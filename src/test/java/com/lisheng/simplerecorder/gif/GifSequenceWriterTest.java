package com.lisheng.simplerecorder.gif;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.awt.image.BufferedImage;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import javax.imageio.ImageIO;
import javax.imageio.ImageReader;
import javax.imageio.metadata.IIOMetadata;
import javax.imageio.stream.ImageInputStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.w3c.dom.NamedNodeMap;
import org.w3c.dom.Node;

class GifSequenceWriterTest {
    @TempDir
    Path tempDir;

    @Test
    void writesAnimatedGifWithThirtyFpsDelayPattern() throws Exception {
        Path file = tempDir.resolve("animated.gif");
        GifSequenceWriter writer = new GifSequenceWriter(file, 30, true);
        writer.addFrame(solid(20, 10, 0xFFFF0000), 20, 10, 0L);
        writer.addFrame(solid(20, 10, 0xFF00FF00), 20, 10, 33_333_333L);
        writer.addFrame(solid(20, 10, 0xFF0000FF), 20, 10, 66_666_666L);
        GifResult result = writer.finish();

        assertEquals(3, result.frameCount());
        assertTrue(result.bytes() > 0);

        ImageReader reader = ImageIO.getImageReadersByFormatName("gif").next();
        try (ImageInputStream input = ImageIO.createImageInputStream(file.toFile())) {
            reader.setInput(input);
            assertEquals(3, reader.getNumImages(true));
            assertEquals(20, reader.getWidth(0));
            assertEquals(10, reader.getHeight(0));
            assertEquals(List.of(3, 4, 3), delays(reader));
        } finally {
            reader.dispose();
        }
    }

    @Test
    void writesNetscapeLoopExtension() throws Exception {
        Path file = tempDir.resolve("loop.gif");
        GifSequenceWriter writer = new GifSequenceWriter(file, 30, true);
        writer.addFrame(solid(8, 8, 0xFFFFFFFF), 8, 8, 0L);
        writer.finish();

        // The standard ImageIO GIF reader does not expose application
        // extensions in its stream metadata tree, so check the wire bytes.
        String ascii = new String(Files.readAllBytes(file), StandardCharsets.ISO_8859_1);
        assertTrue(ascii.contains("NETSCAPE2.0"));
    }

    @Test
    void mergesRunsOfIdenticalFrames() throws Exception {
        Path file = tempDir.resolve("merged.gif");
        GifSequenceWriter writer = new GifSequenceWriter(file, 30, true);
        writer.addFrame(solid(16, 16, 0xFFFF0000), 16, 16, 0L);
        writer.addFrame(solid(16, 16, 0xFFFF0000), 16, 16, 33_333_333L);
        writer.addFrame(solid(16, 16, 0xFF0000FF), 16, 16, 66_666_666L);
        GifResult result = writer.finish();

        assertEquals(2, result.frameCount());

        ImageReader reader = ImageIO.getImageReadersByFormatName("gif").next();
        try (ImageInputStream input = ImageIO.createImageInputStream(file.toFile())) {
            reader.setInput(input);
            // First red frame now covers two capture intervals (3 + 4 cs).
            assertEquals(List.of(7, 3), delays(reader));
        } finally {
            reader.dispose();
        }
    }

    @Test
    void extendsTrailingIdenticalFrame() throws Exception {
        Path file = tempDir.resolve("trailing.gif");
        GifSequenceWriter writer = new GifSequenceWriter(file, 30, true);
        writer.addFrame(solid(8, 8, 0xFF112233), 8, 8, 0L);
        writer.addFrame(solid(8, 8, 0xFF112233), 8, 8, 33_333_333L);
        GifResult result = writer.finish();

        assertEquals(1, result.frameCount());

        ImageReader reader = ImageIO.getImageReadersByFormatName("gif").next();
        try (ImageInputStream input = ImageIO.createImageInputStream(file.toFile())) {
            reader.setInput(input);
            // Both captured frames are identical, so one GIF frame lasts for
            // both capture intervals (3 + 4 centiseconds).
            assertEquals(List.of(7), delays(reader));
        } finally {
            reader.dispose();
        }
    }

