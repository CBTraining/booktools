package com.example.topbardrop;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Self-contained, zero-allocation GIF89a animated encoder with RGB555 Median-Cut
 * 256-color quantization and table-driven 12-bit LZW compression.
 */
final class Gif89aWriter {

    private final OutputStream out;
    private final int width;
    private final int height;
    private final byte[] indexedPixels;
    private final byte[] paletteRgb = new byte[256 * 3];
    private final int[] histogram = new int[32768];
    private final byte[] rgb555ToPalette = new byte[32768];
    // Pre-allocated counting-sort buffers so Median-Cut splits perform 0 heap allocations
    private final int[] sortCounts = new int[32];
    private final int[] sortOffsets = new int[32];
    private final int[] sortScratch = new int[32768];
    private final int[] lzwTable = new int[4096 * 256];
    private final byte[] subBlock = new byte[256];

    Gif89aWriter(OutputStream out, int width, int height) {
        this.out = out;
        this.width = width;
        this.height = height;
        this.indexedPixels = new byte[width * height];
    }

    void writeHeader() throws IOException {
        out.write("GIF89a".getBytes(StandardCharsets.US_ASCII));
        writeShortLE(width);
        writeShortLE(height);
        out.write(0x70);
        out.write(0);
        out.write(0);

        // Netscape 2.0 Application Extension for infinite looping
        out.write(0x21);
        out.write(0xFF);
        out.write(11);
        out.write("NETSCAPE2.0".getBytes(StandardCharsets.US_ASCII));
        out.write(3);
        out.write(1);
        writeShortLE(0);
        out.write(0);
    }

    void writeFrame(int[] argbPixels, int delayCentiseconds) throws IOException {
        quantizeFrameMedianCut(argbPixels);

        // 1. Graphic Control Extension
        out.write(0x21);
        out.write(0xF9);
        out.write(4);
        out.write(0x04);
        writeShortLE(delayCentiseconds);
        out.write(0);
        out.write(0);

        // 2. Image Descriptor with 256-color Local Color Table
        out.write(0x2C);
        writeShortLE(0);
        writeShortLE(0);
        writeShortLE(width);
        writeShortLE(height);
        out.write(0x87);

        // 3. 256-color Local Color Table (768 bytes)
        out.write(paletteRgb, 0, 768);

        // 4. LZW Image Data
        encodeLzw(indexedPixels);
    }

    void writeTrailer() throws IOException {
        out.write(0x3B);
    }

