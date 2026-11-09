package com.example.topbardrop;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.os.Build;
import android.util.TypedValue;
import android.view.InputDevice;
import android.view.MotionEvent;
import android.view.PointerIcon;
import android.view.View;
import android.view.animation.AccelerateInterpolator;
import android.view.animation.OvershootInterpolator;

import java.util.Locale;

/**
 * Interactive full-screen Color Picker canvas view with precision crosshair,
 * frosted-glass 11x11 pixel zoom loupe (auto-flipping near screen edges),
 * and 1px Arrow-Key nudging.
 */
final class ColorPickerCanvasView extends View {

    public interface Listener {
        void onColorPicked(int color, String hex);

        void onCancelRequested();
    }

    private final Bitmap screenBmp;
    private final Bitmap blurredBmp;
    private final String bannerHint;
    private final Listener listener;
    private float pointerX = -1f;
    private float pointerY = -1f;
    private float animProgress = 1f;
    private ValueAnimator currentAnimator;

    private final int gridRadius = 5; // 11 x 11 pixel grid
    private final int gridCount = gridRadius * 2 + 1;

    private final Paint bmpPaint = new Paint(Paint.FILTER_BITMAP_FLAG);
    private final Paint pixelPaint = new Paint();
    private final Paint gridLinePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint glassTintPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint textPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint subTextPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint bannerTextPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint crosshairDarkPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint crosshairLightPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint centerOuterPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint centerInnerPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF tempRect = new RectF();
    private final Rect fullDestRect = new Rect();
    private final Path clipPath = new Path();

    ColorPickerCanvasView(
            Context context,
            Bitmap bitmap,
            Bitmap blurred,
            String hint,
            boolean dark,
            Listener listener
    ) {
        super(context);
        this.screenBmp = bitmap;
        this.blurredBmp = blurred != null ? blurred : FrostedGlassCardLayout.createBlurredBackdropBitmap(bitmap);
        this.bannerHint = hint;
        this.listener = listener;

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            setPointerIcon(PointerIcon.getSystemIcon(context, PointerIcon.TYPE_CROSSHAIR));
        }

        gridLinePaint.setStyle(Paint.Style.STROKE);
        gridLinePaint.setStrokeWidth(1f);
        gridLinePaint.setColor(0x28FFFFFF);

        glassTintPaint.setStyle(Paint.Style.FILL);
        glassTintPaint.setColor(dark ? Color.parseColor("#A6181A22") : Color.parseColor("#B8F4F8FC"));

        textPaint.setColor(dark ? Color.WHITE : Color.parseColor("#181A1F"));
        textPaint.setTypeface(Typeface.create(Typeface.MONOSPACE, Typeface.BOLD));
        textPaint.setTextSize(TypedValue.applyDimension(
                TypedValue.COMPLEX_UNIT_SP, 14f, getResources().getDisplayMetrics()));

        subTextPaint.setColor(dark ? Color.parseColor("#D2D6DC") : Color.parseColor("#4A5058"));
        subTextPaint.setTypeface(Typeface.DEFAULT);
        subTextPaint.setTextSize(TypedValue.applyDimension(
                TypedValue.COMPLEX_UNIT_SP, 11f, getResources().getDisplayMetrics()));

        bannerTextPaint.setColor(dark ? Color.WHITE : Color.parseColor("#181A1F"));
        bannerTextPaint.setTypeface(Typeface.DEFAULT_BOLD);
        bannerTextPaint.setTextAlign(Paint.Align.CENTER);
        bannerTextPaint.setTextSize(TypedValue.applyDimension(
                TypedValue.COMPLEX_UNIT_SP, 12.5f, getResources().getDisplayMetrics()));

        crosshairDarkPaint.setStyle(Paint.Style.STROKE);
        crosshairDarkPaint.setStrokeWidth(dp(3f));
        crosshairDarkPaint.setColor(0xDD000000);
        crosshairDarkPaint.setStrokeCap(Paint.Cap.ROUND);

        crosshairLightPaint.setStyle(Paint.Style.STROKE);
        crosshairLightPaint.setStrokeWidth(dp(1.6f));
        crosshairLightPaint.setColor(0xFFFFFFFF);
        crosshairLightPaint.setStrokeCap(Paint.Cap.ROUND);

