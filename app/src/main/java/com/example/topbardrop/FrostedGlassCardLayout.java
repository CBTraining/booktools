package com.example.topbardrop;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.Shader;
import android.widget.LinearLayout;

/**
 * Custom LinearLayout that renders a genuine frosted blurry glass effect strictly inside
 * its own rounded card bounds (sampling the blurred screen backdrop at its exact screen
 * coordinates + translucent glass sheen), leaving the rest of the screen 100% sharp and
 * unblurred with no outline border.
 */
final class FrostedGlassCardLayout extends LinearLayout {

    public interface BackdropProvider {
        Bitmap getCapturedScreenBitmap();

        Bitmap getBlurredScreenBitmap();
    }

    private final boolean dark;
    private final float cornerRadius;
    private final BackdropProvider backdropProvider;
    private final Path clipPath = new Path();
    private final RectF cardBounds = new RectF();
    private final Rect screenDestRect = new Rect();
    private final int[] locOnScreen = new int[2];

    private final Paint bmpPaint = new Paint(Paint.FILTER_BITMAP_FLAG);
    private final Paint tintPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint sheenPaint = new Paint(Paint.ANTI_ALIAS_FLAG);

    FrostedGlassCardLayout(Context context, boolean dark, float cornerRadius, BackdropProvider backdropProvider) {
        super(context);
        this.dark = dark;
        this.cornerRadius = cornerRadius;
        this.backdropProvider = backdropProvider;
        setWillNotDraw(false);

        tintPaint.setStyle(Paint.Style.FILL);
        tintPaint.setColor(dark ? Color.parseColor("#A81A1D26") : Color.parseColor("#B8F2F6FC"));
    }

    @Override
    protected void onSizeChanged(int w, int h, int oldw, int oldh) {
        super.onSizeChanged(w, h, oldw, oldh);
        sheenPaint.setShader(new LinearGradient(
                0, 0, w, h,
                dark
                        ? new int[]{0x38FFFFFF, 0x08FFFFFF, 0x182DD4BF}
                        : new int[]{0xAAFFFFFF, 0x22FFFFFF, 0x220D9488},
                new float[]{0f, 0.5f, 1f},
                Shader.TileMode.CLAMP
        ));
    }

    @Override
    protected void onDraw(Canvas canvas) {
        int w = getWidth();
        int h = getHeight();
        if (w <= 0 || h <= 0) {
            super.onDraw(canvas);
            return;
        }

        cardBounds.set(0, 0, w, h);

        canvas.save();
        clipPath.reset();
        clipPath.addRoundRect(cardBounds, cornerRadius, cornerRadius, Path.Direction.CW);
        canvas.clipPath(clipPath);

        if (backdropProvider != null) {
            Bitmap blurred = backdropProvider.getBlurredScreenBitmap();
            Bitmap captured = backdropProvider.getCapturedScreenBitmap();
            if (blurred != null && !blurred.isRecycled() && captured != null && !captured.isRecycled()) {
                getLocationOnScreen(locOnScreen);
                int screenW = captured.getWidth();
                int screenH = captured.getHeight();
                screenDestRect.set(
                        -locOnScreen[0],
                        -locOnScreen[1],
                        screenW - locOnScreen[0],
                        screenH - locOnScreen[1]
                );
                canvas.drawBitmap(blurred, null, screenDestRect, bmpPaint);
            }
        }

        // Frosted glass tint + diagonal glass sheen
        canvas.drawRect(cardBounds, tintPaint);
        canvas.drawRect(cardBounds, sheenPaint);
        canvas.restore();

        super.onDraw(canvas);
    }

    /**
     * Generates a downscaled 2-pass separable box-blurred backdrop bitmap from a screen snapshot.
     */
    static Bitmap createBlurredBackdropBitmap(Bitmap src) {
        if (src == null || src.isRecycled()) return null;
        try {
            int smallW = Math.max(32, src.getWidth() / 8);
            int smallH = Math.max(32, src.getHeight() / 8);
            Bitmap small = Bitmap.createScaledBitmap(src, smallW, smallH, true);
            int[] pixels = new int[smallW * smallH];
            int[] temp = new int[smallW * smallH];
            small.getPixels(pixels, 0, smallW, 0, 0, smallW, smallH);

            int radius = 3;
            for (int pass = 0; pass < 2; pass++) {
                for (int y = 0; y < smallH; y++) {
                    int rowOffset = y * smallW;
                    for (int x = 0; x < smallW; x++) {
                        int rSum = 0, gSum = 0, bSum = 0, count = 0;
                        for (int k = -radius; k <= radius; k++) {
                            int nx = Math.max(0, Math.min(smallW - 1, x + k));
                            int c = pixels[rowOffset + nx];
                            rSum += (c >> 16) & 0xFF;
                            gSum += (c >> 8) & 0xFF;
                            bSum += c & 0xFF;
                            count++;
                        }
                        temp[rowOffset + x] = 0xFF000000
                                | ((rSum / count) << 16)
                                | ((gSum / count) << 8)
                                | (bSum / count);
                    }
                }
                for (int x = 0; x < smallW; x++) {
                    for (int y = 0; y < smallH; y++) {
                        int rSum = 0, gSum = 0, bSum = 0, count = 0;
                        for (int k = -radius; k <= radius; k++) {
                            int ny = Math.max(0, Math.min(smallH - 1, y + k));
                            int c = temp[ny * smallW + x];
                            rSum += (c >> 16) & 0xFF;
                            gSum += (c >> 8) & 0xFF;
                            bSum += c & 0xFF;
                            count++;
                        }
                        pixels[y * smallW + x] = 0xFF000000
                                | ((rSum / count) << 16)
                                | ((gSum / count) << 8)
                                | (bSum / count);
                    }
                }
            }
            small.setPixels(pixels, 0, smallW, 0, 0, smallW, smallH);
            return small;
        } catch (Exception ignored) {
            return null;
        }
    }
}
