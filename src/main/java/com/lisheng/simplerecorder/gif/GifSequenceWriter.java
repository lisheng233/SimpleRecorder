package com.lisheng.simplerecorder.gif;

import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import javax.imageio.IIOImage;
import javax.imageio.ImageIO;
import javax.imageio.ImageTypeSpecifier;
import javax.imageio.ImageWriteParam;
import javax.imageio.ImageWriter;
import javax.imageio.metadata.IIOMetadata;
import javax.imageio.metadata.IIOMetadataNode;
import javax.imageio.stream.FileImageOutputStream;
import javax.imageio.stream.ImageOutputStream;
import org.w3c.dom.Node;

/**
 * Streaming GIF89a writer built on the ImageIO GIF codec.
 *
 * <p>Frames are handed to the codec one at a time, so a long recording never
 * keeps every frame in memory. The writer also merges runs of identical frames
 * into a longer frame delay, which keeps menus and static shots small.</p>
 *
 * <p>Only the encoder thread talks to this class.</p>
 */
public final class GifSequenceWriter {
    private static final int MAX_GIF_DELAY_CS = 0xFFFF;

    private final Path path;
    private final int fps;
    private final boolean loop;
    private final ImageWriter writer;
    private final ImageWriteParam param;
    private final PaletteQuantizer quantizer = new PaletteQuantizer();
    private final ImageOutputStream output;

    private int frameWidth;
    private int frameHeight;
    private int writtenFrames;
    private int pendingStartCs;
    private int[] pendingPixels;
    private long firstTimestampNanos;
    private long lastTimestampNanos;
    private boolean finished;

    public GifSequenceWriter(Path path, int fps, boolean loop) throws IOException {
        if (fps < 1) {
            throw new IllegalArgumentException("fps must be >= 1");
        }
        this.path = path;
        this.fps = fps;
        this.loop = loop;

        Path parent = path.toAbsolutePath().getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }

        ImageWriter imageWriter = ImageIO.getImageWritersByFormatName("gif").next();
        ImageWriteParam writeParam = imageWriter.getDefaultWriteParam();
        IIOMetadata streamMetadata = imageWriter.getDefaultStreamMetadata(writeParam);

        ImageOutputStream imageOutput = new FileImageOutputStream(path.toFile());
        try {
            imageWriter.setOutput(imageOutput);
            imageWriter.prepareWriteSequence(streamMetadata);
        } catch (IOException | RuntimeException e) {
            closeQuietly(imageWriter, imageOutput);
            throw e;
        }