    @Test
    void keepsRealTimeWhenFramesAreMissing() throws Exception {
        Path file = tempDir.resolve("dropped.gif");
        GifSequenceWriter writer = new GifSequenceWriter(file, 30, true);
        // Simulate a 100 ms gap because two captures were dropped by a busy
        // encoder. The first GIF frame must stay visible for 10 cs, not 3 cs.
        writer.addFrame(solid(8, 8, 0xFFFF0000), 8, 8, 0L);
        writer.addFrame(solid(8, 8, 0xFF00FF00), 8, 8, 100_000_000L);
        writer.finish();

        ImageReader reader = ImageIO.getImageReadersByFormatName("gif").next();
        try (ImageInputStream input = ImageIO.createImageInputStream(file.toFile())) {
            reader.setInput(input);
            assertEquals(List.of(10, 3), delays(reader));
        } finally {
            reader.dispose();
        }
    }

    @Test
    void preservesRedGreenBlueThroughTheGifCodec() throws Exception {
        Path file = tempDir.resolve("colours.gif");
        GifSequenceWriter writer = new GifSequenceWriter(file, 30, true);
        writer.addFrame(solid(8, 8, 0xFFFF0000), 8, 8, 0L);
        writer.addFrame(solid(8, 8, 0xFF00FF00), 8, 8, 33_333_333L);
        writer.addFrame(solid(8, 8, 0xFF0000FF), 8, 8, 66_666_666L);
        writer.finish();

        ImageReader reader = ImageIO.getImageReadersByFormatName("gif").next();
        try (ImageInputStream input = ImageIO.createImageInputStream(file.toFile())) {
            reader.setInput(input);
            assertEquals(0xFFFF0000, reader.read(0).getRGB(4, 4));
            assertEquals(0xFF00FF00, reader.read(1).getRGB(4, 4));
            assertEquals(0xFF0000FF, reader.read(2).getRGB(4, 4));
        } finally {
            reader.dispose();
        }
    }

    @Test
    void preservesAWholeGradientFrame() throws Exception {
        Path file = tempDir.resolve("gradient.gif");
        int width = 32;
        int height = 32;
        int[] pixels = new int[width * height];
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                int r = x * 255 / (width - 1);
                int g = y * 255 / (height - 1);
                int b = (x + y) * 255 / (width + height - 2);
                pixels[y * width + x] = 0xFF000000 | (r << 16) | (g << 8) | b;
            }
        }

        GifSequenceWriter writer = new GifSequenceWriter(file, 30, true);
        writer.addFrame(pixels, width, height, 0L);
        writer.finish();

        BufferedImage expected = new PaletteQuantizer().toIndexed(pixels, width, height);
        BufferedImage decoded = ImageIO.read(file.toFile());
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                assertEquals(expected.getRGB(x, y), decoded.getRGB(x, y),
                        "pixel " + x + "," + y);
            }
        }
    }

    private static int[] solid(int width, int height, int argb) {
        int[] pixels = new int[width * height];
        java.util.Arrays.fill(pixels, argb);
        return pixels;
    }

    private static List<Integer> delays(ImageReader reader) throws Exception {
        List<Integer> result = new ArrayList<>();
        for (int i = 0; i < reader.getNumImages(true); i++) {
            IIOMetadata metadata = reader.getImageMetadata(i);
            Node root = metadata.getAsTree(metadata.getNativeMetadataFormatName());
            Node control = find(root, "GraphicControlExtension");
            if (control == null) {
                throw new AssertionError("Frame " + i + " has no GraphicControlExtension");
            }
            NamedNodeMap attributes = control.getAttributes();
            Node delay = attributes.getNamedItem("delayTime");
            result.add(Integer.parseInt(delay.getNodeValue()));
        }
        return result;
    }

    private static Node find(Node node, String name) {
        if (node.getNodeName().equalsIgnoreCase(name)) {
            return node;
        }
        for (Node child = node.getFirstChild(); child != null; child = child.getNextSibling()) {
            Node found = find(child, name);
            if (found != null) {
                return found;
            }
        }
        return null;
    }
}
