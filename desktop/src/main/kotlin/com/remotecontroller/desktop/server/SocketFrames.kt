package com.remotecontroller.desktop.server

import com.sun.jna.Library
import com.sun.jna.Memory
import com.sun.jna.Native
import com.sun.jna.Pointer
import com.sun.jna.win32.StdCallLibrary
import io.netty.bootstrap.ServerBootstrap
import io.netty.buffer.Unpooled
import io.netty.channel.Channel
import io.netty.channel.ChannelInitializer
import io.ktor.websocket.Frame
import kotlinx.coroutines.suspendCancellableCoroutine
import java.io.FileDescriptor
import java.net.Inet4Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.NetworkInterface
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * Ktor's Netty upgrade accepts the websocket and then never copies outgoing frames
 * onto the socket. Frames are written on the accepted channel directly.
 *
 * A VPN can steal the LAN route and point it at utun. Windows still answers on the
 * NIC that received the SYN. macOS does not: the kernel sends SYN-ACK from the
 * listening socket, using the routing table, before accept returns. Java listens on
 * an IPv6 socket, so the pin is IPV6_BOUND_IF and it has to be set before bind.
 * Accepted sockets inherit it. Windows keeps using IP_UNICAST_IF.
 */
internal object SocketFrames {
    private val channels = java.util.concurrent.ConcurrentHashMap<String, Channel>()
    private val tunnels = java.util.concurrent.ConcurrentHashMap<String, Tunnel>()

    /** A relay link is an outgoing client websocket, not a Netty channel. Same endpoint API. */
    interface Tunnel {
        suspend fun send(frame: Frame)

        fun close()
    }

    fun bindTunnel(endpoint: String, tunnel: Tunnel) {
        tunnels[endpoint] = tunnel
    }

    fun unbindTunnel(endpoint: String) {
        tunnels.remove(endpoint)
    }
    private val descriptorMissLogged = AtomicBoolean(false)

    @Volatile
    private var armedLan: ArmedLan? = null

    @Volatile
    private var listenerPinned = false

    fun armLan(host: String) {
        listenerPinned = false
        armedLan = null
        if (!isMac) return
        if (host.isBlank() || host == "0.0.0.0" || host == "127.0.0.1") return
        val address = runCatching { InetAddress.getByName(host) }.getOrNull() as? Inet4Address ?: return
        val nic = NetworkInterface.getByInetAddress(address) ?: return
        val index = MacSockets.indexOf(nic.name).takeIf { it > 0 } ?: nic.index.takeIf { it > 0 } ?: return
        armedLan = ArmedLan(nic.name, index)
        println("[CommandServer] LAN replies will use ${nic.name} index $index ($host)")
    }

    fun installListenerPin(bootstrap: ServerBootstrap) {
        if (!isMac) return
        bootstrap.handler(object : ChannelInitializer<Channel>() {
            override fun initChannel(ch: Channel) {
                // Before bind. Setting the option again after bind returns EINVAL.
                pinListening(ch)
            }
        })
    }

