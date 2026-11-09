package com.example.topbardrop;

import android.content.Context;
import android.media.MediaCodec;
import android.media.MediaCodecInfo;
import android.media.MediaExtractor;
import android.media.MediaFormat;
import android.media.MediaMetadataRetriever;
import android.media.MediaMuxer;
import android.net.Uri;
import android.opengl.EGL14;
import android.opengl.EGLConfig;
import android.opengl.EGLContext;
import android.opengl.EGLDisplay;
import android.opengl.EGLExt;
import android.opengl.EGLSurface;
import android.opengl.GLES11Ext;
import android.opengl.GLES20;
import android.opengl.Matrix;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Looper;
import android.view.Surface;

import java.io.File;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 100% Offline Hardware-Accelerated Video Compressor (~50MB target).
 * Uses Android's built-in MediaExtractor + MediaCodec + EGL14/GLES20 Surface pipeline + MediaMuxer
 * to transcode any video offline into an H.264 MP4 sized under ~50MB in Downloads.
 */
public final class VideoCompressor {

    // Target 47.0 MB total budget so container overhead + keyframes always stay safely under 50.0 MB
    public static final long TARGET_MAX_BYTES = (long) (47.0 * 1024 * 1024);

    private static final String MIME_VIDEO_AVC = MediaFormat.MIMETYPE_VIDEO_AVC; // "video/avc"
    private static final int TIMEOUT_USEC = 10_000;

