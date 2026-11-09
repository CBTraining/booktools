package com.example.topbardrop;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Shader;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Bundle;
import android.text.TextUtils;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.Window;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;

import java.util.Locale;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Floating desktop dialog activity launched from the Files app right-click menu:
 * Right-Click video -> Open with (or Share) -> "Convert video to GIF (<50mb)".
 * Lets the user select the Start and End points of the GIF with a live frame preview
 * and encodes a 256-color looping GIF89a under 50MB in Downloads.
 */
public class VideoToGifActivity extends Activity {

    private final AtomicBoolean cancelFlag = new AtomicBoolean(false);
    private boolean isEncoding = false;
    private boolean isFinished = false;

    private Uri sourceVideoUri;
    private VideoCompressor.VideoInfo videoInfo;
    private long totalDurationMs = 10_000L;
    private long startMs = 0L;
    private long endMs = 6_000L;
    private long lastPreviewRequestTimeMs = 0L;

    private TextView fileTitleView;
    private TextView metaSubtitleView;
    private ImageView framePreviewView;
    private TextView previewTimestampBadge;
    private TrimRangeBarView trimRangeBar;
    private TextView startLabelView;
    private TextView endLabelView;
    private TextView durationLabelView;
    private ProgressBar progressBar;
    private TextView statusTextView;
    private Button createGifBtn;
    private Button cancelOrCloseBtn;
    private Bitmap currentPreviewBitmap;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        requestWindowFeature(Window.FEATURE_NO_TITLE);

