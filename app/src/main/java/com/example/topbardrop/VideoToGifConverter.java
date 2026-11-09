package com.example.topbardrop;

import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.Context;
import android.graphics.Bitmap;
import android.media.MediaMetadataRetriever;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.provider.MediaStore;

import java.io.BufferedOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 100% Offline Video-to-GIF Converter with Start/End Trim and <50MB guarantee.
 * Uses MediaMetadataRetriever for scaled frame extraction, fast RGB555 Median-Cut 256-color
 * quantization, and a table-driven 12-bit GIF89a LZW encoder.
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
     * Loads a scaled preview frame asynchronously for the Start/End trim scrubber.
     */
    public static void loadPreviewFrameAsync(
            final Context context,
            final Uri videoUri,
            final long timeMs,
            final FramePreviewCallback callback
    ) {
        final Context appContext = context.getApplicationContext();
        final Handler mainHandler = new Handler(Looper.getMainLooper());
        PREVIEW_EXECUTOR.execute(() -> {
            MediaMetadataRetriever retriever = new MediaMetadataRetriever();
            Bitmap bmp = null;
            try {
                retriever.setDataSource(appContext, videoUri);
                long timeUs = Math.max(0L, timeMs * 1000L);
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
                    bmp = retriever.getScaledFrameAtTime(
                            timeUs,
                            MediaMetadataRetriever.OPTION_CLOSEST_SYNC,
                            480,
                            480
                    );
                }
                if (bmp == null) {
                    bmp = retriever.getFrameAtTime(timeUs, MediaMetadataRetriever.OPTION_CLOSEST_SYNC);
                }
            } catch (Exception ignored) {
            } finally {
                try {
                    retriever.release();
                } catch (Exception ignored) {
                }
            }
            final Bitmap finalBmp = bmp;
            mainHandler.post(() -> callback.onFrameReady(finalBmp));
        });
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

                // Choose frame rate and max resolution so the GIF is smooth and stays well under 50MB
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

                // Pick output dimensions based on total frame count so total size stays well under 50MB
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
                        // Stop adding frames early if we ever approach our 48MB ceiling so file stays <50MB
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

                String baseName = stripExtension(info.displayName);
                String outFileName = baseName + ".gif";
                Uri savedUri = saveGifToDownloads(appContext, tempGif, outFileName);
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

    private static String stripExtension(String fileName) {
        if (fileName == null || fileName.trim().isEmpty()) {
            return "clip";
        }
        String clean = fileName.trim().replaceAll("[^a-zA-Z0-9._-]", "_");
        int dot = clean.lastIndexOf('.');
        if (dot > 0) {
            clean = clean.substring(0, dot);
        }
        return clean.isEmpty() ? "clip" : clean;
    }

    private static Uri saveGifToDownloads(Context context, File tempFile, String outFileName) throws IOException {
        ContentResolver resolver = context.getContentResolver();
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ContentValues values = new ContentValues();
            values.put(MediaStore.Downloads.DISPLAY_NAME, outFileName);
            values.put(MediaStore.Downloads.MIME_TYPE, "image/gif");
            values.put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS);
            values.put(MediaStore.Downloads.IS_PENDING, 1);

            Uri uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values);
            if (uri == null) {
                throw new IOException("Failed to create MediaStore entry in Downloads.");
            }
            try (InputStream in = new FileInputStream(tempFile);
                 OutputStream out = resolver.openOutputStream(uri)) {
                if (out == null) {
                    throw new IOException("Failed to open Downloads output stream.");
                }
                byte[] buf = new byte[64 * 1024];
                int n;
                while ((n = in.read(buf)) != -1) {
                    out.write(buf, 0, n);
                }
                out.flush();
            }
            ContentValues done = new ContentValues();
            done.put(MediaStore.Downloads.IS_PENDING, 0);
            resolver.update(uri, done, null, null);
            return uri;
        } else {
            File downloadsDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS);
            if (!downloadsDir.exists()) {
                //noinspection ResultOfMethodCallIgnored
                downloadsDir.mkdirs();
            }
            File target = new File(downloadsDir, outFileName);
            try (InputStream in = new FileInputStream(tempFile);
                 OutputStream out = new FileOutputStream(target)) {
                byte[] buf = new byte[64 * 1024];
                int n;
                while ((n = in.read(buf)) != -1) {
                    out.write(buf, 0, n);
                }
                out.flush();
            }
            return Uri.fromFile(target);
        }
    }

    /**
     * Self-contained, zero-dependency GIF89a animated encoder with RGB555 Median-Cut
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
        // Flat LZW transition table: [prefixCode * 256 + nextByte] -> code (-1 if absent)
        private final int[] lzwTable = new int[4096 * 256];
        private final byte[] subBlock = new byte[256];

        Gif89aWriter(OutputStream out, int width, int height) {
            this.out = out;
            this.width = width;
            this.height = height;
            this.indexedPixels = new byte[width * height];
        }

        void writeHeader() throws IOException {
            // GIF89a signature
            out.write("GIF89a".getBytes(StandardCharsets.US_ASCII));
            // Logical Screen Descriptor (no global color table; each frame has a tailored 256-color local palette)
            writeShortLE(width);
            writeShortLE(height);
            out.write(0x70); // GCT=0, color resolution=7 (8 bits), sort=0, size=0
            out.write(0);    // Background color index
            out.write(0);    // Pixel aspect ratio

            // Netscape 2.0 Application Extension for infinite looping
            out.write(0x21);
            out.write(0xFF);
            out.write(11);
            out.write("NETSCAPE2.0".getBytes(StandardCharsets.US_ASCII));
            out.write(3);
            out.write(1);
            writeShortLE(0); // Loop count = 0 (infinite)
            out.write(0);
        }

        void writeFrame(int[] argbPixels, int delayCentiseconds) throws IOException {
            quantizeFrameMedianCut(argbPixels);

            // 1. Graphic Control Extension
            out.write(0x21);
            out.write(0xF9);
            out.write(4);
            out.write(0x04); // Disposal method = 1 (do not dispose), no transparency
            writeShortLE(delayCentiseconds);
            out.write(0);    // Transparent color index (unused)
            out.write(0);    // Block terminator

            // 2. Image Descriptor with 256-color Local Color Table
            out.write(0x2C);
            writeShortLE(0); // Left
            writeShortLE(0); // Top
            writeShortLE(width);
            writeShortLE(height);
            out.write(0x87); // Local Color Table flag (0x80) | size=7 (2^(7+1) = 256 colors)

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
                // Find the splittable box with the largest pixel count * volume score
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

        private static void sortColorSlice(int[] colors, int start, int end, int axis) {
            // Counting sort on 5-bit channel value [0..31] -> O(N) instead of O(N log N)
            int[] counts = new int[32];
            int shift = (axis == 0) ? 10 : ((axis == 1) ? 5 : 0);
            for (int i = start; i < end; i++) {
                counts[(colors[i] >> shift) & 0x1F]++;
            }
            int[] offsets = new int[32];
            int sum = start;
            for (int k = 0; k < 32; k++) {
                offsets[k] = sum;
                sum += counts[k];
            }
            int[] temp = Arrays.copyOfRange(colors, start, end);
            for (int c : temp) {
                int key = (c >> shift) & 0x1F;
                colors[offsets[key]++] = c;
            }
        }

        private void encodeLzw(byte[] pixels) throws IOException {
            final int minCodeSize = 8;
            out.write(minCodeSize);

            final int clearCode = 1 << minCodeSize; // 256
            final int endOfInfoCode = clearCode + 1; // 257

            int codeSize = minCodeSize + 1; // 9
            int maxCodeForSize = (1 << codeSize) - 1;
            int nextAvailableCode = clearCode + 2; // 258

            Arrays.fill(lzwTable, -1);

            int bitAccumulator = 0;
            int bitCount = 0;
            int subBlockLen = 0;

            // Emit initial Clear Code
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
                    // Emit current prefix code
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
                        // Table full: emit Clear Code and reset LZW dictionary
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

            // Emit final prefix code + End-Of-Information code
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
            out.write(0); // Block terminator
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
