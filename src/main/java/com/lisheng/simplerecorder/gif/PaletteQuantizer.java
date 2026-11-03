package com.lisheng.simplerecorder.gif;

import java.awt.image.BufferedImage;
import java.awt.image.IndexColorModel;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.PriorityQueue;

/**
 * Per-frame adaptive palette for fast GIF output.
 *
 * <p>ImageIO's built-in quantizer is high quality but costs roughly 50 ms for a
 * 1280x747 frame. This class builds a 5-bit RGB histogram, runs a weighted
 * median cut into at most 256 boxes, and then maps every pixel through a
 * 32768-entry lookup table. The GIF writer receives an already indexed image,
 * so it only has to run LZW compression.</p>
 *
 * <p>The adaptive palette keeps Minecraft's sandy/earthy mid-tones together;
 * a fixed RGB cube produced visible green/purple casts on those colours.</p>
 */
final class PaletteQuantizer {
    private static final int BIN_COUNT = 32 * 32 * 32;
    private static final int MAX_PALETTE = 256;

    private final int[] counts = new int[BIN_COUNT];
    private final long[] sumR = new long[BIN_COUNT];
    private final long[] sumG = new long[BIN_COUNT];
    private final long[] sumB = new long[BIN_COUNT];
    private final int[] bins = new int[BIN_COUNT];
    private final int[] temp = new int[BIN_COUNT];
    private final int[] sortCounts = new int[33];
    private final byte[] lookup = new byte[BIN_COUNT];
    private byte[] indices = new byte[0];

    BufferedImage toIndexed(int[] argbPixels, int width, int height) {
        int size = width * height;
        if (indices.length != size) {
            indices = new byte[size];
        }

        Arrays.fill(counts, 0);
        Arrays.fill(sumR, 0L);
        Arrays.fill(sumG, 0L);
        Arrays.fill(sumB, 0L);

        for (int i = 0; i < size; i++) {
            int pixel = argbPixels[i];
            int r = (pixel >>> 16) & 0xFF;
            int g = (pixel >>> 8) & 0xFF;
            int b = pixel & 0xFF;
            int bin = ((r >>> 3) << 10) | ((g >>> 3) << 5) | (b >>> 3);
            counts[bin]++;
            sumR[bin] += r;
            sumG[bin] += g;
            sumB[bin] += b;
        }

        int nonEmpty = 0;
        for (int i = 0; i < BIN_COUNT; i++) {
            if (counts[i] != 0) {
                bins[nonEmpty++] = i;
            }
        }
        if (nonEmpty == 0) {
            throw new IllegalArgumentException("Cannot quantize an empty frame");
        }

        List<Box> boxes = medianCut(nonEmpty);
        int paletteSize = Math.max(2, boxes.size());
        byte[] reds = new byte[paletteSize];
        byte[] greens = new byte[paletteSize];
        byte[] blues = new byte[paletteSize];

        Arrays.fill(lookup, (byte) 0);
        for (int paletteIndex = 0; paletteIndex < boxes.size(); paletteIndex++) {
            Box box = boxes.get(paletteIndex);
            long total = box.count;
            reds[paletteIndex] = (byte) ((box.weightedR + total / 2) / total);
            greens[paletteIndex] = (byte) ((box.weightedG + total / 2) / total);
            blues[paletteIndex] = (byte) ((box.weightedB + total / 2) / total);
            for (int i = box.start; i < box.end; i++) {
                lookup[bins[i]] = (byte) paletteIndex;
            }
        }

        if (boxes.size() == 1) {
            // IndexColorModel requires at least a two-entry table for GIF.
            reds[1] = reds[0];
            greens[1] = greens[0];
            blues[1] = blues[0];
        }

        byte[] output = indices;
        for (int i = 0; i < size; i++) {
            int pixel = argbPixels[i];
            int bin = (((pixel >>> 16) & 0xFF) >>> 3) << 10
                    | ((((pixel >>> 8) & 0xFF) >>> 3) << 5)
                    | ((pixel & 0xFF) >>> 3);
            output[i] = lookup[bin];
        }

        IndexColorModel colorModel = new IndexColorModel(8, paletteSize, reds, greens, blues);
        BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_BYTE_INDEXED, colorModel);
        image.getRaster().setDataElements(0, 0, width, height, output);
        return image;
    }

    private List<Box> medianCut(int nonEmpty) {
        PriorityQueue<Box> queue = new PriorityQueue<>(
                Comparator.comparingLong((Box box) -> box.count).reversed());
        List<Box> result = new ArrayList<>(MAX_PALETTE);
        queue.add(new Box(0, nonEmpty));

        while (result.size() + queue.size() < MAX_PALETTE && !queue.isEmpty()) {
            Box box = queue.poll();
            if (!box.splittable()) {
                result.add(box);
                continue;
            }

            int split = split(box);
            if (split <= box.start || split >= box.end) {
                result.add(box);
                continue;
            }
            queue.add(new Box(box.start, split));
            queue.add(new Box(split, box.end));
        }

        result.addAll(queue);
        return result;
    }

    private int split(Box box) {
        int redRange = box.maxR - box.minR;
        int greenRange = box.maxG - box.minG;
        int blueRange = box.maxB - box.minB;
        int channel = redRange >= greenRange && redRange >= blueRange ? 0
                : greenRange >= blueRange ? 1 : 2;

        Arrays.fill(sortCounts, 0);
        for (int i = box.start; i < box.end; i++) {
            sortCounts[channel(bins[i], channel) + 1]++;
        }
        for (int i = 1; i < sortCounts.length; i++) {
            sortCounts[i] += sortCounts[i - 1];
        }
        for (int i = box.start; i < box.end; i++) {
            int bin = bins[i];
            int position = box.start + sortCounts[channel(bin, channel)]++;
            temp[position] = bin;
        }
        System.arraycopy(temp, box.start, bins, box.start, box.end - box.start);

        long half = box.count / 2L;
        long cumulative = 0L;
        int split = box.start;
        for (int i = box.start; i < box.end; i++) {
            cumulative += counts[bins[i]];
            if (cumulative >= half) {
                split = i + 1;
                break;
            }
        }
        if (split <= box.start) {
            split = box.start + 1;
        } else if (split >= box.end) {
            split = box.end - 1;
        }
        return split;
    }

    private static int channel(int bin, int channel) {
        return switch (channel) {
            case 0 -> bin >>> 10;
            case 1 -> (bin >>> 5) & 31;
            default -> bin & 31;
        };
    }

    private final class Box {
        final int start;
        final int end;
        long count;
        long weightedR;
        long weightedG;
        long weightedB;
        int minR = 32;
        int maxR = -1;
        int minG = 32;
        int maxG = -1;
        int minB = 32;
        int maxB = -1;

        Box(int start, int end) {
            this.start = start;
            this.end = end;
            for (int i = start; i < end; i++) {
                int bin = bins[i];
                int weight = counts[bin];
                int r = bin >>> 10;
                int g = (bin >>> 5) & 31;
                int b = bin & 31;
                count += weight;
                weightedR += PaletteQuantizer.this.sumR[bin];
                weightedG += PaletteQuantizer.this.sumG[bin];
                weightedB += PaletteQuantizer.this.sumB[bin];
                minR = Math.min(minR, r);
                maxR = Math.max(maxR, r);
                minG = Math.min(minG, g);
                maxG = Math.max(maxG, g);
                minB = Math.min(minB, b);
                maxB = Math.max(maxB, b);
            }
        }

        boolean splittable() {
            return end - start > 1 && (maxR > minR || maxG > minG || maxB > minB);
        }
    }
}