        centerOuterPaint.setStyle(Paint.Style.STROKE);
        centerOuterPaint.setStrokeWidth(dp(2f));
        centerOuterPaint.setColor(Color.WHITE);

        centerInnerPaint.setStyle(Paint.Style.STROKE);
        centerInnerPaint.setStrokeWidth(dp(1f));
        centerInnerPaint.setColor(Color.BLACK);
    }

    void cancelRunningAnimator() {
        if (currentAnimator != null) {
            currentAnimator.removeAllUpdateListeners();
            currentAnimator.removeAllListeners();
            currentAnimator.cancel();
            currentAnimator = null;
        }
    }

    @Override
    protected void onDetachedFromWindow() {
        cancelRunningAnimator();
        super.onDetachedFromWindow();
    }

    void startAppearAnimation() {
        if (currentAnimator != null) {
            currentAnimator.cancel();
        }
        animProgress = 0f;
        currentAnimator = ValueAnimator.ofFloat(0f, 1f);
        currentAnimator.setDuration(190);
        currentAnimator.setInterpolator(new OvershootInterpolator(1.08f));
        currentAnimator.addUpdateListener(animation -> {
            animProgress = (Float) animation.getAnimatedValue();
            invalidate();
        });
        currentAnimator.start();
    }

    void startDisappearAnimation(final Runnable onEnd) {
        if (currentAnimator != null) {
            currentAnimator.cancel();
        }
        currentAnimator = ValueAnimator.ofFloat(animProgress, 0f);
        currentAnimator.setDuration(150);
        currentAnimator.setInterpolator(new AccelerateInterpolator(1.2f));
        currentAnimator.addUpdateListener(animation -> {
            animProgress = (Float) animation.getAnimatedValue();
            invalidate();
        });
        currentAnimator.addListener(new AnimatorListenerAdapter() {
            @Override
            public void onAnimationEnd(Animator animation) {
                if (onEnd != null) {
                    onEnd.run();
                }
            }
        });
        currentAnimator.start();
    }

    void updatePointerFromMotionEvent(MotionEvent event) {
        pointerX = event.getX();
        pointerY = event.getY();
        invalidate();
    }

    void nudgePointerByPixels(int dx, int dy) {
        int maxW = Math.max(1, getWidth() - 1);
        int maxH = Math.max(1, getHeight() - 1);
        pointerX = Math.max(0f, Math.min(maxW, pointerX + dx));
        pointerY = Math.max(0f, Math.min(maxH, pointerY + dy));
        invalidate();
    }

    @Override
    protected void onSizeChanged(int w, int h, int oldw, int oldh) {
        super.onSizeChanged(w, h, oldw, oldh);
        if (pointerX < 0f || pointerY < 0f) {
            pointerX = w / 2f;
            pointerY = h / 2f;
        }
    }

    private void drawFrostedGlassCard(Canvas canvas, RectF bounds, float radius, int screenW, int screenH) {
        canvas.save();
        clipPath.reset();
        clipPath.addRoundRect(bounds, radius, radius, Path.Direction.CW);
        canvas.clipPath(clipPath);
        if (blurredBmp != null && !blurredBmp.isRecycled()) {
            fullDestRect.set(0, 0, screenW, screenH);
            canvas.drawBitmap(blurredBmp, null, fullDestRect, bmpPaint);
        }
        canvas.drawRect(bounds, glassTintPaint);
        canvas.restore();
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        int viewW = getWidth();
        int viewH = getHeight();
        if (viewW <= 0 || viewH <= 0 || screenBmp == null || screenBmp.isRecycled()) {
            return;
        }

        int alphaInt = clamp(Math.round(Math.min(1f, Math.max(0f, animProgress)) * 255f), 0, 255);
        bmpPaint.setAlpha(alphaInt);
        // Draw the unblurred screen snapshot so the rest of the screen stays 100% sharp!
        canvas.drawBitmap(screenBmp, 0f, 0f, bmpPaint);

        if (pointerX < 0f || pointerY < 0f) {
            pointerX = viewW / 2f;
            pointerY = viewH / 2f;
        }

        int bmpX = clamp(Math.round(pointerX), 0, screenBmp.getWidth() - 1);
        int bmpY = clamp(Math.round(pointerY), 0, screenBmp.getHeight() - 1);
        int centerColor = screenBmp.getPixel(bmpX, bmpY);
        String hex = String.format(Locale.US, "#%06X", (0xFFFFFF & centerColor));
        int r = Color.red(centerColor);
        int g = Color.green(centerColor);
        int b = Color.blue(centerColor);

        float scale = 0.72f + 0.28f * animProgress;

        // 1. Draw Frosted Glass Top Instruction Pill (blurs ONLY inside the pill!)
        String topMsg = (bannerHint != null && !bannerHint.isEmpty())
                ? bannerHint
                : "Color Picker — Move cursor & click to copy " + hex + " · Right-click or Esc to cancel";
        float pillWidth = bannerTextPaint.measureText(topMsg) + dp(34f);
        float pillHeight = dp(36f);
        float pillLeft = (viewW - pillWidth) / 2f;
        float pillTop = dp(50f);
        RectF pillBounds = new RectF(pillLeft, pillTop, pillLeft + pillWidth, pillTop + pillHeight);
        canvas.save();
        canvas.scale(scale, scale, viewW / 2f, pillTop + pillHeight / 2f);
        drawFrostedGlassCard(canvas, pillBounds, dp(18f), screenBmp.getWidth(), screenBmp.getHeight());
        canvas.drawText(topMsg, viewW / 2f, pillTop + dp(23f), bannerTextPaint);
        canvas.restore();

        // 2. Draw high-contrast precision Crosshair at (pointerX, pointerY)
        float chGap = dp(5f);
        float chLen = dp(18f);

        canvas.drawLine(pointerX - chLen, pointerY, pointerX - chGap, pointerY, crosshairDarkPaint);
        canvas.drawLine(pointerX + chGap, pointerY, pointerX + chLen, pointerY, crosshairDarkPaint);
        canvas.drawLine(pointerX, pointerY - chLen, pointerX, pointerY - chGap, crosshairDarkPaint);
        canvas.drawLine(pointerX, pointerY + chGap, pointerX, pointerY + chLen, crosshairDarkPaint);
        canvas.drawCircle(pointerX, pointerY, chGap, crosshairDarkPaint);

        canvas.drawLine(pointerX - chLen, pointerY, pointerX - chGap, pointerY, crosshairLightPaint);
        canvas.drawLine(pointerX + chGap, pointerY, pointerX + chLen, pointerY, crosshairLightPaint);
        canvas.drawLine(pointerX, pointerY - chLen, pointerX, pointerY - chGap, crosshairLightPaint);
        canvas.drawLine(pointerX, pointerY + chGap, pointerX, pointerY + chLen, crosshairLightPaint);
        canvas.drawCircle(pointerX, pointerY, chGap, crosshairLightPaint);

        // 3. Compute Frosted Glass Zoom Loupe Card bounds (auto-flips below crosshair if near top edge!)
        float cellPx = dp(11f);
        float gridSize = cellPx * gridCount;
        float pad = dp(10f);
        float infoHeight = dp(46f);
        float cardW = gridSize + pad * 2f;
        float cardH = gridSize + pad * 2f + infoHeight;
        float cursorOffset = dp(26f);
        float screenEdgeMargin = dp(14f);

        float cardLeft = pointerX - cardW / 2f;
        float cardTop = pointerY - cursorOffset - cardH;

        if (cardTop < screenEdgeMargin) {
            cardTop = pointerY + cursorOffset;
        }
        if (cardTop + cardH > viewH - screenEdgeMargin) {
            cardTop = viewH - screenEdgeMargin - cardH;
        }
        if (cardLeft < screenEdgeMargin) {
            cardLeft = screenEdgeMargin;
        } else if (cardLeft + cardW > viewW - screenEdgeMargin) {
            cardLeft = viewW - screenEdgeMargin - cardW;
        }

        canvas.save();
        canvas.scale(scale, scale, pointerX, pointerY);

        RectF cardBounds = new RectF(cardLeft, cardTop, cardLeft + cardW, cardTop + cardH);
        drawFrostedGlassCard(canvas, cardBounds, dp(18f), screenBmp.getWidth(), screenBmp.getHeight());

        // Draw Magnified 11x11 Pixel Grid inside rounded clip
        float gridLeft = cardLeft + pad;
        float gridTop = cardTop + pad;
        tempRect.set(gridLeft, gridTop, gridLeft + gridSize, gridTop + gridSize);

        canvas.save();
        clipPath.reset();
        clipPath.addRoundRect(tempRect, dp(10f), dp(10f), Path.Direction.CW);
        canvas.clipPath(clipPath);

        for (int dy = -gridRadius; dy <= gridRadius; dy++) {
            int sy = clamp(bmpY + dy, 0, screenBmp.getHeight() - 1);
            float py = gridTop + (dy + gridRadius) * cellPx;
            for (int dx = -gridRadius; dx <= gridRadius; dx++) {
                int sx = clamp(bmpX + dx, 0, screenBmp.getWidth() - 1);
                float px = gridLeft + (dx + gridRadius) * cellPx;
                pixelPaint.setColor(screenBmp.getPixel(sx, sy));
                canvas.drawRect(px, py, px + cellPx + 0.5f, py + cellPx + 0.5f, pixelPaint);
                canvas.drawRect(px, py, px + cellPx, py + cellPx, gridLinePaint);
            }
        }
        canvas.restore();

        // Highlight center pixel (exact target pixel)
        float centerPxLeft = gridLeft + gridRadius * cellPx;
        float centerPxTop = gridTop + gridRadius * cellPx;
        tempRect.set(centerPxLeft, centerPxTop, centerPxLeft + cellPx, centerPxTop + cellPx);
        canvas.drawRect(tempRect, centerOuterPaint);
        tempRect.inset(dp(1.5f), dp(1.5f));
        canvas.drawRect(tempRect, centerInnerPaint);

        // Draw Color Swatch + #RRGGBB HEX + RGB(r,g,b) below the magnified grid
        float infoTop = gridTop + gridSize + dp(8f);
        float swatchSize = dp(24f);
        float swatchLeft = gridLeft + dp(2f);
        float swatchTop = infoTop + dp(4f);

        tempRect.set(swatchLeft, swatchTop, swatchLeft + swatchSize, swatchTop + swatchSize);
        pixelPaint.setColor(centerColor);
        pixelPaint.setAntiAlias(true);
        canvas.drawRoundRect(tempRect, dp(6f), dp(6f), pixelPaint);

        float textLeft = swatchLeft + swatchSize + dp(8f);
        canvas.drawText(hex, textLeft, swatchTop + dp(13f), textPaint);
        canvas.drawText("RGB(" + r + "," + g + "," + b + ")", textLeft, swatchTop + dp(26f), subTextPaint);

        canvas.restore();
    }

    @Override
    public boolean onHoverEvent(MotionEvent event) {
        if (event != null) {
            updatePointerFromMotionEvent(event);
            return true;
        }
        return super.onHoverEvent(event);
    }

    @Override
    public boolean onTouchEvent(MotionEvent event) {
        if (event == null) return false;

        if ((event.getButtonState() & MotionEvent.BUTTON_SECONDARY) != 0) {
            if (listener != null) {
                listener.onCancelRequested();
            }
            return true;
        }

        boolean isMouse = (event.getSource() & InputDevice.SOURCE_MOUSE) == InputDevice.SOURCE_MOUSE;

        switch (event.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                updatePointerFromMotionEvent(event);
                if (isMouse) {
                    pickCurrentColorAndClose();
                }
                return true;

            case MotionEvent.ACTION_MOVE:
                updatePointerFromMotionEvent(event);
                return true;

            case MotionEvent.ACTION_UP:
                updatePointerFromMotionEvent(event);
                if (!isMouse) {
                    pickCurrentColorAndClose();
                }
                return true;
        }
        return super.onTouchEvent(event);
    }

    void pickCurrentColorAndClose() {
        if (screenBmp == null || screenBmp.isRecycled()) {
            if (listener != null) {
                listener.onCancelRequested();
            }
            return;
        }
        int bmpX = clamp(Math.round(pointerX), 0, screenBmp.getWidth() - 1);
        int bmpY = clamp(Math.round(pointerY), 0, screenBmp.getHeight() - 1);
        int pickedColor = screenBmp.getPixel(bmpX, bmpY);
        String hex = String.format(Locale.US, "#%06X", (0xFFFFFF & pickedColor));

        if (listener != null) {
            listener.onColorPicked(pickedColor, hex);
        }
    }

    private int dp(float value) {
        return MediaToolUtils.dpToPx(getContext(), value);
    }

    private static int clamp(int val, int min, int max) {
        return Math.max(min, Math.min(max, val));
    }
}
