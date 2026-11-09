package com.example.topbardrop;

import android.graphics.SurfaceTexture;
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
import android.view.Surface;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;

/**
 * Self-contained EGL14 + GLES20 hardware surface bridge for VideoCompressor.
 * Receives decoded video frames on a SurfaceTexture and renders them directly into the
 * MediaCodec encoder's InputSurface on the GPU with zero CPU pixel copies.
 */
final class TranscodeGlSurface implements SurfaceTexture.OnFrameAvailableListener {

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
    private SurfaceTexture surfaceTexture;
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
        surfaceTexture = new SurfaceTexture(textureId);
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
    public void onFrameAvailable(SurfaceTexture st) {
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
