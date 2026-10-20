package com.remotecontroller.desktop.server

import com.sun.jna.Memory
import com.sun.jna.Native
import com.sun.jna.Pointer
import com.sun.jna.win32.StdCallLibrary
import io.netty.buffer.Unpooled
import io.netty.channel.Channel
import kotlinx.coroutines.suspendCancellableCoroutine
import java.io.FileDescriptor
import java.net.Inet4Address
import java.net.InetSocketAddress
import java.net.NetworkInterface
import java.nio.channels.SocketChannel
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * Ktor's Netty upgrade accepts the websocket and then never copies outgoing frames
 * onto the socket. Frames are written on the accepted channel directly.
 * On Windows the same socket is pinned to the Wi-Fi adapter so a VPN default route
 * cannot swallow the reply.
 */
internal object SocketFrames {
    private val channels = java.util.concurrent.ConcurrentHashMap<String, Channel>()

    fun attach(channel: Channel) {
        val remote = channel.remoteAddress() as? InetSocketAddress ?: return
        val address = remote.address?.hostAddress ?: return
        val key = "$address:${remote.port}"
        channels[key] = channel
        pinToLan(channel)
        channel.closeFuture().addListener { channels.remove(key, channel) }
    }

    fun endpoint(address: String, port: Int): String = "$address:$port"

    suspend fun sendText(endpoint: String, text: String) {
        write(endpoint, frame(0x1, text.toByteArray(Charsets.UTF_8)))
    }

    suspend fun sendBinary(endpoint: String, payload: ByteArray) {
        write(endpoint, frame(0x2, payload))
    }

    suspend fun sendPong(endpoint: String, payload: ByteArray) {
        write(endpoint, frame(0xA, payload))
    }

    fun close(endpoint: String) {
        val channel = channels[endpoint] ?: return
        val closeFrame = Unpooled.wrappedBuffer(frame(0x8, byteArrayOf(0x03, 0xE8.toByte())))
        channel.writeAndFlush(closeFrame).addListener { channel.close() }
    }

    private suspend fun write(endpoint: String, bytes: ByteArray) {
        val channel = channels[endpoint] ?: throw IllegalStateException("Socket for $endpoint is already closed")
        suspendCancellableCoroutine { continuation ->
            val future = channel.writeAndFlush(Unpooled.wrappedBuffer(bytes))
            future.addListener {
                if (!continuation.isActive) return@addListener
                val cause = future.cause()
                if (future.isSuccess) continuation.resume(Unit)
                else continuation.resumeWithException(cause ?: IllegalStateException("Socket write failed"))
            }
        }
    }

    private fun frame(opcode: Int, payload: ByteArray): ByteArray {
        val header = when {
            payload.size < 126 -> 2
            payload.size <= 0xFFFF -> 4
            else -> 10
        }
        val bytes = ByteArray(header + payload.size)
        bytes[0] = (0x80 or opcode).toByte()
        when {
            payload.size < 126 -> bytes[1] = payload.size.toByte()
            payload.size <= 0xFFFF -> {
                bytes[1] = 126
                bytes[2] = (payload.size ushr 8).toByte()
                bytes[3] = payload.size.toByte()
            }
            else -> {
                bytes[1] = 127
                var length = payload.size.toLong()
                for (index in 9 downTo 2) {
                    bytes[index] = length.toByte()
                    length = length ushr 8
                }
            }
        }
        payload.copyInto(bytes, header)
        return bytes
    }

    private fun pinToLan(channel: Channel) {
        if (!System.getProperty("os.name").startsWith("Windows", ignoreCase = true)) return
        val local = (channel.localAddress() as? InetSocketAddress)?.address as? Inet4Address ?: return
        val nic = NetworkInterface.getByInetAddress(local) ?: return
        if (nic.index <= 0) return
        val javaChannel = javaSocketChannel(channel) ?: return
        val handle = socketHandle(javaChannel) ?: return
        WindowsSockets.pin(handle, nic.index)
    }

    private fun javaSocketChannel(channel: Channel): SocketChannel? = try {
        var type: Class<*>? = channel.javaClass
        var found: java.lang.reflect.Method? = null
        while (type != null && found == null) {
            found = type.declaredMethods.firstOrNull { it.name == "javaChannel" && it.parameterCount == 0 }
            type = type.superclass
        }
        val method = found ?: return null
        method.isAccessible = true
        method.invoke(channel) as? SocketChannel
    } catch (_: Exception) {
        null
    }

    private fun socketHandle(channel: SocketChannel): Long? = try {
        val getter = channel.javaClass.methods.firstOrNull { it.name == "getFD" && it.parameterCount == 0 }
        val descriptor = if (getter != null) {
            getter.isAccessible = true
            getter.invoke(channel) as? FileDescriptor
        } else {
            null
        } ?: return null
        val handleField = FileDescriptor::class.java.getDeclaredField("handle").apply { isAccessible = true }
        val fdField = FileDescriptor::class.java.getDeclaredField("fd").apply { isAccessible = true }
        val handle = handleField.getLong(descriptor)
        val fd = fdField.getInt(descriptor)
        when {
            handle != 0L && handle != -1L -> handle
            fd > 0 -> fd.toLong()
            else -> null
        }
    } catch (_: Exception) {
        null
    }

    private object WindowsSockets {
        private const val IPPROTO_IP = 0
        private const val IP_UNICAST_IF = 31
        private val library = Native.load("ws2_32", Ws2_32::class.java)

        fun pin(socket: Long, interfaceIndex: Int) {
            val buffer = Memory(4).apply { setInt(0, Integer.reverseBytes(interfaceIndex)) }
            library.setsockopt(socket, IPPROTO_IP, IP_UNICAST_IF, buffer, 4)
        }

        private interface Ws2_32 : StdCallLibrary {
            fun setsockopt(socket: Long, level: Int, name: Int, value: Pointer, length: Int): Int
        }
    }
}