        this.writer = imageWriter;
        this.param = writeParam;
        this.output = imageOutput;
    }

    /**
     * Adds one captured frame in ARGB format (alpha is ignored).
     *
     * <p>One frame is buffered: its delay is not final until the next distinct
     * frame arrives, because runs of identical frames are merged into the delay
     * of the frame that is actually visible.</p>
     */
    public void addFrame(int[] argbPixels, int width, int height, long timestampNanos) throws IOException {
        if (finished) {
            throw new IllegalStateException("Writer is already finished");
        }
        if (width <= 0 || height <= 0) {
            throw new IllegalArgumentException("Invalid frame size " + width + "x" + height);
        }
        if (argbPixels.length != width * height) {
            throw new IllegalArgumentException("Pixel array does not match frame size");
        }

        if (frameWidth == 0) {
            frameWidth = width;
            frameHeight = height;
        } else if (frameWidth != width || frameHeight != height) {
            throw new IOException("Frame size changed from " + frameWidth + "x" + frameHeight
                    + " to " + width + "x" + height);
        }

        if (pendingPixels == null) {
            firstTimestampNanos = timestampNanos;
            lastTimestampNanos = timestampNanos;
            pendingStartCs = 0;
            pendingPixels = argbPixels.clone();
            return;
        }

        lastTimestampNanos = timestampNanos;

        if (Arrays.equals(pendingPixels, argbPixels)) {
            return;
        }

        int startCs = centis(timestampNanos);
        int delayCs = Math.max(1, startCs - pendingStartCs);
        int[] frameToWrite = pendingPixels;
        pendingPixels = argbPixels.clone();
        pendingStartCs = Math.max(pendingStartCs, startCs);
        writeFrame(frameToWrite, delayCs);
    }

    /**
     * Flushes the GIF trailer and closes the underlying stream.
     */
    public GifResult finish() throws IOException {
        if (finished) {
            throw new IllegalStateException("Writer is already finished");
        }
        finished = true;

        try {
            if (pendingPixels != null) {
                int endCs = centis(lastTimestampNanos + nominalFrameNanos());
                int delayCs = Math.max(1, endCs - pendingStartCs);
                writeFrame(pendingPixels, delayCs);
                pendingPixels = null;
            }
            if (writtenFrames == 0) {
                throw new IOException("No frames were written");
            }

            writer.endWriteSequence();
            output.flush();
        } finally {
            closeQuietly(writer, output);
        }

        return new GifResult(path.toAbsolutePath(), writtenFrames, Files.size(path));
    }

    /**
     * Closes the writer and removes the partial file.
     */
    public void abort() {
        finished = true;
        closeQuietly(writer, output);
        try {
            Files.deleteIfExists(path);
        } catch (IOException ignored) {
            // The file is not usable anyway; nothing useful to do here.
        }
    }

    private void writeFrame(int[] argbPixels, int delayCs) throws IOException {
        int remaining = Math.max(1, delayCs);
        while (remaining > 0) {
            int chunk = Math.min(remaining, MAX_GIF_DELAY_CS);
            writeSingleFrame(argbPixels, chunk);
            writtenFrames++;
            remaining -= chunk;
        }
    }

    private void writeSingleFrame(int[] argbPixels, int delayCs) throws IOException {
        BufferedImage image = quantizer.toIndexed(argbPixels, frameWidth, frameHeight);

        IIOMetadata metadata = writer.getDefaultImageMetadata(
                ImageTypeSpecifier.createFromRenderedImage(image), param);
        String format = metadata.getNativeMetadataFormatName();
        IIOMetadataNode root = (IIOMetadataNode) metadata.getAsTree(format);

        // The default tree carries a 256-entry LocalColorTable. Merging that
        // table back through ImageIO's metadata parser is lossy for the last
        // entries; letting the writer rebuild the table from the indexed
        // image's ColorModel keeps every colour exact.
        Node localColorTable = find(root, "LocalColorTable");
        if (localColorTable != null) {
            root.removeChild(localColorTable);
        }

        IIOMetadataNode control = findOrCreate(root, "GraphicControlExtension");
        control.setAttribute("disposalMethod", "none");
        control.setAttribute("userInputFlag", "FALSE");
        control.setAttribute("transparentColorFlag", "FALSE");
        control.setAttribute("delayTime", Integer.toString(delayCs));
        control.setAttribute("transparentColorIndex", "0");

        // The JDK GIF writer emits application extensions from image metadata,
        // not stream metadata. The NETSCAPE extension must precede the first
        // image, which is exactly where writeToSequence() puts it.
        if (loop && writtenFrames == 0) {
            IIOMetadataNode applicationExtensions = findOrCreate(root, "ApplicationExtensions");
            IIOMetadataNode application = new IIOMetadataNode("ApplicationExtension");
            application.setAttribute("applicationID", "NETSCAPE");
            application.setAttribute("authenticationCode", "2.0");
            application.setUserObject(new byte[]{1, 0, 0});
            applicationExtensions.appendChild(application);
        }

        metadata.setFromTree(format, root);

        writer.writeToSequence(new IIOImage(image, null, metadata), param);
    }

    /**
     * Converts an absolute capture time into centiseconds on a shared grid.
     * Using a cumulative grid instead of rounding each interval separately
     * keeps 30 FPS from drifting to 33.3 FPS, and dropped frames simply produce
     * a longer delay instead of a faster GIF.
     */
    private int centis(long timestampNanos) {
        long delta = Math.max(0L, timestampNanos - firstTimestampNanos);
        return (int) Math.round(delta / 10_000_000.0D);
    }

    private long nominalFrameNanos() {
        return Math.max(1L, 1_000_000_000L / fps);
    }

    private static IIOMetadataNode findOrCreate(Node root, String name) {
        Node existing = find(root, name);
        if (existing != null) {
            return (IIOMetadataNode) existing;
        }
        IIOMetadataNode created = new IIOMetadataNode(name);
        root.appendChild(created);
        return created;
    }

    private static Node find(Node root, String name) {
        for (Node node = root.getFirstChild(); node != null; node = node.getNextSibling()) {
            if (node.getNodeName().equalsIgnoreCase(name)) {
                return node;
            }
        }
        return null;
    }

    private static int saturatingAdd(int left, int right) {
        long sum = (long) left + right;
        return sum > Integer.MAX_VALUE ? Integer.MAX_VALUE : (int) sum;
    }

    private static void closeQuietly(ImageWriter writer, ImageOutputStream output) {
        try {
            if (writer != null) {
                writer.dispose();
            }
        } catch (RuntimeException ignored) {
            // Best-effort cleanup.
        }
        try {
            if (output != null) {
                output.close();
            }
        } catch (IOException ignored) {
            // Best-effort cleanup.
        }
    }
}