        buildUi();
        handleIncomingIntent(getIntent());
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        cancelFlag.set(true);
        buildUi();
        handleIncomingIntent(intent);
    }

    @Override
    protected void onDestroy() {
        cancelFlag.set(true);
        VideoToGifConverter.releasePreviewRetriever();
        if (currentPreviewBitmap != null && !currentPreviewBitmap.isRecycled()) {
            currentPreviewBitmap.recycle();
            currentPreviewBitmap = null;
        }
        super.onDestroy();
    }

    private void buildUi() {
        boolean dark = MediaToolUtils.isDarkMode(this);

        int bgColor = dark ? Color.parseColor("#111318") : Color.parseColor("#F5F7FA");
        int cardColor = dark ? Color.parseColor("#1A1D24") : Color.parseColor("#FFFFFF");
        int surfaceColor = dark ? Color.parseColor("#222631") : Color.parseColor("#EEF2F6");
        int textPrimary = dark ? Color.parseColor("#F3F4F6") : Color.parseColor("#111827");
        int textSecondary = dark ? Color.parseColor("#9CA3AF") : Color.parseColor("#4B5563");
        int accentTeal = dark ? Color.parseColor("#2DD4BF") : Color.parseColor("#0D9488");

        Window window = getWindow();
        if (window != null) {
            window.setStatusBarColor(bgColor);
            window.setNavigationBarColor(bgColor);
        }

        FrameLayout outerRoot = new FrameLayout(this);
        outerRoot.setBackgroundColor(bgColor);
        int outerPad = dp(16f);
        outerRoot.setPadding(outerPad, outerPad, outerPad, outerPad);

        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        int cardPad = dp(18f);
        card.setPadding(cardPad, cardPad, cardPad, cardPad);

        GradientDrawable cardBg = new GradientDrawable();
        cardBg.setCornerRadius(dp(18f));
        cardBg.setColor(cardColor);
        card.setBackground(cardBg);

        // 1. Header Row
        LinearLayout headerRow = new LinearLayout(this);
        headerRow.setOrientation(LinearLayout.HORIZONTAL);
        headerRow.setGravity(Gravity.CENTER_VERTICAL);

        ImageView iconView = new ImageView(this);
        iconView.setImageResource(R.drawable.ic_drop_arrow);
        LinearLayout.LayoutParams iconLp = new LinearLayout.LayoutParams(dp(28f), dp(28f));
        iconLp.rightMargin = dp(10f);
        headerRow.addView(iconView, iconLp);

        LinearLayout titleCol = new LinearLayout(this);
        titleCol.setOrientation(LinearLayout.VERTICAL);

        TextView headerTitle = new TextView(this);
        headerTitle.setText("Convert video to GIF (<50mb)");
        headerTitle.setTextColor(textPrimary);
        headerTitle.setTypeface(Typeface.DEFAULT_BOLD);
        headerTitle.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f);
        titleCol.addView(headerTitle);

        fileTitleView = new TextView(this);
        fileTitleView.setText("Loading video...");
        fileTitleView.setTextColor(accentTeal);
        fileTitleView.setTypeface(Typeface.DEFAULT_BOLD);
        fileTitleView.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11.5f);
        fileTitleView.setSingleLine(true);
        fileTitleView.setEllipsize(TextUtils.TruncateAt.MIDDLE);
        titleCol.addView(fileTitleView);

        headerRow.addView(titleCol, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));

        TextView closeX = new TextView(this);
        closeX.setText("✕");
        closeX.setTextColor(textSecondary);
        closeX.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f);
        closeX.setPadding(dp(10f), dp(4f), dp(4f), dp(4f));
        closeX.setOnClickListener(v -> {
            cancelFlag.set(true);
            finish();
        });
        headerRow.addView(closeX);
        card.addView(headerRow);

        // 2. Live Frame Preview Box
        FrameLayout previewContainer = new FrameLayout(this);
        GradientDrawable previewBg = new GradientDrawable();
        previewBg.setCornerRadius(dp(14f));
        previewBg.setColor(surfaceColor);
        previewContainer.setBackground(previewBg);

        framePreviewView = new ImageView(this);
        framePreviewView.setScaleType(ImageView.ScaleType.FIT_CENTER);
        FrameLayout.LayoutParams imgLp = new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                dp(170f),
                Gravity.CENTER
        );
        int imgPad = dp(8f);
        framePreviewView.setPadding(imgPad, imgPad, imgPad, imgPad);
        previewContainer.addView(framePreviewView, imgLp);

        previewTimestampBadge = new TextView(this);
        previewTimestampBadge.setText("Preview: 0:00.0");
        previewTimestampBadge.setTextColor(Color.WHITE);
        previewTimestampBadge.setTypeface(Typeface.create(Typeface.MONOSPACE, Typeface.BOLD));
        previewTimestampBadge.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f);
        previewTimestampBadge.setPadding(dp(10f), dp(4f), dp(10f), dp(4f));
        GradientDrawable tsBg = new GradientDrawable();
        tsBg.setCornerRadius(dp(8f));
        tsBg.setColor(Color.parseColor("#B8111318"));
        previewTimestampBadge.setBackground(tsBg);
        FrameLayout.LayoutParams tsLp = new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT,
                FrameLayout.LayoutParams.WRAP_CONTENT,
                Gravity.BOTTOM | Gravity.RIGHT
        );
        tsLp.rightMargin = dp(10f);
        tsLp.bottomMargin = dp(10f);
        previewContainer.addView(previewTimestampBadge, tsLp);

        LinearLayout.LayoutParams prevBoxLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
        );
        prevBoxLp.topMargin = dp(12f);
        card.addView(previewContainer, prevBoxLp);

        // 3. Start / End Trim Range Section
        LinearLayout trimBox = new LinearLayout(this);
        trimBox.setOrientation(LinearLayout.VERTICAL);
        int trimPad = dp(14f);
        trimBox.setPadding(trimPad, dp(10f), trimPad, dp(12f));
        GradientDrawable trimBg = new GradientDrawable();
        trimBg.setCornerRadius(dp(14f));
        trimBg.setColor(surfaceColor);
        trimBox.setBackground(trimBg);

        LinearLayout labelsRow = new LinearLayout(this);
        labelsRow.setOrientation(LinearLayout.HORIZONTAL);
        labelsRow.setGravity(Gravity.CENTER_VERTICAL);

        startLabelView = new TextView(this);
        startLabelView.setText("Start: 0:00.0");
        startLabelView.setTextColor(textPrimary);
        startLabelView.setTypeface(Typeface.create(Typeface.MONOSPACE, Typeface.BOLD));
        startLabelView.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f);
        labelsRow.addView(startLabelView, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));

        durationLabelView = new TextView(this);
        durationLabelView.setText("GIF Length: 6.0s");
        durationLabelView.setTextColor(accentTeal);
        durationLabelView.setTypeface(Typeface.DEFAULT_BOLD);
        durationLabelView.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f);
        durationLabelView.setGravity(Gravity.CENTER);
        labelsRow.addView(durationLabelView, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));

        endLabelView = new TextView(this);
        endLabelView.setText("End: 0:06.0");
        endLabelView.setTextColor(textPrimary);
        endLabelView.setTypeface(Typeface.create(Typeface.MONOSPACE, Typeface.BOLD));
        endLabelView.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f);
        endLabelView.setGravity(Gravity.END);
        labelsRow.addView(endLabelView, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));

        trimBox.addView(labelsRow);

        trimRangeBar = new TrimRangeBarView(this, dark);
        LinearLayout.LayoutParams barLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                dp(42f)
        );
        barLp.topMargin = dp(6f);
        trimBox.addView(trimRangeBar, barLp);

        metaSubtitleView = new TextView(this);
        metaSubtitleView.setText("Drag the left handle for Start and right handle for End · Auto-optimized under 50MB");
        metaSubtitleView.setTextColor(textSecondary);
        metaSubtitleView.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11.5f);
        metaSubtitleView.setGravity(Gravity.CENTER);
        metaSubtitleView.setPadding(0, dp(2f), 0, 0);
        trimBox.addView(metaSubtitleView);

        LinearLayout.LayoutParams trimBoxLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
        );
        trimBoxLp.topMargin = dp(10f);
        card.addView(trimBox, trimBoxLp);

        // 4. Progress Bar + Status Text
        progressBar = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
        progressBar.setMax(100);
        progressBar.setProgress(0);
        progressBar.setProgressDrawable(MediaToolUtils.buildSmoothProgressDrawable(this, dark, accentTeal));
        LinearLayout.LayoutParams progLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                dp(10f)
        );
        progLp.topMargin = dp(12f);
        card.addView(progressBar, progLp);

        statusTextView = new TextView(this);
        statusTextView.setText("Select your Start & End times above, then click 'Create GIF (<50mb)'.");
        statusTextView.setTextColor(textSecondary);
        statusTextView.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12.5f);
        statusTextView.setPadding(0, dp(8f), 0, 0);
        card.addView(statusTextView);

        // 5. Bottom Action Buttons Row
        LinearLayout btnRow = new LinearLayout(this);
        btnRow.setOrientation(LinearLayout.HORIZONTAL);
        btnRow.setGravity(Gravity.END | Gravity.CENTER_VERTICAL);
        LinearLayout.LayoutParams btnRowLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
        );
        btnRowLp.topMargin = dp(14f);

        createGifBtn = MediaToolUtils.createFlatButton(this, dark, "Create GIF (<50mb)", true);
        createGifBtn.setOnClickListener(v -> startGifConversion());
        LinearLayout.LayoutParams createLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                dp(38f)
        );
        createLp.rightMargin = dp(8f);
        btnRow.addView(createGifBtn, createLp);

        cancelOrCloseBtn = MediaToolUtils.createFlatButton(this, dark, "Cancel", false);
        cancelOrCloseBtn.setOnClickListener(v -> {
            cancelFlag.set(true);
            finish();
        });
        btnRow.addView(cancelOrCloseBtn, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                dp(38f)
        ));

        card.addView(btnRow, btnRowLp);

        FrameLayout.LayoutParams cardLp = new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.WRAP_CONTENT,
                Gravity.CENTER
        );
        outerRoot.addView(card, cardLp);

        setContentView(outerRoot);
    }

    private void handleIncomingIntent(Intent intent) {
        sourceVideoUri = MediaToolUtils.extractMediaUri(intent);
        if (sourceVideoUri == null) {
            fileTitleView.setText("No video file received");
            statusTextView.setText("Right-click a video file in Files → Open with → 'Convert video to GIF (<50mb)'.");
            createGifBtn.setVisibility(View.GONE);
            cancelOrCloseBtn.setText("Close");
            return;
        }

        cancelFlag.set(false);
        isEncoding = false;
        isFinished = false;

        videoInfo = VideoCompressor.inspectVideo(this, sourceVideoUri);
        totalDurationMs = Math.max(500L, videoInfo.durationMs);
        startMs = 0L;
        endMs = Math.min(totalDurationMs, 8_000L);
        if (endMs <= startMs) {
            endMs = totalDurationMs;
        }

        fileTitleView.setText(videoInfo.displayName + " (" + formatTimeMs(totalDurationMs) + ")");
        updateTrimLabels();
        trimRangeBar.invalidate();
        requestPreviewFrame(startMs, true);
    }

    private void updateTrimLabels() {
        startLabelView.setText("Start: " + formatTimeMs(startMs));
        endLabelView.setText("End: " + formatTimeMs(endMs));
        double lenSec = Math.max(0.1, (endMs - startMs) / 1000.0);
        durationLabelView.setText(String.format(Locale.US, "GIF Length: %.1fs", lenSec));
    }

    private void requestPreviewFrame(final long timeMs, boolean force) {
        if (sourceVideoUri == null) return;
        long now = System.currentTimeMillis();
        if (!force && (now - lastPreviewRequestTimeMs < 45L)) {
            return;
        }
        lastPreviewRequestTimeMs = now;
        previewTimestampBadge.setText("Preview: " + formatTimeMs(timeMs));

        VideoToGifConverter.loadPreviewFrameAsync(this, sourceVideoUri, timeMs, bitmap -> {
            if (bitmap != null && !isFinishing()) {
                Bitmap old = currentPreviewBitmap;
                currentPreviewBitmap = bitmap;
                framePreviewView.setImageBitmap(bitmap);
                if (old != null && old != bitmap && !old.isRecycled()) {
                    old.recycle();
                }
            }
        });
    }

    private void startGifConversion() {
        if (isEncoding || sourceVideoUri == null || videoInfo == null) {
            return;
        }
        isEncoding = true;
        isFinished = false;
        cancelFlag.set(false);
        createGifBtn.setEnabled(false);
        createGifBtn.setAlpha(0.5f);
        progressBar.setProgress(2);
        statusTextView.setText("Starting offline GIF encoder...");

        VideoToGifConverter.convertVideoRangeToGifAsync(
                this,
                sourceVideoUri,
                videoInfo,
                startMs,
                endMs,
                cancelFlag,
                new VideoToGifConverter.GifCallback() {
                    @Override
                    public void onProgress(int percent, String statusText) {
                        progressBar.setProgress(percent);
                        statusTextView.setText(statusText);
                    }

                    @Override
                    public void onSuccess(VideoToGifConverter.GifResult result) {
                        isEncoding = false;
                        isFinished = true;
                        boolean dark = MediaToolUtils.isDarkMode(VideoToGifActivity.this);
                        progressBar.setProgress(100);

                        String finalSizeStr = MediaToolUtils.formatBytes(result.gifBytes);
                        statusTextView.setTypeface(Typeface.DEFAULT_BOLD);
                        statusTextView.setTextColor(dark ? Color.parseColor("#2DD4BF") : Color.parseColor("#0D9488"));
                        statusTextView.setText("✓ File done! Saved to Downloads/" + result.savedFileName
                                + " (" + finalSizeStr + " · " + result.frameCount + " frames)");

                        createGifBtn.setVisibility(View.GONE);
                        cancelOrCloseBtn.setText("Done");
                        MediaToolUtils.updateButtonAppearance(VideoToGifActivity.this, cancelOrCloseBtn, dark, true);

                        TopBarDropService.lastActionMessage =
                                "File done: Downloads/" + result.savedFileName + " (" + finalSizeStr + ")";
                    }

                    @Override
                    public void onError(String errorMessage) {
                        isEncoding = false;
                        if (cancelFlag.get()) {
                            return;
                        }
                        createGifBtn.setEnabled(true);
                        createGifBtn.setAlpha(1.0f);
                        statusTextView.setText("GIF conversion failed: " + errorMessage);
                    }
                }
        );
    }

    private static String formatTimeMs(long ms) {
        long clamped = Math.max(0L, ms);
        long minutes = clamped / 60_000L;
        long seconds = (clamped % 60_000L) / 1000L;
        long tenths = (clamped % 1000L) / 100L;
        return String.format(Locale.US, "%d:%02d.%d", minutes, seconds, tenths);
    }

    private int dp(float value) {
        return MediaToolUtils.dpToPx(this, value);
    }

    /**
     * Custom borderless dual-handle timeline scrubber for selecting GIF Start and End times.
     */
    private final class TrimRangeBarView extends View {
        private static final int DRAG_NONE = 0;
        private static final int DRAG_START = 1;
        private static final int DRAG_END = 2;

        private final Paint trackPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint activeRangePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint thumbPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint thumbInnerPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final RectF rectF = new RectF();
        private int dragMode = DRAG_NONE;

        TrimRangeBarView(Context context, boolean dark) {
            super(context);
            trackPaint.setColor(dark ? Color.parseColor("#14171F") : Color.parseColor("#CBD5E1"));
            thumbPaint.setColor(dark ? Color.parseColor("#2DD4BF") : Color.parseColor("#0D9488"));
            thumbInnerPaint.setColor(dark ? Color.parseColor("#111318") : Color.WHITE);
        }

        @Override
        protected void onDraw(Canvas canvas) {
            super.onDraw(canvas);
            int w = getWidth();
            int h = getHeight();
            if (w <= 0 || h <= 0) return;

            float padH = dp(14f);
            float usableW = Math.max(1f, w - 2f * padH);
            float cy = h / 2f;
            float trackHalfH = dp(4.5f);

            rectF.set(padH, cy - trackHalfH, w - padH, cy + trackHalfH);
            canvas.drawRoundRect(rectF, trackHalfH, trackHalfH, trackPaint);

            float startFrac = (totalDurationMs > 0) ? ((float) startMs / (float) totalDurationMs) : 0f;
            float endFrac = (totalDurationMs > 0) ? ((float) endMs / (float) totalDurationMs) : 1f;
            startFrac = Math.max(0f, Math.min(1f, startFrac));
            endFrac = Math.max(startFrac, Math.min(1f, endFrac));

            float startX = padH + startFrac * usableW;
            float endX = padH + endFrac * usableW;

            activeRangePaint.setShader(new LinearGradient(
                    startX, cy, endX, cy,
                    new int[]{
                            Color.parseColor("#A3E635"),
                            Color.parseColor("#2DD4BF"),
                            Color.parseColor("#0D9488")
                    },
                    new float[]{0f, 0.5f, 1f},
                    Shader.TileMode.CLAMP
            ));
            rectF.set(startX, cy - trackHalfH, endX, cy + trackHalfH);
            canvas.drawRoundRect(rectF, trackHalfH, trackHalfH, activeRangePaint);

            float thumbR = dp(10.5f);
            float innerR = dp(4f);

            canvas.drawCircle(startX, cy, thumbR, thumbPaint);
            canvas.drawCircle(startX, cy, innerR, thumbInnerPaint);

            canvas.drawCircle(endX, cy, thumbR, thumbPaint);
            canvas.drawCircle(endX, cy, innerR, thumbInnerPaint);
        }

        @Override
        public boolean onTouchEvent(MotionEvent event) {
            if (isEncoding || isFinished || totalDurationMs <= 0) {
                return false;
            }
            int w = getWidth();
            float padH = dp(14f);
            float usableW = Math.max(1f, w - 2f * padH);
            float x = event.getX();
            float frac = Math.max(0f, Math.min(1f, (x - padH) / usableW));
            long touchedMs = Math.round(frac * totalDurationMs);
            long minGapMs = Math.min(200L, totalDurationMs / 4L);

            switch (event.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    float startX = padH + ((float) startMs / (float) totalDurationMs) * usableW;
                    float endX = padH + ((float) endMs / (float) totalDurationMs) * usableW;
                    if (Math.abs(x - startX) <= Math.abs(x - endX)) {
                        dragMode = DRAG_START;
                        startMs = Math.min(touchedMs, endMs - minGapMs);
                        startMs = Math.max(0L, startMs);
                        requestPreviewFrame(startMs, true);
                    } else {
                        dragMode = DRAG_END;
                        endMs = Math.max(touchedMs, startMs + minGapMs);
                        endMs = Math.min(totalDurationMs, endMs);
                        requestPreviewFrame(endMs, true);
                    }
                    if (getParent() != null) {
                        getParent().requestDisallowInterceptTouchEvent(true);
                    }
                    updateTrimLabels();
                    invalidate();
                    return true;

                case MotionEvent.ACTION_MOVE:
                    if (dragMode == DRAG_START) {
                        startMs = Math.max(0L, Math.min(touchedMs, endMs - minGapMs));
                        requestPreviewFrame(startMs, false);
                    } else if (dragMode == DRAG_END) {
                        endMs = Math.min(totalDurationMs, Math.max(touchedMs, startMs + minGapMs));
                        requestPreviewFrame(endMs, false);
                    }
                    updateTrimLabels();
                    invalidate();
                    return true;

                case MotionEvent.ACTION_UP:
                case MotionEvent.ACTION_CANCEL:
                    if (dragMode == DRAG_START) {
                        requestPreviewFrame(startMs, true);
                    } else if (dragMode == DRAG_END) {
                        requestPreviewFrame(endMs, true);
                    }
                    dragMode = DRAG_NONE;
                    return true;
            }
            return false;
        }
    }
}
