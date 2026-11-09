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
}