    private void quantizeFrameMedianCut(int[] argbPixels) {
        Arrays.fill(histogram, 0);
        final int len = argbPixels.length;

        for (int i = 0; i < len; i++) {
            int c = argbPixels[i];
            int r5 = (c >> 19) & 0x1F;
            int g5 = (c >> 11) & 0x1F;
            int b5 = (c >> 3) & 0x1F;
            histogram[(r5 << 10) | (g5 << 5) | b5]++;
        }

        int distinctCount = 0;
        for (int i = 0; i < 32768; i++) {
            if (histogram[i] > 0) {
                distinctCount++;
            }
        }

        int[] activeColors = new int[distinctCount];
        int idx = 0;
        for (int i = 0; i < 32768; i++) {
            if (histogram[i] > 0) {
                activeColors[idx++] = i;
            }
        }

        List<ColorBox> boxes = new ArrayList<>(256);
        if (distinctCount > 0) {
            boxes.add(createBox(activeColors, 0, distinctCount));
        }

        while (boxes.size() < 256) {
            int bestIdx = -1;
            long bestScore = -1L;
            for (int i = 0; i < boxes.size(); i++) {
                ColorBox b = boxes.get(i);
                if (b.end - b.start > 1) {
                    int rSpan = b.rMax - b.rMin;
                    int gSpan = b.gMax - b.gMin;
                    int bSpan = b.bMax - b.bMin;
                    int maxSpan = Math.max(rSpan, Math.max(gSpan, bSpan));
                    if (maxSpan > 0) {
                        long score = (long) b.pixelCount * (long) maxSpan;
                        if (score > bestScore) {
                            bestScore = score;
                            bestIdx = i;
                        }
                    }
                }
            }
            if (bestIdx < 0) {
                break;
            }

            ColorBox target = boxes.remove(bestIdx);
            int rSpan = target.rMax - target.rMin;
            int gSpan = target.gMax - target.gMin;
            int bSpan = target.bMax - target.bMin;
            final int axis = (gSpan >= rSpan && gSpan >= bSpan) ? 1 : ((rSpan >= bSpan) ? 0 : 2);

            sortColorSlice(activeColors, target.start, target.end, axis);

            int halfPixels = target.pixelCount / 2;
            int acc = 0;
            int split = target.start + 1;
            for (int i = target.start; i < target.end - 1; i++) {
                acc += histogram[activeColors[i]];
                if (acc >= halfPixels) {
                    split = i + 1;
                    break;
                }
            }

            boxes.add(createBox(activeColors, target.start, split));
            boxes.add(createBox(activeColors, split, target.end));
        }

        Arrays.fill(paletteRgb, (byte) 0);
        for (int p = 0; p < boxes.size(); p++) {
            ColorBox b = boxes.get(p);
            long rSum = 0;
            long gSum = 0;
            long bSum = 0;
            long weightSum = 0;
            for (int i = b.start; i < b.end; i++) {
                int c555 = activeColors[i];
                int w = histogram[c555];
                int r5 = (c555 >> 10) & 0x1F;
                int g5 = (c555 >> 5) & 0x1F;
                int b5 = c555 & 0x1F;
                rSum += (long) ((r5 << 3) | (r5 >> 2)) * w;
                gSum += (long) ((g5 << 3) | (g5 >> 2)) * w;
                bSum += (long) ((b5 << 3) | (b5 >> 2)) * w;
                weightSum += w;
                rgb555ToPalette[c555] = (byte) p;
            }
            if (weightSum > 0) {
                paletteRgb[p * 3] = (byte) (rSum / weightSum);
                paletteRgb[p * 3 + 1] = (byte) (gSum / weightSum);
                paletteRgb[p * 3 + 2] = (byte) (bSum / weightSum);
            }
        }

        for (int i = 0; i < len; i++) {
            int c = argbPixels[i];
            int r5 = (c >> 19) & 0x1F;
            int g5 = (c >> 11) & 0x1F;
            int b5 = (c >> 3) & 0x1F;
            indexedPixels[i] = rgb555ToPalette[(r5 << 10) | (g5 << 5) | b5];
        }
    }

    private ColorBox createBox(int[] colors, int start, int end) {
        int rMin = 31, rMax = 0;
        int gMin = 31, gMax = 0;
        int bMin = 31, bMax = 0;
        int count = 0;
        for (int i = start; i < end; i++) {
            int c = colors[i];
            int r = (c >> 10) & 0x1F;
            int g = (c >> 5) & 0x1F;
            int b = c & 0x1F;
            if (r < rMin) rMin = r;
            if (r > rMax) rMax = r;
            if (g < gMin) gMin = g;
            if (g > gMax) gMax = g;
            if (b < bMin) bMin = b;
            if (b > bMax) bMax = b;
            count += histogram[c];
        }
        return new ColorBox(start, end, count, rMin, rMax, gMin, gMax, bMin, bMax);
    }

    private void sortColorSlice(int[] colors, int start, int end, int axis) {
        Arrays.fill(sortCounts, 0);
        int shift = (axis == 0) ? 10 : ((axis == 1) ? 5 : 0);
        for (int i = start; i < end; i++) {
            sortCounts[(colors[i] >> shift) & 0x1F]++;
        }
        int sum = start;
        for (int k = 0; k < 32; k++) {
            sortOffsets[k] = sum;
            sum += sortCounts[k];
        }
        int sliceLen = end - start;
        System.arraycopy(colors, start, sortScratch, 0, sliceLen);
        for (int i = 0; i < sliceLen; i++) {
            int c = sortScratch[i];
            int key = (c >> shift) & 0x1F;
            colors[sortOffsets[key]++] = c;
        }
    }