    private static final ExecutorService COMPRESS_EXECUTOR = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "BookTools-VideoCompressor");
        t.setDaemon(true);
        return t;
    });

    private VideoCompressor() {
    }

    public static final class VideoInfo {
        public final String displayName;
        public final long originalBytes;
        public final long durationMs;
        public final int width;
        public final int height;
        public final int rotation;

        public VideoInfo(String displayName, long originalBytes, long durationMs, int width, int height, int rotation) {
            this.displayName = displayName;
            this.originalBytes = originalBytes;
            this.durationMs = durationMs;
            this.width = width;
            this.height = height;
            this.rotation = rotation;
        }
    }

    public static final class CompressResult {
        public final Uri savedUri;
        public final String savedFileName;
        public final long originalBytes;
        public final long compressedBytes;
        public final int outputWidth;
        public final int outputHeight;

        public CompressResult(
                Uri savedUri,
                String savedFileName,
                long originalBytes,
                long compressedBytes,
                int outputWidth,
                int outputHeight
        ) {
            this.savedUri = savedUri;
            this.savedFileName = savedFileName;
            this.originalBytes = originalBytes;
            this.compressedBytes = compressedBytes;
            this.outputWidth = outputWidth;
            this.outputHeight = outputHeight;
        }
    }

    public interface CompressCallback {
        void onMetadataReady(VideoInfo info, int targetWidth, int targetHeight, int targetBitrateKbps);
        void onProgress(int percent, String statusText);
        void onSuccess(CompressResult result);
        void onError(String errorMessage);
    }

    public static VideoInfo inspectVideo(Context context, Uri videoUri) {
        MediaToolUtils.FileMeta fileMeta = MediaToolUtils.queryFileMeta(context, videoUri, "video.mp4");

        long durationMs = 0L;
        int width = 1280;
        int height = 720;
        int rotation = 0;

        MediaMetadataRetriever retriever = new MediaMetadataRetriever();
        try {
            retriever.setDataSource(context, videoUri);
            String durStr = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION);
            if (durStr != null) {
                durationMs = Long.parseLong(durStr.trim());
            }
            String wStr = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH);
            if (wStr != null) {
                width = Integer.parseInt(wStr.trim());
            }
            String hStr = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT);
            if (hStr != null) {
                height = Integer.parseInt(hStr.trim());
            }
            String rotStr = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION);
            if (rotStr != null) {
                rotation = Integer.parseInt(rotStr.trim());
            }
        } catch (Exception ignored) {
        } finally {
            try {
                retriever.release();
            } catch (Exception ignored) {
            }
        }

        return new VideoInfo(fileMeta.displayName, fileMeta.sizeBytes, durationMs, width, height, rotation);
    }

    public static void compressVideoAsync(
            final Context context,
            final Uri sourceUri,
            final AtomicBoolean cancelFlag,
            final CompressCallback callback
    ) {
        final Context appContext = context.getApplicationContext();
        final Handler mainHandler = new Handler(Looper.getMainLooper());

        COMPRESS_EXECUTOR.execute(() -> {
            File tempOutput = new File(appContext.getCacheDir(), "booktools_compress_" + System.currentTimeMillis() + ".mp4");
            try {
                VideoInfo info = inspectVideo(appContext, sourceUri);
                double durationSec = Math.max(1.0, info.durationMs / 1000.0);

                // If original video is already under 48MB, compress to ~55% of original size;
                // otherwise target 47MB so the output is guaranteed to be under ~50MB.
                long targetBytes = TARGET_MAX_BYTES;
                if (info.originalBytes > 0 && info.originalBytes < TARGET_MAX_BYTES) {
                    targetBytes = Math.max(2L * 1024L * 1024L, (long) (info.originalBytes * 0.55));
                }

                long totalBitsBudget = targetBytes * 8L;
                int estimatedAudioBps = 128_000;
                long rawVideoBps = (long) (totalBitsBudget / durationSec) - estimatedAudioBps;
                // Clamp between 220 kbps and 18 Mbps
                int targetVideoBps = (int) Math.max(220_000L, Math.min(18_000_000L, rawVideoBps));

                // Choose optimal max dimension based on target bitrate so lower bitrates look crisp
                int maxDim;
                if (targetVideoBps < 650_000) {
                    maxDim = 960;  // 540p
                } else if (targetVideoBps < 1_400_000) {
                    maxDim = 1280; // 720p
                } else {
                    maxDim = 1920; // 1080p
                }

                int srcW = Math.max(160, info.width);
                int srcH = Math.max(160, info.height);
                int longest = Math.max(srcW, srcH);
                double scale = (longest > maxDim) ? ((double) maxDim / (double) longest) : 1.0;
                int outW = align16((int) Math.round(srcW * scale));
                int outH = align16((int) Math.round(srcH * scale));

                final int fOutW = outW;
                final int fOutH = outH;
                final int fBitrateKbps = targetVideoBps / 1000;
                mainHandler.post(() -> callback.onMetadataReady(info, fOutW, fOutH, fBitrateKbps));

                transcodeToTempFile(
                        appContext,
                        sourceUri,
                        tempOutput,
                        info,
                        outW,
                        outH,
                        targetVideoBps,
                        cancelFlag,
                        (pct, text) -> mainHandler.post(() -> callback.onProgress(pct, text))
                );

                if (cancelFlag.get()) {
                    mainHandler.post(() -> callback.onError("Compression cancelled."));
                    return;
                }

                long compressedBytes = tempOutput.length();
                if (compressedBytes <= 0L) {
                    throw new IOException("Compressed output file was empty.");
                }

                String baseName = MediaToolUtils.stripExtension(info.displayName, "video");
                String outFileName = baseName + "_50mb.mp4";
                Uri savedUri = MediaToolUtils.saveFileToDownloads(appContext, tempOutput, outFileName, "video/mp4");
                if (savedUri == null) {
                    throw new IOException("Could not save compressed MP4 to Downloads.");
                }

                final CompressResult result = new CompressResult(
                        savedUri,
                        outFileName,
                        info.originalBytes,
                        compressedBytes,
                        outW,
                        outH
                );
                mainHandler.post(() -> callback.onSuccess(result));
            } catch (Exception e) {
                final String msg = e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName();
                mainHandler.post(() -> callback.onError(msg));
            } finally {
                if (tempOutput.exists()) {
                    //noinspection ResultOfMethodCallIgnored
                    tempOutput.delete();
                }
            }
        });
    }

    private interface InternalProgressListener {
        void onProgress(int percent, String statusText);
    }

    private static void transcodeToTempFile(
            Context context,
            Uri sourceUri,
            File tempOutput,
            VideoInfo info,
            int outWidth,
            int outHeight,
            int targetVideoBitrate,
            AtomicBoolean cancelFlag,
            InternalProgressListener progressListener
    ) throws Exception {
        MediaExtractor videoExtractor = new MediaExtractor();
        MediaExtractor audioExtractor = new MediaExtractor();
        MediaCodec decoder = null;
        MediaCodec encoder = null;
        MediaMuxer muxer = null;
        TranscodeGlSurface glSurface = null;

        try {
            videoExtractor.setDataSource(context, sourceUri, null);
            int videoTrackIndex = selectTrack(videoExtractor, "video/");
            if (videoTrackIndex < 0) {
                throw new IOException("No video track found in selected file.");
            }
            videoExtractor.selectTrack(videoTrackIndex);
            MediaFormat inputVideoFormat = videoExtractor.getTrackFormat(videoTrackIndex);

            // Inspect optional audio track for passthrough muxing
            int audioTrackIndex = -1;
            MediaFormat inputAudioFormat = null;
            try {
                audioExtractor.setDataSource(context, sourceUri, null);
                int idx = selectTrack(audioExtractor, "audio/");
                if (idx >= 0) {
                    MediaFormat af = audioExtractor.getTrackFormat(idx);
                    String audioMime = af.getString(MediaFormat.KEY_MIME);
                    // MP4 container supports AAC and MP3 audio tracks directly
                    if (MediaFormat.MIMETYPE_AUDIO_AAC.equals(audioMime)
                            || MediaFormat.MIMETYPE_AUDIO_MPEG.equals(audioMime)) {
                        audioTrackIndex = idx;
                        inputAudioFormat = af;
                        audioExtractor.selectTrack(audioTrackIndex);
                    }
                }
            } catch (Exception ignored) {
                audioTrackIndex = -1;
            }

            int frameRate = 30;
            if (inputVideoFormat.containsKey(MediaFormat.KEY_FRAME_RATE)) {
                try {
                    frameRate = Math.max(15, Math.min(60, inputVideoFormat.getInteger(MediaFormat.KEY_FRAME_RATE)));
                } catch (Exception ignored) {
                }
            }

            // Configure H.264 hardware encoder
            MediaFormat outputVideoFormat = MediaFormat.createVideoFormat(MIME_VIDEO_AVC, outWidth, outHeight);
            outputVideoFormat.setInteger(
                    MediaFormat.KEY_COLOR_FORMAT,
                    MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface
            );
            outputVideoFormat.setInteger(MediaFormat.KEY_BIT_RATE, targetVideoBitrate);
            outputVideoFormat.setInteger(MediaFormat.KEY_FRAME_RATE, frameRate);
            outputVideoFormat.setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 2);

            encoder = MediaCodec.createEncoderByType(MIME_VIDEO_AVC);
            encoder.configure(outputVideoFormat, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE);
            Surface encoderInputSurface = encoder.createInputSurface();

            // Initialize single-context EGL + GLES20 bridge from Decoder SurfaceTexture -> Encoder Surface
            glSurface = new TranscodeGlSurface(encoderInputSurface, outWidth, outHeight);
            encoder.start();

            String inputMime = inputVideoFormat.getString(MediaFormat.KEY_MIME);
            if (inputMime == null) {
                throw new IOException("Unsupported video track MIME type.");
            }
            decoder = MediaCodec.createDecoderByType(inputMime);
            decoder.configure(inputVideoFormat, glSurface.getDecoderSurface(), null, 0);
            decoder.start();

            muxer = new MediaMuxer(tempOutput.getAbsolutePath(), MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4);
            if (info.rotation == 90 || info.rotation == 180 || info.rotation == 270) {
                muxer.setOrientationHint(info.rotation);
            }

            MediaCodec.BufferInfo bufferInfo = new MediaCodec.BufferInfo();
            boolean extractorDone = false;
            boolean decoderDone = false;
            boolean encoderDone = false;
            boolean muxerStarted = false;
            int muxerVideoTrack = -1;
            int muxerAudioTrack = -1;

            long durationUs = Math.max(1_000L, info.durationMs * 1000L);
            int lastReportedPct = -1;

            while (!encoderDone && !cancelFlag.get()) {
                // 1. Feed compressed samples from MediaExtractor into Decoder
                if (!extractorDone) {
                    int inputBufIndex = decoder.dequeueInputBuffer(TIMEOUT_USEC);
                    if (inputBufIndex >= 0) {
                        ByteBuffer inputBuf = decoder.getInputBuffer(inputBufIndex);
                        int sampleSize = (inputBuf != null) ? videoExtractor.readSampleData(inputBuf, 0) : -1;
                        if (sampleSize < 0) {
                            decoder.queueInputBuffer(
                                    inputBufIndex, 0, 0, 0L, MediaCodec.BUFFER_FLAG_END_OF_STREAM
                            );
                            extractorDone = true;
                        } else {
                            long presentationTimeUs = videoExtractor.getSampleTime();
                            decoder.queueInputBuffer(
                                    inputBufIndex, 0, sampleSize, presentationTimeUs, 0
                            );
                            videoExtractor.advance();
                        }
                    }
                }

                // 2. Drain decoded frames from Decoder -> GL Surface -> Encoder InputSurface
                boolean decoderOutputAvailable = !decoderDone;
                while (decoderOutputAvailable && !cancelFlag.get()) {
                    int outStatus = decoder.dequeueOutputBuffer(bufferInfo, TIMEOUT_USEC);
                    if (outStatus == MediaCodec.INFO_TRY_AGAIN_LATER
                            || outStatus == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED
                            || outStatus == MediaCodec.INFO_OUTPUT_BUFFERS_CHANGED) {
                        decoderOutputAvailable = false;
                    } else if (outStatus >= 0) {
                        boolean doRender = (bufferInfo.size != 0);
                        decoder.releaseOutputBuffer(outStatus, doRender);
                        if (doRender) {
                            glSurface.awaitNewImageAndDraw(bufferInfo.presentationTimeUs * 1000L);
                        }
                        if ((bufferInfo.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) {
                            encoder.signalEndOfInputStream();
                            decoderDone = true;
                            decoderOutputAvailable = false;
                        }
                    }
                }

                // 3. Drain encoded H.264 packets from Encoder -> MediaMuxer
                boolean encoderOutputAvailable = true;
                while (encoderOutputAvailable && !cancelFlag.get()) {
                    int encStatus = encoder.dequeueOutputBuffer(bufferInfo, TIMEOUT_USEC);
                    if (encStatus == MediaCodec.INFO_TRY_AGAIN_LATER
                            || encStatus == MediaCodec.INFO_OUTPUT_BUFFERS_CHANGED) {
                        encoderOutputAvailable = false;
                    } else if (encStatus == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                        if (muxerStarted) {
                            throw new IllegalStateException("Encoder output format changed twice.");
                        }
                        MediaFormat newFormat = encoder.getOutputFormat();
                        muxerVideoTrack = muxer.addTrack(newFormat);
                        if (audioTrackIndex >= 0 && inputAudioFormat != null) {
                            try {
                                muxerAudioTrack = muxer.addTrack(inputAudioFormat);
                            } catch (Exception ignored) {
                                muxerAudioTrack = -1;
                            }
                        }
                        muxer.start();
                        muxerStarted = true;
                    } else if (encStatus >= 0) {
                        ByteBuffer encodedData = encoder.getOutputBuffer(encStatus);
                        if ((bufferInfo.flags & MediaCodec.BUFFER_FLAG_CODEC_CONFIG) != 0) {
                            bufferInfo.size = 0;
                        }
                        if (bufferInfo.size > 0 && muxerStarted && encodedData != null) {
                            encodedData.position(bufferInfo.offset);
                            encodedData.limit(bufferInfo.offset + bufferInfo.size);
                            muxer.writeSampleData(muxerVideoTrack, encodedData, bufferInfo);

                            int pct = (int) Math.min(95L, (bufferInfo.presentationTimeUs * 95L) / durationUs);
                            if (pct > lastReportedPct) {
                                lastReportedPct = pct;
                                progressListener.onProgress(pct, "Compressing video offline... " + pct + "%");
                            }
                        }
                        encoder.releaseOutputBuffer(encStatus, false);
                        if ((bufferInfo.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) {
                            encoderDone = true;
                            encoderOutputAvailable = false;
                        }
                    }
                }
            }

            // 4. Copy passthrough audio packets if present
            if (!cancelFlag.get() && muxerStarted && muxerAudioTrack >= 0) {
                progressListener.onProgress(96, "Muxing audio track... 96%");
                ByteBuffer audioBuf = ByteBuffer.allocateDirect(256 * 1024);
                MediaCodec.BufferInfo audioInfo = new MediaCodec.BufferInfo();
                while (!cancelFlag.get()) {
                    int sampleSize = audioExtractor.readSampleData(audioBuf, 0);
                    if (sampleSize < 0) {
                        break;
                    }
                    long sampleTimeUs = audioExtractor.getSampleTime();
                    if (sampleTimeUs > durationUs + 500_000L) {
                        break;
                    }
                    audioInfo.offset = 0;
                    audioInfo.size = sampleSize;
                    audioInfo.presentationTimeUs = Math.max(0L, sampleTimeUs);
                    int sampleFlags = audioExtractor.getSampleFlags();
                    audioInfo.flags = ((sampleFlags & MediaExtractor.SAMPLE_FLAG_SYNC) != 0)
                            ? MediaCodec.BUFFER_FLAG_KEY_FRAME
                            : 0;
                    muxer.writeSampleData(muxerAudioTrack, audioBuf, audioInfo);
                    audioExtractor.advance();
                }
            }

            progressListener.onProgress(99, "Saving compressed MP4 to Downloads...");
        } finally {
            try {
                videoExtractor.release();
            } catch (Exception ignored) {
            }
            try {
                audioExtractor.release();
            } catch (Exception ignored) {
            }
            if (decoder != null) {
                try {
                    decoder.stop();
                } catch (Exception ignored) {
                }
                try {
                    decoder.release();
                } catch (Exception ignored) {
                }
            }
            if (encoder != null) {
                try {
                    encoder.stop();
                } catch (Exception ignored) {
                }
                try {
                    encoder.release();
                } catch (Exception ignored) {
                }
            }
            if (glSurface != null) {
                try {
                    glSurface.release();
                } catch (Exception ignored) {
                }
            }
            if (muxer != null) {
                try {
                    muxer.stop();
                } catch (Exception ignored) {
                }
                try {
                    muxer.release();
                } catch (Exception ignored) {
                }
            }
        }
    }

    private static int selectTrack(MediaExtractor extractor, String mimePrefix) {
        int numTracks = extractor.getTrackCount();
        for (int i = 0; i < numTracks; i++) {
            MediaFormat format = extractor.getTrackFormat(i);
            String mime = format.getString(MediaFormat.KEY_MIME);
            if (mime != null && mime.startsWith(mimePrefix)) {
                return i;
            }
        }
        return -1;
    }

    private static int align16(int val) {
        int aligned = ((val + 8) / 16) * 16;
        return Math.max(160, aligned);
    }

    public static String formatBytes(long bytes) {
        return MediaToolUtils.formatBytes(bytes);
    }

    /**
     * Self-contained EGL14 + GLES20 hardware surface bridge.
     * Receives decoded video frames on a SurfaceTexture and renders them directly into the
     * MediaCodec encoder's InputSurface on the GPU with zero CPU pixel copies.
     */
    private static final class TranscodeGlSurface implements android.graphics.SurfaceTexture.OnFrameAvailableListener {
        private static final int EGL_RECORDABLE_ANDROID = 0x3142;

        private static final String VERTEX_SHADER =
                "uniform mat4 uMVPMatrix;\n" +
                "uniform mat4 uSTMatrix;\n" +
                "attribute vec4 aPosition;\n" +
                "attribute vec4 aTextureCoord;\n" +
                "varying vec2 vTextureCoord;\n" +
                "void main() {\n" +
                "  gl_Position = uMVPMatrix * aPosition;\n" +
                "  vTextureCoord = (uSTMatrix * aTextureCoord).xy;\n" +
                "}\n";

        private static final String FRAGMENT_SHADER =
                "#extension GL_OES_EGL_image_external : require\n" +
                "precision mediump float;\n" +
                "varying vec2 vTextureCoord;\n" +
                "uniform samplerExternalOES sTexture;\n" +
                "void main() {\n" +
                "  gl_FragColor = texture2D(sTexture, vTextureCoord);\n" +
                "}\n";

        private static final float[] TRIANGLE_VERTICES_DATA = {
                // X, Y, Z, U, V
                -1.0f, -1.0f, 0f, 0f, 0f,
                 1.0f, -1.0f, 0f, 1f, 0f,
                -1.0f,  1.0f, 0f, 0f, 1f,
                 1.0f,  1.0f, 0f, 1f, 1f,
        };

        private final int width;
        private final int height;
        private EGLDisplay eglDisplay = EGL14.EGL_NO_DISPLAY;
        private EGLContext eglContext = EGL14.EGL_NO_CONTEXT;
        private EGLSurface eglSurface = EGL14.EGL_NO_SURFACE;
        private Surface encoderSurface;

        private HandlerThread frameCallbackThread;
        private android.graphics.SurfaceTexture surfaceTexture;
        private Surface decoderSurface;
        private final Object frameSyncObject = new Object();
        private boolean frameAvailable = false;

        private final FloatBuffer triangleVertices;
        private final float[] mvpMatrix = new float[16];
        private final float[] stMatrix = new float[16];
        private int program;
        private int textureId;
        private int uMVPMatrixHandle;
        private int uSTMatrixHandle;
        private int aPositionHandle;
        private int aTextureHandle;

        TranscodeGlSurface(Surface encoderInputSurface, int width, int height) {
            this.encoderSurface = encoderInputSurface;
            this.width = width;
            this.height = height;

            triangleVertices = ByteBuffer.allocateDirect(TRIANGLE_VERTICES_DATA.length * 4)
                    .order(ByteOrder.nativeOrder())
                    .asFloatBuffer();
            triangleVertices.put(TRIANGLE_VERTICES_DATA).position(0);
            Matrix.setIdentityM(mvpMatrix, 0);
            Matrix.setIdentityM(stMatrix, 0);

            initEgl();
            initGlShaderAndSurfaceTexture();
        }

        private void initEgl() {
            eglDisplay = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY);
            if (eglDisplay == EGL14.EGL_NO_DISPLAY) {
                throw new RuntimeException("Unable to get EGL14 display.");
            }
            int[] version = new int[2];
            if (!EGL14.eglInitialize(eglDisplay, version, 0, version, 1)) {
                throw new RuntimeException("Unable to initialize EGL14.");
            }

            int[] attribList = {
                    EGL14.EGL_RED_SIZE, 8,
                    EGL14.EGL_GREEN_SIZE, 8,
                    EGL14.EGL_BLUE_SIZE, 8,
                    EGL14.EGL_ALPHA_SIZE, 8,
                    EGL14.EGL_RENDERABLE_TYPE, EGL14.EGL_OPENGL_ES2_BIT,
                    EGL_RECORDABLE_ANDROID, 1,
                    EGL14.EGL_NONE
            };
            EGLConfig[] configs = new EGLConfig[1];
            int[] numConfigs = new int[1];
            if (!EGL14.eglChooseConfig(eglDisplay, attribList, 0, configs, 0, configs.length, numConfigs, 0)) {
                throw new RuntimeException("Unable to find RGB888 recordable EGLConfig.");
            }

            int[] contextAttribs = {
                    EGL14.EGL_CONTEXT_CLIENT_VERSION, 2,
                    EGL14.EGL_NONE
            };
            eglContext = EGL14.eglCreateContext(eglDisplay, configs[0], EGL14.EGL_NO_CONTEXT, contextAttribs, 0);
            int[] surfaceAttribs = {EGL14.EGL_NONE};
            eglSurface = EGL14.eglCreateWindowSurface(eglDisplay, configs[0], encoderSurface, surfaceAttribs, 0);

            if (!EGL14.eglMakeCurrent(eglDisplay, eglSurface, eglSurface, eglContext)) {
                throw new RuntimeException("eglMakeCurrent failed.");
            }
        }

        private void initGlShaderAndSurfaceTexture() {
            int vertexShader = loadShader(GLES20.GL_VERTEX_SHADER, VERTEX_SHADER);
            int fragmentShader = loadShader(GLES20.GL_FRAGMENT_SHADER, FRAGMENT_SHADER);
            program = GLES20.glCreateProgram();
            GLES20.glAttachShader(program, vertexShader);
            GLES20.glAttachShader(program, fragmentShader);
            GLES20.glLinkProgram(program);

            aPositionHandle = GLES20.glGetAttribLocation(program, "aPosition");
            aTextureHandle = GLES20.glGetAttribLocation(program, "aTextureCoord");
            uMVPMatrixHandle = GLES20.glGetUniformLocation(program, "uMVPMatrix");
            uSTMatrixHandle = GLES20.glGetUniformLocation(program, "uSTMatrix");

            int[] textures = new int[1];
            GLES20.glGenTextures(1, textures, 0);
            textureId = textures[0];
            GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, textureId);
            GLES20.glTexParameterf(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR);
            GLES20.glTexParameterf(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR);
            GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE);
            GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE);

            // Use a dedicated Looper thread for SurfaceTexture callbacks so wait() on the transcode thread never deadlocks
            frameCallbackThread = new HandlerThread("BookTools-GLFrameListener");
            frameCallbackThread.start();
            surfaceTexture = new android.graphics.SurfaceTexture(textureId);
            surfaceTexture.setOnFrameAvailableListener(this, new Handler(frameCallbackThread.getLooper()));
            decoderSurface = new Surface(surfaceTexture);
        }

        private static int loadShader(int shaderType, String source) {
            int shader = GLES20.glCreateShader(shaderType);
            GLES20.glShaderSource(shader, source);
            GLES20.glCompileShader(shader);
            return shader;
        }

        Surface getDecoderSurface() {
            return decoderSurface;
        }

        @Override
        public void onFrameAvailable(android.graphics.SurfaceTexture st) {
            synchronized (frameSyncObject) {
                frameAvailable = true;
                frameSyncObject.notifyAll();
            }
        }

        void awaitNewImageAndDraw(long presentationTimeNs) {
            synchronized (frameSyncObject) {
                long deadline = System.currentTimeMillis() + 2500L;
                while (!frameAvailable) {
                    long remaining = deadline - System.currentTimeMillis();
                    if (remaining <= 0) {
                        break;
                    }
                    try {
                        frameSyncObject.wait(remaining);
                    } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                        break;
                    }
                }
                frameAvailable = false;
            }

            surfaceTexture.updateTexImage();
            surfaceTexture.getTransformMatrix(stMatrix);

            GLES20.glViewport(0, 0, width, height);
            GLES20.glClearColor(0.0f, 0.0f, 0.0f, 1.0f);
            GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT);
            GLES20.glUseProgram(program);

            GLES20.glActiveTexture(GLES20.GL_TEXTURE0);
            GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, textureId);

            triangleVertices.position(0);
            GLES20.glVertexAttribPointer(aPositionHandle, 3, GLES20.GL_FLOAT, false, 5 * 4, triangleVertices);
            GLES20.glEnableVertexAttribArray(aPositionHandle);

            triangleVertices.position(3);
            GLES20.glVertexAttribPointer(aTextureHandle, 2, GLES20.GL_FLOAT, false, 5 * 4, triangleVertices);
            GLES20.glEnableVertexAttribArray(aTextureHandle);

            Matrix.setIdentityM(mvpMatrix, 0);
            GLES20.glUniformMatrix4fv(uMVPMatrixHandle, 1, false, mvpMatrix, 0);
            GLES20.glUniformMatrix4fv(uSTMatrixHandle, 1, false, stMatrix, 0);

            GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4);
            GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, 0);

            EGLExt.eglPresentationTimeANDROID(eglDisplay, eglSurface, presentationTimeNs);
            EGL14.eglSwapBuffers(eglDisplay, eglSurface);
        }

        void release() {
            if (decoderSurface != null) {
                try {
                    decoderSurface.release();
                } catch (Exception ignored) {
                }
                decoderSurface = null;
            }
            if (surfaceTexture != null) {
                try {
                    surfaceTexture.release();
                } catch (Exception ignored) {
                }
                surfaceTexture = null;
            }
            if (frameCallbackThread != null) {
                try {
                    frameCallbackThread.quitSafely();
                } catch (Exception ignored) {
                }
                frameCallbackThread = null;
            }
            if (eglDisplay != EGL14.EGL_NO_DISPLAY) {
                EGL14.eglMakeCurrent(eglDisplay, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT);
                if (eglSurface != EGL14.EGL_NO_SURFACE) {
                    EGL14.eglDestroySurface(eglDisplay, eglSurface);
                    eglSurface = EGL14.EGL_NO_SURFACE;
                }
                if (eglContext != EGL14.EGL_NO_CONTEXT) {
                    EGL14.eglDestroyContext(eglDisplay, eglContext);
                    eglContext = EGL14.EGL_NO_CONTEXT;
                }
                EGL14.eglTerminate(eglDisplay);
                eglDisplay = EGL14.EGL_NO_DISPLAY;
            }
            if (encoderSurface != null) {
                try {
                    encoderSurface.release();
                } catch (Exception ignored) {
                }
                encoderSurface = null;
            }
        }
    }
}