    fun pinListening(channel: Channel) {
        if (!isMac) return
        val lan = armedLan
        if (lan == null) {
            println("[CommandServer] listening socket not pinned; no LAN adapter for this bind")
            return
        }
        val fd = descriptor(channel)
        if (fd == null) {
            println("[CommandServer] listening socket not pinned; socket fd unavailable")
            return
        }
        if (!MacSockets.pin(fd, lan.index, lan.name)) return
        if (!listenerPinned) {
            listenerPinned = true
            println("[CommandServer] listening socket pinned to ${lan.name} (index ${lan.index})")
        }
    }

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
        tunnels[endpoint]?.let { return it.send(Frame.Text(text)) }
        write(endpoint, frame(0x1, text.toByteArray(Charsets.UTF_8)))
    }

    suspend fun sendBinary(endpoint: String, payload: ByteArray) {
        tunnels[endpoint]?.let { return it.send(Frame.Binary(true, payload)) }
        write(endpoint, frame(0x2, payload))
    }

    suspend fun sendPong(endpoint: String, payload: ByteArray) {
        // The client websocket answers pings by itself.
        if (tunnels.containsKey(endpoint)) return
        write(endpoint, frame(0xA, payload))
    }

    fun close(endpoint: String) {
        tunnels[endpoint]?.let { return it.close() }
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
        if (isLoopback(channel)) return
        when {
            isWindows -> {
                val local = (channel.localAddress() as? InetSocketAddress)?.address as? Inet4Address ?: return
                val nic = NetworkInterface.getByInetAddress(local) ?: return
                if (nic.index <= 0) return
                val handle = nioHandle(channel) ?: return
                WindowsSockets.pin(handle, nic.index)
            }
            isMac -> {
                val lan = armedLan ?: return
                val fd = descriptor(channel) ?: return
                MacSockets.pin(fd, lan.index, lan.name)
            }
        }
    }

    private fun isLoopback(channel: Channel): Boolean {
        val local = (channel.localAddress() as? InetSocketAddress)?.address?.isLoopbackAddress == true
        val remote = (channel.remoteAddress() as? InetSocketAddress)?.address?.isLoopbackAddress == true
        return local || remote
    }

    private fun descriptor(channel: Channel): Int? {
        unixFd(channel)?.let { return it }
        val handle = nioHandle(channel) ?: return null
        return handle.toInt().takeIf { it > 0 }
    }

    /** Ktor uses Netty kqueue on macOS. Those channels expose fd(), not a Java SocketChannel. */
    private fun unixFd(channel: Channel): Int? = try {
        val fdMethod = channel.javaClass.methods.firstOrNull { it.name == "fd" && it.parameterCount == 0 }
            ?: return null
        val descriptor = fdMethod.invoke(channel) ?: return null
        val intValue = descriptor.javaClass.methods.firstOrNull { it.name == "intValue" && it.parameterCount == 0 }
            ?: return null
        (intValue.invoke(descriptor) as? Int)?.takeIf { it > 0 }
    } catch (_: Exception) {
        null
    }

    private fun nioHandle(channel: Channel): Long? = try {
        var type: Class<*>? = channel.javaClass
        var found: java.lang.reflect.Method? = null
        while (type != null && found == null) {
            found = type.declaredMethods.firstOrNull { it.name == "javaChannel" && it.parameterCount == 0 }
            type = type.superclass
        }
        val method = found ?: return null
        method.isAccessible = true
        val javaChannel = method.invoke(channel) ?: return null
        var javaType: Class<*>? = javaChannel.javaClass
        var getter: java.lang.reflect.Method? = null
        while (javaType != null && getter == null) {
            getter = javaType.declaredMethods.firstOrNull { it.name == "getFD" && it.parameterCount == 0 }
            javaType = javaType.superclass
        }
        val fdMethod = getter ?: return null
        fdMethod.isAccessible = true
        val descriptor = fdMethod.invoke(javaChannel) as? FileDescriptor ?: return null
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
        if (descriptorMissLogged.compareAndSet(false, true)) {
            println("[CommandServer] could not read the socket fd to pin it to the LAN adapter")
        }
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

    private object MacSockets {
        private const val IPPROTO_IP = 0
        private const val IPPROTO_IPV6 = 41
        private const val IP_BOUND_IF = 25
        private const val IPV6_BOUND_IF = 125
        private val library = Native.load("c", LibC::class.java)

        fun indexOf(name: String): Int = runCatching { library.if_nametoindex(name) }.getOrDefault(0)

        fun pin(socket: Int, interfaceIndex: Int, name: String): Boolean {
            if (socket <= 0 || interfaceIndex <= 0) return false
            if (set(socket, IPPROTO_IP, IP_BOUND_IF, interfaceIndex)) return true
            if (set(socket, IPPROTO_IPV6, IPV6_BOUND_IF, interfaceIndex)) return true
            // An accepted socket inherits the listener option. A second setsockopt
            // then fails with EINVAL, while getsockopt still reports the interface.
            if (get(socket, IPPROTO_IP, IP_BOUND_IF) == interfaceIndex) return true
            if (get(socket, IPPROTO_IPV6, IPV6_BOUND_IF) == interfaceIndex) return true
            println(
                "[CommandServer] could not pin socket to $name ($interfaceIndex), errno ${Native.getLastError()}",
            )
            return false
        }

        private fun set(socket: Int, level: Int, name: Int, value: Int): Boolean {
            val buffer = Memory(4).apply { setInt(0, value) }
            return library.setsockopt(socket, level, name, buffer, 4) == 0
        }

        private fun get(socket: Int, level: Int, name: Int): Int {
            val value = Memory(4)
            val length = Memory(4).apply { setInt(0, 4) }
            return if (library.getsockopt(socket, level, name, value, length) == 0) value.getInt(0) else -1
        }

        private interface LibC : Library {
            fun setsockopt(socket: Int, level: Int, name: Int, value: Pointer, length: Int): Int

            fun getsockopt(socket: Int, level: Int, name: Int, value: Pointer, length: Pointer): Int

            fun if_nametoindex(name: String): Int
        }
    }

    private data class ArmedLan(
        val name: String,
        val index: Int,
    )

    private val isMac = System.getProperty("os.name").startsWith("Mac", ignoreCase = true)
    private val isWindows = System.getProperty("os.name").startsWith("Windows", ignoreCase = true)
}