    private void encodeLzw(byte[] pixels) throws IOException {
        final int minCodeSize = 8;
        out.write(minCodeSize);

        final int clearCode = 1 << minCodeSize;
        final int endOfInfoCode = clearCode + 1;

        int codeSize = minCodeSize + 1;
        int maxCodeForSize = (1 << codeSize) - 1;
        int nextAvailableCode = clearCode + 2;

        Arrays.fill(lzwTable, -1);

        int bitAccumulator = 0;
        int bitCount = 0;
        int subBlockLen = 0;

        bitAccumulator |= (clearCode << bitCount);
        bitCount += codeSize;
        while (bitCount >= 8) {
            subBlock[subBlockLen++] = (byte) (bitAccumulator & 0xFF);
            if (subBlockLen == 255) {
                out.write(255);
                out.write(subBlock, 0, 255);
                subBlockLen = 0;
            }
            bitAccumulator >>>= 8;
            bitCount -= 8;
        }

        int prefix = pixels[0] & 0xFF;
        for (int i = 1; i < pixels.length; i++) {
            int k = pixels[i] & 0xFF;
            int tableIdx = (prefix << 8) | k;
            int existingCode = lzwTable[tableIdx];
            if (existingCode != -1) {
                prefix = existingCode;
            } else {
                bitAccumulator |= (prefix << bitCount);
                bitCount += codeSize;
                while (bitCount >= 8) {
                    subBlock[subBlockLen++] = (byte) (bitAccumulator & 0xFF);
                    if (subBlockLen == 255) {
                        out.write(255);
                        out.write(subBlock, 0, 255);
                        subBlockLen = 0;
                    }
                    bitAccumulator >>>= 8;
                    bitCount -= 8;
                }

                if (nextAvailableCode < 4096) {
                    lzwTable[tableIdx] = nextAvailableCode++;
                    if (nextAvailableCode > maxCodeForSize && codeSize < 12) {
                        codeSize++;
                        maxCodeForSize = (1 << codeSize) - 1;
                    }
                } else {
                    bitAccumulator |= (clearCode << bitCount);
                    bitCount += codeSize;
                    while (bitCount >= 8) {
                        subBlock[subBlockLen++] = (byte) (bitAccumulator & 0xFF);
                        if (subBlockLen == 255) {
                            out.write(255);
                            out.write(subBlock, 0, 255);
                            subBlockLen = 0;
                        }
                        bitAccumulator >>>= 8;
                        bitCount -= 8;
                    }
                    Arrays.fill(lzwTable, -1);
                    codeSize = minCodeSize + 1;
                    maxCodeForSize = (1 << codeSize) - 1;
                    nextAvailableCode = clearCode + 2;
                }
                prefix = k;
            }
        }

        bitAccumulator |= (prefix << bitCount);
        bitCount += codeSize;
        while (bitCount >= 8) {
            subBlock[subBlockLen++] = (byte) (bitAccumulator & 0xFF);
            if (subBlockLen == 255) {
                out.write(255);
                out.write(subBlock, 0, 255);
                subBlockLen = 0;
            }
            bitAccumulator >>>= 8;
            bitCount -= 8;
        }

        bitAccumulator |= (endOfInfoCode << bitCount);
        bitCount += codeSize;
        while (bitCount > 0) {
            subBlock[subBlockLen++] = (byte) (bitAccumulator & 0xFF);
            if (subBlockLen == 255) {
                out.write(255);
                out.write(subBlock, 0, 255);
                subBlockLen = 0;
            }
            bitAccumulator >>>= 8;
            bitCount -= 8;
        }

        if (subBlockLen > 0) {
            out.write(subBlockLen);
            out.write(subBlock, 0, subBlockLen);
        }
        out.write(0);
    }

    private void writeShortLE(int val) throws IOException {
        out.write(val & 0xFF);
        out.write((val >> 8) & 0xFF);
    }

    private static final class ColorBox {
        final int start;
        final int end;
        final int pixelCount;
        final int rMin, rMax;
        final int gMin, gMax;
        final int bMin, bMax;

        ColorBox(int start, int end, int pixelCount, int rMin, int rMax, int gMin, int gMax, int bMin, int bMax) {
            this.start = start;
            this.end = end;
            this.pixelCount = pixelCount;
            this.rMin = rMin;
            this.rMax = rMax;
            this.gMin = gMin;
            this.gMax = gMax;
            this.bMin = bMin;
            this.bMax = bMax;
        }
    }
}
