package com.lisheng.simplerecorder.gif;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.file.Path;
import javax.imageio.IIOImage;
import javax.imageio.ImageIO;
import javax.imageio.ImageTypeSpecifier;
import javax.imageio.ImageWriteParam;
import javax.imageio.ImageWriter;
import javax.imageio.metadata.IIOMetadata;
import javax.imageio.metadata.IIOMetadataNode;
import javax.imageio.stream.FileImageOutputStream;
import javax.imageio.stream.ImageOutputStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.w3c.dom.Node;

class PaletteQuantizerTest {
    @TempDir
    Path tempDir;

    @Test
    void keepsPrimaryColoursInTheRightChannels() {
        PaletteQuantizer quantizer = new PaletteQuantizer();

        assertEquals(0xFFFF0000, imagePixel(quantizer, 0xFFFF0000));
        assertEquals(0xFF00FF00, imagePixel(quantizer, 0xFF00FF00));
        assertEquals(0xFF0000FF, imagePixel(quantizer, 0xFF0000FF));
        assertEquals(0xFFFFFFFF, imagePixel(quantizer, 0xFFFFFFFF));
        assertEquals(0xFF000000, imagePixel(quantizer, 0xFF000000));
        // Sand and sky are typical mid-tone colours, not primaries.
        assertEquals(0xFFDBCFA3, imagePixel(quantizer, 0xFFDBCFA3));
        assertEquals(0xFF64A0FF, imagePixel(quantizer, 0xFF64A0FF));
        assertEquals(0xFF866043, imagePixel(quantizer, 0xFF866043));
    }

    @Test
    void plainImageIoWritePreservesIndexedColours() throws Exception {
        PaletteQuantizer quantizer = new PaletteQuantizer();
        BufferedImage image = quantizer.toIndexed(new int[]{0xFFFF0000}, 1, 1);

        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        ImageIO.write(image, "gif", bytes);

        BufferedImage decoded = ImageIO.read(new ByteArrayInputStream(bytes.toByteArray()));
        assertEquals(0xFFFF0000, decoded.getRGB(0, 0));
    }

    @Test
    void sequenceWithoutMetadataPreservesColours() throws Exception {
        assertEquals(0xFFFF0000, writeSequence(false));
    }

    @Test
    void sequenceWithMetadataPreservesColours() throws Exception {
        assertEquals(0xFFFF0000, writeSequence(true));
    }

    private int writeSequence(boolean withMetadata) throws Exception {
        PaletteQuantizer quantizer = new PaletteQuantizer();
        BufferedImage image = quantizer.toIndexed(new int[]{0xFFFF0000}, 1, 1);

        Path file = tempDir.resolve(withMetadata ? "with-meta.gif" : "without-meta.gif");
        ImageWriter writer = ImageIO.getImageWritersByFormatName("gif").next();
        ImageWriteParam param = writer.getDefaultWriteParam();
        IIOMetadata stream = writer.getDefaultStreamMetadata(param);
        try (ImageOutputStream output = new FileImageOutputStream(file.toFile())) {
            writer.setOutput(output);
            writer.prepareWriteSequence(stream);

            IIOMetadata metadata = null;
            if (withMetadata) {
                metadata = writer.getDefaultImageMetadata(ImageTypeSpecifier.createFromRenderedImage(image), param);
                String format = metadata.getNativeMetadataFormatName();
                IIOMetadataNode root = (IIOMetadataNode) metadata.getAsTree(format);
                Node localColorTable = find(root, "LocalColorTable");
                if (localColorTable != null) {
                    root.removeChild(localColorTable);
                }
                IIOMetadataNode gce = find(root, "GraphicControlExtension");
                gce.setAttribute("delayTime", "3");
                metadata.setFromTree(format, root);
            }
            writer.writeToSequence(new IIOImage(image, null, metadata), param);
            writer.endWriteSequence();
        } finally {
            writer.dispose();
        }

        BufferedImage decoded = ImageIO.read(file.toFile());
        return decoded.getRGB(0, 0);
    }

    private static IIOMetadataNode find(Node root, String name) {
        for (Node node = root.getFirstChild(); node != null; node = node.getNextSibling()) {
            if (node.getNodeName().equalsIgnoreCase(name)) {
                return (IIOMetadataNode) node;
            }
        }
        IIOMetadataNode node = new IIOMetadataNode(name);
        root.appendChild(node);
        return node;
    }

    private static int imagePixel(PaletteQuantizer quantizer, int argb) {
        BufferedImage image = quantizer.toIndexed(new int[]{argb}, 1, 1);
        return image.getRGB(0, 0);
    }
}
