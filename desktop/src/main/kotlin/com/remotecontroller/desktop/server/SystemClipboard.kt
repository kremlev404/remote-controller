package com.remotecontroller.desktop.server

import com.remotecontroller.shared.model.ClipNote
import java.awt.Image
import java.awt.Toolkit
import java.awt.datatransfer.DataFlavor
import java.awt.datatransfer.StringSelection
import java.awt.datatransfer.Transferable
import java.awt.datatransfer.UnsupportedFlavorException
import java.awt.image.BufferedImage
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.nio.ByteBuffer
import java.security.MessageDigest
import javax.imageio.ImageIO
import javax.swing.SwingUtilities

internal data class ClipboardContents(
    val text: String = "",
    val image: ClipImage? = null,
) {
    fun fingerprint(): String {
        val digest = MessageDigest.getInstance("SHA-256")
        digest.update(text.toByteArray(Charsets.UTF_8))
        digest.update(0)
        image?.bytes?.let(digest::update)
        return digest.digest().joinToString("") { "%02x".format(it) }
    }
}

internal object SystemClipboard {
    private var cachedSample: String? = null
    private var cachedImage: ClipImage? = null

    fun read(): ClipboardContents = onToolkit {
        val contents = Toolkit.getDefaultToolkit().systemClipboard.getContents(null)
            ?: return@onToolkit ClipboardContents().also { forgetImage() }
        val image = readImage(contents)
        if (image == null) forgetImage()
        ClipboardContents(
            text = readText(contents).orEmpty().trim(),
            image = image,
        )
    } ?: ClipboardContents()

    fun readText(): String? = read().text.takeIf { it.isNotEmpty() }

    fun writeText(text: String): Boolean = onToolkit {
        Toolkit.getDefaultToolkit().systemClipboard.setContents(StringSelection(text), null)
        true
    } ?: false

    fun writeImage(bytes: ByteArray): Boolean = onToolkit {
        val image = ImageIO.read(ByteArrayInputStream(bytes)) ?: return@onToolkit false
        Toolkit.getDefaultToolkit().systemClipboard.setContents(ImageTransferable(image), null)
        true
    } ?: false

    private fun readText(contents: Transferable): String? {
        if (!contents.isDataFlavorSupported(DataFlavor.stringFlavor)) return null
        return runCatching { contents.getTransferData(DataFlavor.stringFlavor) as? String }.getOrNull()
    }

    private fun readImage(contents: Transferable): ClipImage? {
        val flavors = runCatching { contents.transferDataFlavors }.getOrNull().orEmpty()
        for (flavor in flavors) {
            if (flavor.primaryType != "image") continue
            if (flavor.subType == "x-java-image") continue
            val bytes = runCatching { bytesOf(contents.getTransferData(flavor)) }.getOrNull() ?: continue
            val type = ClipImages.sniff(bytes) ?: continue
            if (bytes.size > ClipNote.MAX_IMAGE_BYTES) continue
            forgetImage()
            return ClipImage(bytes, type)
        }

        if (!contents.isDataFlavorSupported(DataFlavor.imageFlavor)) return null
        val awt = runCatching { contents.getTransferData(DataFlavor.imageFlavor) as? Image }.getOrNull() ?: return null
        val buffered = awt as? BufferedImage ?: bufferedCopy(awt) ?: return null
        val sample = sampleOf(buffered)
        val cached = cachedImage
        if (sample == cachedSample && cached != null) return cached
        val encoded = ByteArrayOutputStream()
        if (!ImageIO.write(buffered, "png", encoded)) return null
        val bytes = encoded.toByteArray()
        if (bytes.isEmpty() || bytes.size > ClipNote.MAX_IMAGE_BYTES) return null
        val image = ClipImage(bytes, "image/png")
        cachedSample = sample
        cachedImage = image
        return image
    }

    private fun forgetImage() {
        cachedSample = null
        cachedImage = null
    }

    private fun sampleOf(image: BufferedImage): String {
        val digest = MessageDigest.getInstance("SHA-256")
        digest.update(image.width.toString().toByteArray())
        digest.update(image.height.toString().toByteArray())
        val stepX = maxOf(1, image.width / 48)
        val stepY = maxOf(1, image.height / 48)
        var y = 0
        while (y < image.height) {
            var x = 0
            while (x < image.width) {
                digest.update(image.getRGB(x, y).toString().toByteArray())
                x += stepX
            }
            y += stepY
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    private fun bytesOf(data: Any?): ByteArray? = when (data) {
        is ByteArray -> data
        is InputStream -> data.use { it.readBytes() }
        is ByteBuffer -> {
            val copy = data.duplicate()
            ByteArray(copy.remaining()).also(copy::get)
        }
        else -> null
    }

    private fun bufferedCopy(image: Image): BufferedImage? {
        val width = image.getWidth(null)
        val height = image.getHeight(null)
        if (width <= 0 || height <= 0) return null
        val buffered = BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB)
        val graphics = buffered.createGraphics()
        graphics.drawImage(image, 0, 0, null)
        graphics.dispose()
        return buffered
    }

    private fun <T> onToolkit(block: () -> T): T? = runCatching {
        if (SwingUtilities.isEventDispatchThread()) {
            block()
        } else {
            var value: T? = null
            SwingUtilities.invokeAndWait { value = block() }
            value
        }
    }.getOrNull()

    private class ImageTransferable(private val image: BufferedImage) : Transferable {
        override fun getTransferDataFlavors(): Array<DataFlavor> = arrayOf(DataFlavor.imageFlavor)

        override fun isDataFlavorSupported(flavor: DataFlavor): Boolean = flavor == DataFlavor.imageFlavor

        override fun getTransferData(flavor: DataFlavor): Any {
            if (!isDataFlavorSupported(flavor)) throw UnsupportedFlavorException(flavor)
            return image
        }
    }
}
