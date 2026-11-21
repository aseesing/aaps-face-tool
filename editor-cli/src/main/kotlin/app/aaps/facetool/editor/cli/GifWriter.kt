package app.aaps.facetool.editor.cli

import app.aaps.facetool.editor.model.wrongUse
import java.awt.image.BufferedImage
import java.io.File
import javax.imageio.IIOImage
import javax.imageio.ImageIO
import javax.imageio.ImageTypeSpecifier
import javax.imageio.metadata.IIOMetadataNode

/** Writes picture frames as one GIF animation that loops, with the GIF writer of the JDK. */
object GifWriter {

    fun write(frames: List<File>, frameMs: Int, out: File) {
        if (frames.isEmpty()) wrongUse("There are no frames for the GIF")
        val writer = ImageIO.getImageWritersBySuffix("gif").next()
        out.absoluteFile.parentFile?.mkdirs()
        out.delete()
        ImageIO.createImageOutputStream(out).use { stream ->
            writer.output = stream
            writer.prepareWriteSequence(null)
            for (file in frames) {
                val source = ImageIO.read(file) ?: wrongUse("${file.path} is not a picture")
                // A GIF has no soft see-through: draw the frame on black
                val image = BufferedImage(source.width, source.height, BufferedImage.TYPE_INT_RGB)
                image.createGraphics().apply { drawImage(source, 0, 0, null); dispose() }
                val metadata = writer.getDefaultImageMetadata(ImageTypeSpecifier.createFromRenderedImage(image), null)
                val format = metadata.nativeMetadataFormatName
                val root = metadata.getAsTree(format) as IIOMetadataNode
                child(root, "GraphicControlExtension").apply {
                    setAttribute("disposalMethod", "none")
                    setAttribute("userInputFlag", "FALSE")
                    setAttribute("transparentColorFlag", "FALSE")
                    setAttribute("delayTime", maxOf(1, frameMs / 10).toString())
                    setAttribute("transparentColorIndex", "0")
                }
                child(root, "ApplicationExtensions").appendChild(IIOMetadataNode("ApplicationExtension").apply {
                    setAttribute("applicationID", "NETSCAPE")
                    setAttribute("authenticationCode", "2.0")
                    userObject = byteArrayOf(1, 0, 0)
                })
                metadata.setFromTree(format, root)
                writer.writeToSequence(IIOImage(image, null, metadata), null)
            }
            writer.endWriteSequence()
        }
        writer.dispose()
    }

    private fun child(root: IIOMetadataNode, name: String): IIOMetadataNode {
        for (i in 0 until root.length) (root.item(i) as IIOMetadataNode).let { if (it.nodeName == name) return it }
        return IIOMetadataNode(name).also { root.appendChild(it) }
    }
}
