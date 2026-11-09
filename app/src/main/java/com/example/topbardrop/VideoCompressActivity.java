package com.example.topbardrop;

import android.app.Activity;
import android.content.Intent;
import android.content.res.Configuration;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.ClipDrawable;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.LayerDrawable;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.text.TextUtils;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.Window;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;

import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Floating desktop dialog activity launched from the Files app right-click menu:
 * Right-Click video -> Open with (or Share) -> "Compress video (~50mb)".
 * Runs 100% offline hardware-accelerated video compression and saves the output MP4 to Downloads.
 */
public class VideoCompressActivity extends Activity {

    private final AtomicBoolean cancelFlag = new AtomicBoolean(false);

    private TextView fileTitleView;
    private TextView metaSubtitleView;
    private ProgressBar progressBar;
    private TextView statusTextView;
    private Button cancelOrCloseBtn;

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
        super.onDestroy();
    }

    private boolean isDarkMode() {
        int nightModeFlags = getResources().getConfiguration().uiMode & Configuration.UI_MODE_NIGHT_MASK;
        return nightModeFlags == Configuration.UI_MODE_NIGHT_YES;
    }

    private void buildUi() {
        boolean dark = isDarkMode();

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
        int outerPad = dpToPx(18f);
        outerRoot.setPadding(outerPad, outerPad, outerPad, outerPad);

        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        int cardPad = dpToPx(20f);
        card.setPadding(cardPad, cardPad, cardPad, cardPad);

        GradientDrawable cardBg = new GradientDrawable();
        cardBg.setCornerRadius(dpToPx(18f));
        cardBg.setColor(cardColor);
        card.setBackground(cardBg);

        // 1. Header Row
        LinearLayout headerRow = new LinearLayout(this);
        headerRow.setOrientation(LinearLayout.HORIZONTAL);
        headerRow.setGravity(Gravity.CENTER_VERTICAL);

        ImageView iconView = new ImageView(this);
        iconView.setImageResource(R.drawable.ic_drop_arrow);
        LinearLayout.LayoutParams iconLp = new LinearLayout.LayoutParams(dpToPx(28f), dpToPx(28f));
        iconLp.rightMargin = dpToPx(10f);
        headerRow.addView(iconView, iconLp);

        LinearLayout titleCol = new LinearLayout(this);
        titleCol.setOrientation(LinearLayout.VERTICAL);

        TextView headerTitle = new TextView(this);
        headerTitle.setText("Compress video (~50mb)");
        headerTitle.setTextColor(textPrimary);
        headerTitle.setTypeface(Typeface.DEFAULT_BOLD);
        headerTitle.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f);
        titleCol.addView(headerTitle);

        TextView headerSub = new TextView(this);
        headerSub.setText("BookTools · 100% Offline Hardware Encoder");
        headerSub.setTextColor(accentTeal);
        headerSub.setTypeface(Typeface.DEFAULT_BOLD);
        headerSub.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11.5f);
        titleCol.addView(headerSub);

        headerRow.addView(titleCol, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));

        TextView closeX = new TextView(this);
        closeX.setText("✕");
        closeX.setTextColor(textSecondary);
        closeX.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f);
        closeX.setPadding(dpToPx(10f), dpToPx(4f), dpToPx(4f), dpToPx(4f));
        closeX.setOnClickListener(v -> {
            cancelFlag.set(true);
            finish();
        });
        headerRow.addView(closeX);
        card.addView(headerRow);

        // 2. Video Info Box
        LinearLayout infoBox = new LinearLayout(this);
        infoBox.setOrientation(LinearLayout.VERTICAL);
        int infoPad = dpToPx(14f);
        infoBox.setPadding(infoPad, dpToPx(12f), infoPad, dpToPx(12f));
        GradientDrawable infoBg = new GradientDrawable();
        infoBg.setCornerRadius(dpToPx(12f));
        infoBg.setColor(surfaceColor);
        infoBox.setBackground(infoBg);

        fileTitleView = new TextView(this);
        fileTitleView.setText("Inspecting video...");
        fileTitleView.setTextColor(textPrimary);
        fileTitleView.setTypeface(Typeface.DEFAULT_BOLD);
        fileTitleView.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13.5f);
        fileTitleView.setSingleLine(true);
        fileTitleView.setEllipsize(TextUtils.TruncateAt.MIDDLE);
        infoBox.addView(fileTitleView);

        metaSubtitleView = new TextView(this);
        metaSubtitleView.setText("Calculating optimal bitrate for ~50MB target...");
        metaSubtitleView.setTextColor(textSecondary);
        metaSubtitleView.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f);
        metaSubtitleView.setPadding(0, dpToPx(4f), 0, 0);
        infoBox.addView(metaSubtitleView);

        LinearLayout.LayoutParams infoLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
        );
        infoLp.topMargin = dpToPx(14f);
        card.addView(infoBox, infoLp);

        // 3. Progress Bar + Status Text
        progressBar = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
        progressBar.setMax(100);
        progressBar.setProgress(0);
        progressBar.setProgressDrawable(buildSmoothProgressDrawable(dark, accentTeal));
        LinearLayout.LayoutParams progLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                dpToPx(10f)
        );
        progLp.topMargin = dpToPx(16f);
        card.addView(progressBar, progLp);

        statusTextView = new TextView(this);
        statusTextView.setText("Starting offline hardware encoder...");
        statusTextView.setTextColor(textSecondary);
        statusTextView.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f);
        statusTextView.setPadding(0, dpToPx(10f), 0, 0);
        card.addView(statusTextView);

        // 4. Bottom Action Button Row
        LinearLayout btnRow = new LinearLayout(this);
        btnRow.setOrientation(LinearLayout.HORIZONTAL);
        btnRow.setGravity(Gravity.END | Gravity.CENTER_VERTICAL);
        LinearLayout.LayoutParams btnRowLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
        );
        btnRowLp.topMargin = dpToPx(16f);

        cancelOrCloseBtn = createFlatButton(dark, "Cancel", false);
        cancelOrCloseBtn.setOnClickListener(v -> {
            cancelFlag.set(true);
            finish();
        });
        btnRow.addView(cancelOrCloseBtn, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                dpToPx(38f)
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

    private Drawable buildSmoothProgressDrawable(boolean dark, int accentTeal) {
        GradientDrawable track = new GradientDrawable();
        track.setCornerRadius(dpToPx(6f));
        track.setColor(dark ? Color.parseColor("#262B36") : Color.parseColor("#E2E8F0"));

        GradientDrawable progressFill = new GradientDrawable(
                GradientDrawable.Orientation.LEFT_RIGHT,
                new int[]{
                        Color.parseColor("#A3E635"),
                        Color.parseColor("#2DD4BF"),
                        accentTeal
                }
        );
        progressFill.setCornerRadius(dpToPx(6f));
        ClipDrawable clip = new ClipDrawable(progressFill, Gravity.LEFT, ClipDrawable.HORIZONTAL);

        LayerDrawable layers = new LayerDrawable(new Drawable[]{track, clip});
        layers.setId(0, android.R.id.background);
        layers.setId(1, android.R.id.progress);
        return layers;
    }

    private Button createFlatButton(boolean dark, String text, boolean primary) {
        Button btn = new Button(this);
        btn.setText(text);
        btn.setAllCaps(false);
        btn.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12.5f);
        btn.setTypeface(Typeface.DEFAULT_BOLD);
        btn.setGravity(Gravity.CENTER);
        btn.setPadding(dpToPx(18f), 0, dpToPx(18f), 0);
        btn.setMinHeight(0);
        btn.setMinimumHeight(0);
        btn.setMinWidth(0);
        btn.setMinimumWidth(0);
        btn.setStateListAnimator(null);
        btn.setElevation(0f);

        updateButtonAppearance(btn, dark, primary);
        return btn;
    }

    private void updateButtonAppearance(Button btn, boolean dark, boolean primary) {
        GradientDrawable bg = new GradientDrawable();
        bg.setCornerRadius(dpToPx(10f));
        if (primary) {
            bg.setColor(dark ? Color.parseColor("#2DD4BF") : Color.parseColor("#0D9488"));
            btn.setTextColor(dark ? Color.parseColor("#0B1312") : Color.WHITE);
        } else {
            bg.setColor(dark ? Color.parseColor("#252A34") : Color.parseColor("#E5E7EB"));
            btn.setTextColor(dark ? Color.parseColor("#E5E7EB") : Color.parseColor("#1F2937"));
        }
        btn.setBackground(bg);
    }

    private void handleIncomingIntent(Intent intent) {
        Uri videoUri = extractVideoUri(intent);
        if (videoUri == null) {
            fileTitleView.setText("No video file received");
            metaSubtitleView.setText("Right-click a video file in Files → Open with → 'Compress video (~50mb)'.");
            statusTextView.setText("Ready.");
            cancelOrCloseBtn.setText("Close");
            return;
        }

        cancelFlag.set(false);

        VideoCompressor.compressVideoAsync(this, videoUri, cancelFlag, new VideoCompressor.CompressCallback() {
            @Override
            public void onMetadataReady(VideoCompressor.VideoInfo info, int targetWidth, int targetHeight, int targetBitrateKbps) {
                fileTitleView.setText(info.displayName);
                String origSizeStr = info.originalBytes > 0 ? VideoCompressor.formatBytes(info.originalBytes) : "Video";
                long sec = Math.max(1L, info.durationMs / 1000L);
                String durStr = (sec >= 60) ? ((sec / 60) + "m " + (sec % 60) + "s") : (sec + "s");
                metaSubtitleView.setText(origSizeStr + " · " + durStr + " → " + targetWidth + "×" + targetHeight
                        + " (" + targetBitrateKbps + " kbps · ~50MB target)");
            }

            @Override
            public void onProgress(int percent, String statusText) {
                progressBar.setProgress(percent);
                statusTextView.setText(statusText);
            }

            @Override
            public void onSuccess(VideoCompressor.CompressResult result) {
                boolean dark = isDarkMode();
                progressBar.setProgress(100);

                String origStr = result.originalBytes > 0
                        ? (VideoCompressor.formatBytes(result.originalBytes) + " → ")
                        : "";
                String finalStr = VideoCompressor.formatBytes(result.compressedBytes);

                statusTextView.setTypeface(Typeface.DEFAULT_BOLD);
                statusTextView.setTextColor(dark ? Color.parseColor("#2DD4BF") : Color.parseColor("#0D9488"));
                statusTextView.setText("✓ File done! Saved to Downloads/" + result.savedFileName
                        + " (" + origStr + finalStr + ")");

                cancelOrCloseBtn.setText("Done");
                updateButtonAppearance(cancelOrCloseBtn, dark, true);

                TopBarDropService.lastActionMessage =
                        "File done: Downloads/" + result.savedFileName + " (" + finalStr + ")";
            }

            @Override
            public void onError(String errorMessage) {
                if (cancelFlag.get()) {
                    return;
                }
                statusTextView.setText("Compression failed: " + errorMessage);
                cancelOrCloseBtn.setText("Close");
            }
        });
    }

    private Uri extractVideoUri(Intent intent) {
        if (intent == null) return null;
        String action = intent.getAction();
        if (Intent.ACTION_VIEW.equals(action) && intent.getData() != null) {
            return intent.getData();
        }
        if (Intent.ACTION_SEND.equals(action)) {
            Uri streamUri;
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                streamUri = intent.getParcelableExtra(Intent.EXTRA_STREAM, Uri.class);
            } else {
                //noinspection deprecation
                streamUri = intent.getParcelableExtra(Intent.EXTRA_STREAM);
            }
            if (streamUri != null) {
                return streamUri;
            }
        }
        if (intent.getClipData() != null && intent.getClipData().getItemCount() > 0) {
            Uri clipUri = intent.getClipData().getItemAt(0).getUri();
            if (clipUri != null) {
                return clipUri;
            }
        }
        return intent.getData();
    }

    private int dpToPx(float dp) {
        return Math.round(TypedValue.applyDimension(
                TypedValue.COMPLEX_UNIT_DIP,
                dp,
                getResources().getDisplayMetrics()
        ));
    }
}
