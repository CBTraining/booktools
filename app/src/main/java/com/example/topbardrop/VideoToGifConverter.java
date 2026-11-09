package com.example.topbardrop;

import android.content.Context;
import android.graphics.Bitmap;
import android.media.MediaMetadataRetriever;
import android.net.Uri;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;

import java.io.BufferedOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 100% Offline Video-to-GIF Converter with Start/End Trim and <50MB guarantee.
 * Uses MediaMetadataRetriever for scaled frame extraction, zero-allocation RGB555 Median-Cut
 * 256-color quantization, and a table-driven 12-bit GIF89a LZW encoder.
 */
public final class VideoToGifConverter {

    // Strict ceiling under 50 MB (48.0 MB safety cap)
    public static final long MAX_GIF_BYTES = 48L * 1024L * 1024L;

    private static final ExecutorService GIF_EXECUTOR = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "BookTools-VideoToGif");
        t.setDaemon(true);
        return t;
    });

    private static final ExecutorService PREVIEW_EXECUTOR = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "BookTools-GifPreview");
        t.setDaemon(true);
        return t;
    });

    // Latest-only coalesced preview state + cached retriever for smooth 60fps scrubbing
    private static final AtomicLong pendingPreviewTimeMs = new AtomicLong(-1L);
    private static final AtomicBoolean previewWorkerRunning = new AtomicBoolean(false);
    private static Uri cachedPreviewUri;
    private static MediaMetadataRetriever cachedPreviewRetriever;

    private VideoToGifConverter() {
    }

    public static final class GifResult {
        public final Uri savedUri;
        public final String savedFileName;
        public final long gifBytes;
        public final int width;
        public final int height;
        public final int frameCount;

        public GifResult(Uri savedUri, String savedFileName, long gifBytes, int width, int height, int frameCount) {
            this.savedUri = savedUri;
            this.savedFileName = savedFileName;
            this.gifBytes = gifBytes;
            this.width = width;
            this.height = height;
            this.frameCount = frameCount;
        }
    }

    public interface GifCallback {
        void onProgress(int percent, String statusText);
        void onSuccess(GifResult result);
        void onError(String errorMessage);
    }

    public interface FramePreviewCallback {
        void onFrameReady(Bitmap bitmap);
    }

    /**
     * Coalesces rapid scrub events so only the most recent timestamp is decoded, and reuses
     * a cached MediaMetadataRetriever for the active video Uri.
     */
    public static void loadPreviewFrameAsync(
            final Context context,
            final Uri videoUri,
            final long timeMs,
            final FramePreviewCallback callback
    ) {
        final Context appContext = context.getApplicationContext();
        final Handler mainHandler = new Handler(Looper.getMainLooper());
        pendingPreviewTimeMs.set(Math.max(0L, timeMs));

        if (!previewWorkerRunning.compareAndSet(false, true)) {
            return;
        }

        PREVIEW_EXECUTOR.execute(() -> {
            try {
                while (true) {
                    long targetMs = pendingPreviewTimeMs.getAndSet(-1L);
                    if (targetMs < 0L) {
                        break;
                    }
                    Bitmap bmp = decodePreviewFrameCached(appContext, videoUri, targetMs);
                    if (bmp != null) {
                        final Bitmap finalBmp = bmp;
                        mainHandler.post(() -> callback.onFrameReady(finalBmp));
                    }
                }
            } finally {
                previewWorkerRunning.set(false);
                // If another scrub arrived right as we finished, drain it
                if (pendingPreviewTimeMs.get() >= 0L) {
                    loadPreviewFrameAsync(appContext, videoUri, pendingPreviewTimeMs.get(), callback);
                }
            }
        });
    }

    private static synchronized Bitmap decodePreviewFrameCached(Context context, Uri videoUri, long timeMs) {
        try {
            if (cachedPreviewRetriever == null || cachedPreviewUri == null || !cachedPreviewUri.equals(videoUri)) {
                releasePreviewRetrieverInternal();
                cachedPreviewRetriever = new MediaMetadataRetriever();
                cachedPreviewRetriever.setDataSource(context, videoUri);
                cachedPreviewUri = videoUri;
            }
            long timeUs = Math.max(0L, timeMs * 1000L);
            Bitmap bmp = null;
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
                bmp = cachedPreviewRetriever.getScaledFrameAtTime(
                        timeUs,
                        MediaMetadataRetriever.OPTION_CLOSEST_SYNC,
                        480,
                        480
                );
            }
            if (bmp == null) {
                bmp = cachedPreviewRetriever.getFrameAtTime(timeUs, MediaMetadataRetriever.OPTION_CLOSEST_SYNC);
            }
            return bmp;
        } catch (Exception e) {
            releasePreviewRetrieverInternal();
            return null;
        }
    }

    public static void releasePreviewRetriever() {
        PREVIEW_EXECUTOR.execute(VideoToGifConverter::releasePreviewRetrieverInternal);
    }

    private static synchronized void releasePreviewRetrieverInternal() {
        if (cachedPreviewRetriever != null) {
            try {
                cachedPreviewRetriever.release();
            } catch (Exception ignored) {
            }
            cachedPreviewRetriever = null;
        }
        cachedPreviewUri = null;
    }

    public static void convertVideoRangeToGifAsync(
            final Context context,
            final Uri videoUri,
            final VideoCompressor.VideoInfo info,
            final long startMs,
            final long endMs,
            final AtomicBoolean cancelFlag,
            final GifCallback callback
    ) {
        final Context appContext = context.getApplicationContext();
        final Handler mainHandler = new Handler(Looper.getMainLooper());

        GIF_EXECUTOR.execute(() -> {
            File tempGif = new File(appContext.getCacheDir(), "booktools_gif_" + System.currentTimeMillis() + ".gif");
            MediaMetadataRetriever retriever = new MediaMetadataRetriever();
            try {
                long safeStartMs = Math.max(0L, Math.min(startMs, endMs - 100L));
                long safeEndMs = Math.max(safeStartMs + 100L, endMs);
                long clipDurationMs = safeEndMs - safeStartMs;
                double clipSec = Math.max(0.1, clipDurationMs / 1000.0);

                int fps;
                if (clipSec <= 10.0) {
                    fps = 12;
                } else if (clipSec <= 22.0) {
                    fps = 10;
                } else if (clipSec <= 40.0) {
                    fps = 8;
                } else {
                    fps = 6;
                }

                int totalFrames = Math.max(2, Math.min(260, (int) Math.round(clipSec * fps)));
                int frameDelayCs = Math.max(2, (int) Math.round((clipDurationMs / 10.0) / totalFrames));

                int maxDim;
                if (totalFrames <= 90) {
                    maxDim = 540;
                } else if (totalFrames <= 160) {
                    maxDim = 460;
                } else {
                    maxDim = 380;
                }

                int srcW = Math.max(120, info.width);
                int srcH = Math.max(120, info.height);
                if (info.rotation == 90 || info.rotation == 270) {
                    int tmp = srcW;
                    srcW = srcH;
                    srcH = tmp;
                }
                int longest = Math.max(srcW, srcH);
                double scale = (longest > maxDim) ? ((double) maxDim / (double) longest) : 1.0;
                int outW = Math.max(80, ((int) Math.round(srcW * scale) / 2) * 2);
                int outH = Math.max(80, ((int) Math.round(srcH * scale) / 2) * 2);

                retriever.setDataSource(appContext, videoUri);

                int actualWidth = outW;
                int actualHeight = outH;
                int encodedFrames = 0;

                try (FileOutputStream fos = new FileOutputStream(tempGif);
                     BufferedOutputStream bos = new BufferedOutputStream(fos, 64 * 1024)) {

                    Gif89aWriter gifWriter = null;
                    int[] pixelBuffer = null;

                    for (int i = 0; i < totalFrames && !cancelFlag.get(); i++) {
                        if (fos.getChannel().position() >= MAX_GIF_BYTES - (512 * 1024)) {
                            break;
                        }

                        double frac = (totalFrames == 1) ? 0.0 : ((double) i / (double) (totalFrames - 1));
                        long frameTimeMs = safeStartMs + Math.round(frac * clipDurationMs);
                        long frameTimeUs = frameTimeMs * 1000L;

                        Bitmap frameBmp = null;
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
                            frameBmp = retriever.getScaledFrameAtTime(
                                    frameTimeUs,
                                    MediaMetadataRetriever.OPTION_CLOSEST,
                                    outW,
                                    outH
                            );
                        }
                        if (frameBmp == null) {
                            Bitmap raw = retriever.getFrameAtTime(frameTimeUs, MediaMetadataRetriever.OPTION_CLOSEST_SYNC);
                            if (raw != null) {
                                frameBmp = Bitmap.createScaledBitmap(raw, outW, outH, true);
                                if (frameBmp != raw) {
                                    raw.recycle();
                                }
                            }
                        }
                        if (frameBmp == null) {
                            continue;
                        }

                        if (gifWriter == null) {
                            actualWidth = frameBmp.getWidth();
                            actualHeight = frameBmp.getHeight();
                            pixelBuffer = new int[actualWidth * actualHeight];
                            gifWriter = new Gif89aWriter(bos, actualWidth, actualHeight);
                            gifWriter.writeHeader();
                        } else if (frameBmp.getWidth() != actualWidth || frameBmp.getHeight() != actualHeight) {
                            Bitmap resized = Bitmap.createScaledBitmap(frameBmp, actualWidth, actualHeight, true);
                            if (resized != frameBmp) {
                                frameBmp.recycle();
                            }
                            frameBmp = resized;
                        }

                        frameBmp.getPixels(pixelBuffer, 0, actualWidth, 0, 0, actualWidth, actualHeight);
                        frameBmp.recycle();

                        gifWriter.writeFrame(pixelBuffer, frameDelayCs);
                        encodedFrames++;

                        final int pct = Math.min(97, ((i + 1) * 97) / totalFrames);
                        final int curFrame = i + 1;
                        final int tot = totalFrames;
                        mainHandler.post(() -> callback.onProgress(
                                pct,
                                "Encoding GIF frame " + curFrame + " / " + tot + " (" + pct + "%)..."
                        ));
                    }

                    if (gifWriter != null) {
                        gifWriter.writeTrailer();
                    }
                    bos.flush();
                }

                if (cancelFlag.get()) {
                    mainHandler.post(() -> callback.onError("Cancelled."));
                    return;
                }

                long gifBytes = tempGif.length();
                if (encodedFrames == 0 || gifBytes <= 0L) {
                    throw new IOException("Could not decode frames from selected video range.");
                }

                mainHandler.post(() -> callback.onProgress(99, "Saving GIF to Downloads..."));

                String baseName = MediaToolUtils.stripExtension(info.displayName, "clip");
                String outFileName = baseName + ".gif";
                Uri savedUri = MediaToolUtils.saveFileToDownloads(appContext, tempGif, outFileName, "image/gif");
                if (savedUri == null) {
                    throw new IOException("Could not save GIF to Downloads.");
                }

                final GifResult result = new GifResult(
                        savedUri,
                        outFileName,
                        gifBytes,
                        actualWidth,
                        actualHeight,
                        encodedFrames
                );
                mainHandler.post(() -> callback.onSuccess(result));
            } catch (Exception e) {
                final String msg = e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName();
                mainHandler.post(() -> callback.onError(msg));
            } finally {
                try {
                    retriever.release();
                } catch (Exception ignored) {
                }
                if (tempGif.exists()) {
                    //noinspection ResultOfMethodCallIgnored
                    tempGif.delete();
                }
            }
        });
    }

    /**
     * Self-contained, zero-allocation GIF89a animated encoder with RGB555 Median-Cut
     * 256-color quantization and table-driven 12-bit LZW compression.
     */
    private static final class Gif89aWriter {
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
}
