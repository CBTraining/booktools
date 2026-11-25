package com.cbtraining.booktools;

import android.content.ContentResolver;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.ImageDecoder;
import android.graphics.Paint;
import android.graphics.Rect;
import android.net.Uri;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 100% Offline Image Compressor and Multi-Core 2x Detail-Enhancing Upscaler.
 * - Compress Image: Optimizes dimensions & quality (JPEG / WebP) to dramatically reduce file size.
 * - Upscale Image (2x HD): Performs 2x high-precision resampling + Memory-Efficient Banded Multi-Core
 *   Luminance Contrast-Adaptive Sharpening (CAS) and streams the lossless PNG directly to Downloads.
 */
public final class ImageProcessor {

    private static final int MAX_UPSCALE_DIMENSION = 4096;
    private static final int CAS_BAND_ROWS = 256;

    private static final ExecutorService IMAGE_EXECUTOR = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "BookTools-ImageProcessor");
        t.setDaemon(true);
        return t;
    });

    private static final int CPU_CORES = Math.max(2, Math.min(8, Runtime.getRuntime().availableProcessors()));
    private static final ExecutorService PARALLEL_FILTER_POOL = Executors.newFixedThreadPool(CPU_CORES, r -> {
        Thread t = new Thread(r, "BookTools-CASWorker");
        t.setDaemon(true);
        return t;
    });

    private ImageProcessor() {
    }

    public static final class ImageResult {
        public final Uri savedUri;
        public final String savedFileName;
        public final long originalBytes;
        public final long outputBytes;
        public final int srcWidth;
        public final int srcHeight;
        public final int outWidth;
        public final int outHeight;

        public ImageResult(
                Uri savedUri,
                String savedFileName,
                long originalBytes,
                long outputBytes,
                int srcWidth,
                int srcHeight,
                int outWidth,
                int outHeight
        ) {
            this.savedUri = savedUri;
            this.savedFileName = savedFileName;
            this.originalBytes = originalBytes;
            this.outputBytes = outputBytes;
            this.srcWidth = srcWidth;
            this.srcHeight = srcHeight;
            this.outWidth = outWidth;
            this.outHeight = outHeight;
        }
    }

    public interface ProcessCallback {
        void onFileInspected(String displayName, long originalBytes, int width, int height);
        void onProgress(int percent, String statusText);
        void onSuccess(ImageResult result);
        void onError(String errorMessage);
    }

    public static void compressImageAsync(
            final Context context,
            final Uri sourceUri,
            final AtomicBoolean cancelFlag,
            final ProcessCallback callback
    ) {
        final Context appContext = context.getApplicationContext();
        final Handler mainHandler = new Handler(Looper.getMainLooper());

        IMAGE_EXECUTOR.execute(() -> {
            Bitmap srcBitmap = null;
            Bitmap scaledBitmap = null;
            try {
                MediaToolUtils.FileMeta meta = MediaToolUtils.queryFileMeta(appContext, sourceUri, "image.png");
                srcBitmap = decodeBitmapFromUri(appContext, sourceUri, 4096);
                if (srcBitmap == null) {
                    throw new IOException("Could not decode the selected image.");
                }

                final int srcW = srcBitmap.getWidth();
                final int srcH = srcBitmap.getHeight();
                mainHandler.post(() -> callback.onFileInspected(meta.displayName, meta.sizeBytes, srcW, srcH));
                mainHandler.post(() -> callback.onProgress(25, "Optimizing and compressing image offline..."));

                if (cancelFlag.get()) {
                    return;
                }

                int maxDim = 2400;
                int longest = Math.max(srcW, srcH);
                int outW = srcW;
                int outH = srcH;
                if (longest > maxDim) {
                    double scale = (double) maxDim / (double) longest;
                    outW = Math.max(1, (int) Math.round(srcW * scale));
                    outH = Math.max(1, (int) Math.round(srcH * scale));
                    scaledBitmap = createSmoothScaledBitmap(srcBitmap, outW, outH);
                } else {
                    scaledBitmap = srcBitmap;
                }

                boolean hasAlpha = hasNonOpaquePixels(scaledBitmap);
                String baseName = MediaToolUtils.stripExtension(meta.displayName, "image");
                String outFileName;
                String outMime;
                byte[] compressedData;

                if (hasAlpha) {
                    outFileName = baseName + "_compressed.webp";
                    outMime = "image/webp";
                    compressedData = compressToBytes(scaledBitmap, true, 80);
                } else {
                    outFileName = baseName + "_compressed.jpg";
                    outMime = "image/jpeg";
                    compressedData = compressToBytes(scaledBitmap, false, 78);

                    if (meta.sizeBytes > 0 && compressedData.length >= meta.sizeBytes * 0.90) {
                        compressedData = compressToBytes(scaledBitmap, false, 62);
                    }
                    if (meta.sizeBytes > 0 && compressedData.length >= meta.sizeBytes * 0.92) {
                        int smallerW = Math.max(1, (int) Math.round(outW * 0.80));
                        int smallerH = Math.max(1, (int) Math.round(outH * 0.80));
                        Bitmap smallerBmp = createSmoothScaledBitmap(scaledBitmap, smallerW, smallerH);
                        try {
                            compressedData = compressToBytes(smallerBmp, false, 58);
                            outW = smallerW;
                            outH = smallerH;
                        } finally {
                            if (smallerBmp != scaledBitmap && smallerBmp != srcBitmap && !smallerBmp.isRecycled()) {
                                smallerBmp.recycle();
                            }
                        }
                    }
                }

                if (cancelFlag.get()) {
                    return;
                }

                mainHandler.post(() -> callback.onProgress(85, "Saving compressed image to Downloads..."));

                Uri savedUri = MediaToolUtils.saveBytesToDownloads(appContext, compressedData, outFileName, outMime);
                if (savedUri == null) {
                    throw new IOException("Could not save compressed image to Downloads.");
                }

                final ImageResult result = new ImageResult(
                        savedUri,
                        outFileName,
                        meta.sizeBytes,
                        compressedData.length,
                        srcW,
                        srcH,
                        outW,
                        outH
                );
                mainHandler.post(() -> callback.onSuccess(result));
            } catch (Throwable e) {
                final String msg = (e instanceof OutOfMemoryError)
                        ? "Image is too large for available memory."
                        : (e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName());
                mainHandler.post(() -> callback.onError(msg));
            } finally {
                if (scaledBitmap != null && scaledBitmap != srcBitmap && !scaledBitmap.isRecycled()) {
                    scaledBitmap.recycle();
                }
                if (srcBitmap != null && !srcBitmap.isRecycled()) {
                    srcBitmap.recycle();
                }
            }
        });
    }

    public static void upscaleImage2xAsync(
            final Context context,
            final Uri sourceUri,
            final AtomicBoolean cancelFlag,
            final ProcessCallback callback
    ) {
        final Context appContext = context.getApplicationContext();
        final Handler mainHandler = new Handler(Looper.getMainLooper());

        IMAGE_EXECUTOR.execute(() -> {
            Bitmap srcBitmap = null;
            Bitmap upscaledBitmap = null;
            try {
                MediaToolUtils.FileMeta meta = MediaToolUtils.queryFileMeta(appContext, sourceUri, "image.png");
                srcBitmap = decodeBitmapFromUri(appContext, sourceUri, 2560);
                if (srcBitmap == null) {
                    throw new IOException("Could not decode the selected image.");
                }

                final int srcW = srcBitmap.getWidth();
                final int srcH = srcBitmap.getHeight();

                double scale = 2.0;
                int longest = Math.max(srcW, srcH);
                if (longest * 2 > MAX_UPSCALE_DIMENSION) {
                    scale = Math.max(1.25, (double) MAX_UPSCALE_DIMENSION / (double) longest);
                }
                final int outW = Math.max(srcW, (int) Math.round(srcW * scale));
                final int outH = Math.max(srcH, (int) Math.round(srcH * scale));

                mainHandler.post(() -> callback.onFileInspected(meta.displayName, meta.sizeBytes, srcW, srcH));
                mainHandler.post(() -> callback.onProgress(15, "Resampling to " + outW + "×" + outH + " (2x HD)..."));

                if (cancelFlag.get()) {
                    return;
                }

                upscaledBitmap = createSmoothScaledBitmap(srcBitmap, outW, outH);
                srcBitmap.recycle();
                srcBitmap = null;

                if (cancelFlag.get()) {
                    return;
                }

                // Memory-efficient banded multi-core Contrast-Adaptive Luminance Sharpening (CAS)
                applyContrastAdaptiveSharpeningParallel(upscaledBitmap, cancelFlag, pct -> {
                    int totalPct = 20 + (pct * 65) / 100;
                    mainHandler.post(() -> callback.onProgress(
                            totalPct,
                            "Enhancing edges & fine detail (" + CPU_CORES + " threads)... " + totalPct + "%"
                    ));
                });

                if (cancelFlag.get()) {
                    return;
                }

                mainHandler.post(() -> callback.onProgress(90, "Streaming lossless 2x PNG to Downloads..."));

                String baseName = MediaToolUtils.stripExtension(meta.displayName, "image");
                String outFileName = baseName + "_upscaled_2x.png";

                final Bitmap finalUpscaled = upscaledBitmap;
                MediaToolUtils.StreamSaveResult saved = MediaToolUtils.saveStreamToDownloads(
                        appContext,
                        outFileName,
                        "image/png",
                        out -> finalUpscaled.compress(Bitmap.CompressFormat.PNG, 100, out)
                );

                final ImageResult result = new ImageResult(
                        saved.savedUri,
                        outFileName,
                        meta.sizeBytes,
                        saved.bytesWritten,
                        srcW,
                        srcH,
                        outW,
                        outH
                );
                mainHandler.post(() -> callback.onSuccess(result));
            } catch (Throwable e) {
                final String msg = (e instanceof OutOfMemoryError)
                        ? "Image is too large for available memory."
                        : (e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName());
                mainHandler.post(() -> callback.onError(msg));
            } finally {
                if (upscaledBitmap != null && !upscaledBitmap.isRecycled()) {
                    upscaledBitmap.recycle();
                }
                if (srcBitmap != null && !srcBitmap.isRecycled()) {
                    srcBitmap.recycle();
                }
            }
        });
    }

    private interface IntProgress {
        void onStep(int percent);
    }

    /**
     * Memory-efficient banded multi-core Contrast-Adaptive Sharpening (CAS) operating on luminance
     * in 256-row strips with 1-row overlap. Reduces peak Java heap by ~95% on 4K images (~9 MB vs
     * ~192 MB) while preserving pixel-exact output and improving CPU cache locality.
     */
    private static void applyContrastAdaptiveSharpeningParallel(
            Bitmap bitmap,
            final AtomicBoolean cancelFlag,
            final IntProgress progress
    ) throws Exception {
        final int w = bitmap.getWidth();
        final int h = bitmap.getHeight();
        if (w < 3 || h < 3) return;

        final int innerRowsTotal = h - 2;
        final int maxBandRows = Math.min(CAS_BAND_ROWS, innerRowsTotal);
        final int maxReadRows = maxBandRows + 2; // 1-row top + 1-row bottom halo

        final int[] bandSrc = new int[w * maxReadRows];
        final int[] bandDst = new int[w * maxBandRows];
        final int[] prevOrigBottomRow = new int[w];
        final byte[] bandLuma = new byte[w * maxReadRows];

        final AtomicInteger completedRows = new AtomicInteger(0);
        final AtomicInteger lastReportedStep = new AtomicInteger(-1);

        for (int bandStartY = 1; bandStartY < h - 1 && !cancelFlag.get(); bandStartY += maxBandRows) {
            final int bandInnerRows = Math.min(maxBandRows, (h - 1) - bandStartY);
            final int readRows = bandInnerRows + 2;
            final int readPixels = w * readRows;

            if (bandStartY == 1) {
                bitmap.getPixels(bandSrc, 0, w, 0, 0, w, readRows);
            } else {
                // Reuse the unsharpened last row of the previous band as top halo for bit-exact seam parity
                System.arraycopy(prevOrigBottomRow, 0, bandSrc, 0, w);
                bitmap.getPixels(bandSrc, w, w, 0, bandStartY, w, bandInnerRows + 1);
            }
            // Save this band's last unsharpened interior row before writing sharpened pixels back to bitmap
            System.arraycopy(bandSrc, bandInnerRows * w, prevOrigBottomRow, 0, w);
            // Copy interior rows (offset +w) to dst so low-contrast & edge pixels remain intact
            System.arraycopy(bandSrc, w, bandDst, 0, w * bandInnerRows);

            final int numThreads = Math.min(CPU_CORES, Math.max(1, bandInnerRows / 16));

            // 1. Parallel Luminance Precomputation for current band
            if (numThreads <= 1) {
                for (int i = 0; i < readPixels; i++) {
                    int c = bandSrc[i];
                    int r = (c >> 16) & 0xFF;
                    int g = (c >> 8) & 0xFF;
                    int b = c & 0xFF;
                    bandLuma[i] = (byte) ((r * 77 + g * 150 + b * 29) >> 8);
                }
            } else {
                List<Callable<Void>> lumaTasks = new ArrayList<>(numThreads);
                final int chunkPixels = (readPixels + numThreads - 1) / numThreads;
                for (int t = 0; t < numThreads; t++) {
                    final int startIdx = t * chunkPixels;
                    final int endIdx = Math.min(readPixels, startIdx + chunkPixels);
                    if (startIdx >= endIdx) continue;
                    lumaTasks.add(() -> {
                        for (int i = startIdx; i < endIdx && !cancelFlag.get(); i++) {
                            int c = bandSrc[i];
                            int r = (c >> 16) & 0xFF;
                            int g = (c >> 8) & 0xFF;
                            int b = c & 0xFF;
                            bandLuma[i] = (byte) ((r * 77 + g * 150 + b * 29) >> 8);
                        }
                        return null;
                    });
                }
                for (Future<Void> f : PARALLEL_FILTER_POOL.invokeAll(lumaTasks)) {
                    f.get();
                }
            }

            if (cancelFlag.get()) {
                return;
            }

            // 2. Parallel Row-Sliced CAS Filter for current band
            final int rowsPerTask = (bandInnerRows + numThreads - 1) / numThreads;
            List<Callable<Void>> casTasks = new ArrayList<>(numThreads);
            for (int t = 0; t < numThreads; t++) {
                final int localRowStart = t * rowsPerTask;
                final int localRowEnd = Math.min(bandInnerRows, localRowStart + rowsPerTask);
                if (localRowStart >= localRowEnd) continue;

                casTasks.add(() -> {
                    for (int lr = localRowStart; lr < localRowEnd && !cancelFlag.get(); lr++) {
                        int srcRowOffset = (lr + 1) * w;
                        int dstRowOffset = lr * w;
                        for (int x = 1; x < w - 1; x++) {
                            int sIdx = srcRowOffset + x;
                            int centerL = bandLuma[sIdx] & 0xFF;
                            int topL = bandLuma[sIdx - w] & 0xFF;
                            int botL = bandLuma[sIdx + w] & 0xFF;
                            int leftL = bandLuma[sIdx - 1] & 0xFF;
                            int rightL = bandLuma[sIdx + 1] & 0xFF;

                            int minL = Math.min(centerL, Math.min(Math.min(topL, botL), Math.min(leftL, rightL)));
                            int maxL = Math.max(centerL, Math.max(Math.max(topL, botL), Math.max(leftL, rightL)));
                            if (maxL - minL < 5) {
                                continue;
                            }

                            int avgNeighbors = (topL + botL + leftL + rightL) >> 2;
                            int detail = centerL - avgNeighbors;
                            int headroom = Math.min(minL, 255 - maxL);
                            int gainNum = 90 + (headroom >> 1);
                            int deltaL = (detail * gainNum) >> 8;

                            int c = bandSrc[sIdx];
                            int a = c & 0xFF000000;
                            int r = clampByte(((c >> 16) & 0xFF) + deltaL);
                            int g = clampByte(((c >> 8) & 0xFF) + deltaL);
                            int b = clampByte((c & 0xFF) + deltaL);
                            bandDst[dstRowOffset + x] = a | (r << 16) | (g << 8) | b;
                        }

                        int done = completedRows.incrementAndGet();
                        int pct = (done * 100) / innerRowsTotal;
                        int step = pct / 5;
                        int prev = lastReportedStep.get();
                        if (step > prev && lastReportedStep.compareAndSet(prev, step)) {
                            progress.onStep(pct);
                        }
                    }
                    return null;
                });
            }
            for (Future<Void> f : PARALLEL_FILTER_POOL.invokeAll(casTasks)) {
                f.get();
            }

            if (!cancelFlag.get()) {
                bitmap.setPixels(bandDst, 0, w, 0, bandStartY, w, bandInnerRows);
            }
        }
    }

    private static int clampByte(int val) {
        return val < 0 ? 0 : Math.min(val, 255);
    }

    private static Bitmap createSmoothScaledBitmap(Bitmap src, int outW, int outH) {
        Bitmap dst = Bitmap.createBitmap(outW, outH, Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(dst);
        Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG | Paint.DITHER_FLAG);
        Rect srcRect = new Rect(0, 0, src.getWidth(), src.getHeight());
        Rect dstRect = new Rect(0, 0, outW, outH);
        canvas.drawBitmap(src, srcRect, dstRect, paint);
        return dst;
    }

    private static boolean hasNonOpaquePixels(Bitmap bmp) {
        if (!bmp.hasAlpha()) {
            return false;
        }
        int w = bmp.getWidth();
        int h = bmp.getHeight();
        int stepY = Math.max(1, h / 32);
        int stepX = Math.max(1, w / 32);
        int[] rowPixels = new int[w];
        for (int y = 0; y < h; y += stepY) {
            bmp.getPixels(rowPixels, 0, w, 0, y, w, 1);
            for (int x = 0; x < w; x += stepX) {
                if (((rowPixels[x] >>> 24) & 0xFF) < 250) {
                    return true;
                }
            }
        }
        return false;
    }

    @SuppressWarnings("deprecation")
    private static byte[] compressToBytes(Bitmap bmp, boolean webp, int quality) {
        ByteArrayOutputStream baos = new ByteArrayOutputStream(64 * 1024);
        if (webp) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                bmp.compress(Bitmap.CompressFormat.WEBP_LOSSY, quality, baos);
            } else {
                bmp.compress(Bitmap.CompressFormat.WEBP, quality, baos);
            }
        } else {
            bmp.compress(Bitmap.CompressFormat.JPEG, quality, baos);
        }
        return baos.toByteArray();
    }

    private static Bitmap decodeBitmapFromUri(Context context, Uri uri, final int maxSide) throws IOException {
        ContentResolver resolver = context.getContentResolver();
        // Prefer ImageDecoder on API 28+: automatically applies EXIF orientation and supports HEIC/AVIF/WebP
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            try {
                ImageDecoder.Source source = ImageDecoder.createSource(resolver, uri);
                return ImageDecoder.decodeBitmap(source, (decoder, info, src) -> {
                    decoder.setAllocator(ImageDecoder.ALLOCATOR_SOFTWARE);
                    int w = info.getSize().getWidth();
                    int h = info.getSize().getHeight();
                    int longest = Math.max(w, h);
                    if (longest > maxSide) {
                        double scale = (double) maxSide / (double) longest;
                        decoder.setTargetSize(
                                Math.max(1, (int) Math.round(w * scale)),
                                Math.max(1, (int) Math.round(h * scale))
                        );
                    }
                });
            } catch (Exception ignored) {
                // Fall through to BitmapFactory fallback
            }
        }

        BitmapFactory.Options boundsOpts = new BitmapFactory.Options();
        boundsOpts.inJustDecodeBounds = true;
        try (InputStream in = resolver.openInputStream(uri)) {
            if (in != null) {
                BitmapFactory.decodeStream(in, null, boundsOpts);
            }
        }
        int sampleSize = 1;
        int longest = Math.max(boundsOpts.outWidth, boundsOpts.outHeight);
        while (longest > 0 && longest / sampleSize > maxSide) {
            sampleSize *= 2;
        }

        BitmapFactory.Options decodeOpts = new BitmapFactory.Options();
        decodeOpts.inSampleSize = sampleSize;
        decodeOpts.inPreferredConfig = Bitmap.Config.ARGB_8888;
        try (InputStream in = resolver.openInputStream(uri)) {
            if (in == null) {
                return null;
            }
            return BitmapFactory.decodeStream(in, null, decodeOpts);
        }
    }
}
