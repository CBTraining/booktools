package com.example.topbardrop;

import android.app.Activity;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Bundle;
import android.text.TextUtils;
import android.util.TypedValue;
import android.view.Gravity;
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
 * - "Compress image" (ImageCompressActivity)
 * - "Upscale image (2x HD)" (ImageUpscaleActivity subclass)
 * Runs 100% offline, saves the output to Downloads, and displays "✓ File done!".
 */
public class ImageCompressActivity extends Activity {

    private final AtomicBoolean cancelFlag = new AtomicBoolean(false);

    private TextView fileTitleView;
    private TextView metaSubtitleView;
    private ProgressBar progressBar;
    private TextView statusTextView;
    private Button cancelOrCloseBtn;

    protected boolean isUpscaleMode() {
        return false;
    }

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

    private void buildUi() {
        boolean dark = MediaToolUtils.isDarkMode(this);
        boolean upscale = isUpscaleMode();

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
        int outerPad = dp(18f);
        outerRoot.setPadding(outerPad, outerPad, outerPad, outerPad);

        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        int cardPad = dp(20f);
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
        headerTitle.setText(upscale ? "Upscale image (2x HD)" : "Compress image");
        headerTitle.setTextColor(textPrimary);
        headerTitle.setTypeface(Typeface.DEFAULT_BOLD);
        headerTitle.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f);
        titleCol.addView(headerTitle);

        TextView headerSub = new TextView(this);
        headerSub.setText(upscale
                ? "BookTools · 100% Offline 2x Detail-Enhancing Upscaler"
                : "BookTools · 100% Offline Smart Image Optimizer");
        headerSub.setTextColor(accentTeal);
        headerSub.setTypeface(Typeface.DEFAULT_BOLD);
        headerSub.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11.5f);
        titleCol.addView(headerSub);

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

        // 2. Image Info Box
        LinearLayout infoBox = new LinearLayout(this);
        infoBox.setOrientation(LinearLayout.VERTICAL);
        int infoPad = dp(14f);
        infoBox.setPadding(infoPad, dp(12f), infoPad, dp(12f));
        GradientDrawable infoBg = new GradientDrawable();
        infoBg.setCornerRadius(dp(12f));
        infoBg.setColor(surfaceColor);
        infoBox.setBackground(infoBg);

        fileTitleView = new TextView(this);
        fileTitleView.setText("Inspecting image...");
        fileTitleView.setTextColor(textPrimary);
        fileTitleView.setTypeface(Typeface.DEFAULT_BOLD);
        fileTitleView.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13.5f);
        fileTitleView.setSingleLine(true);
        fileTitleView.setEllipsize(TextUtils.TruncateAt.MIDDLE);
        infoBox.addView(fileTitleView);

        metaSubtitleView = new TextView(this);
        metaSubtitleView.setText(upscale
                ? "Preparing 2x super-resolution & contrast-adaptive sharpening..."
                : "Analyzing dimensions & alpha channel for optimal compression...");
        metaSubtitleView.setTextColor(textSecondary);
        metaSubtitleView.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f);
        metaSubtitleView.setPadding(0, dp(4f), 0, 0);
        infoBox.addView(metaSubtitleView);

        LinearLayout.LayoutParams infoLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
        );
        infoLp.topMargin = dp(14f);
        card.addView(infoBox, infoLp);

        // 3. Progress Bar + Status Text
        progressBar = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
        progressBar.setMax(100);
        progressBar.setProgress(5);
        progressBar.setProgressDrawable(MediaToolUtils.buildSmoothProgressDrawable(this, dark, accentTeal));
        LinearLayout.LayoutParams progLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                dp(10f)
        );
        progLp.topMargin = dp(16f);
        card.addView(progressBar, progLp);

        statusTextView = new TextView(this);
        statusTextView.setText(upscale ? "Upscaling image 2x offline..." : "Compressing image offline...");
        statusTextView.setTextColor(textSecondary);
        statusTextView.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f);
        statusTextView.setPadding(0, dp(10f), 0, 0);
        card.addView(statusTextView);

        // 4. Bottom Action Button Row
        LinearLayout btnRow = new LinearLayout(this);
        btnRow.setOrientation(LinearLayout.HORIZONTAL);
        btnRow.setGravity(Gravity.END | Gravity.CENTER_VERTICAL);
        LinearLayout.LayoutParams btnRowLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
        );
        btnRowLp.topMargin = dp(16f);

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
        Uri imageUri = MediaToolUtils.extractMediaUri(intent);
        boolean upscale = isUpscaleMode();

        if (imageUri == null) {
            fileTitleView.setText("No image file received");
            metaSubtitleView.setText("Right-click an image file in Files → Open with → '"
                    + (upscale ? "Upscale image (2x HD)" : "Compress image") + "'.");
            statusTextView.setText("Ready.");
            cancelOrCloseBtn.setText("Close");
            return;
        }

        cancelFlag.set(false);

        ImageProcessor.ProcessCallback callback = new ImageProcessor.ProcessCallback() {
            @Override
            public void onFileInspected(String displayName, long originalBytes, int width, int height) {
                fileTitleView.setText(displayName);
                String sizeStr = originalBytes > 0 ? MediaToolUtils.formatBytes(originalBytes) : "Image";
                if (upscale) {
                    metaSubtitleView.setText(sizeStr + " · " + width + "×" + height + " → 2x HD Detail Upscale");
                } else {
                    metaSubtitleView.setText(sizeStr + " · " + width + "×" + height + " → Smart Offline Compression");
                }
            }

            @Override
            public void onProgress(int percent, String statusText) {
                progressBar.setProgress(percent);
                statusTextView.setText(statusText);
            }

            @Override
            public void onSuccess(ImageProcessor.ImageResult result) {
                boolean dark = MediaToolUtils.isDarkMode(ImageCompressActivity.this);
                progressBar.setProgress(100);

                String outSizeStr = MediaToolUtils.formatBytes(result.outputBytes);
                String summary;
                if (upscale) {
                    summary = result.srcWidth + "×" + result.srcHeight + " → "
                            + result.outWidth + "×" + result.outHeight + " · " + outSizeStr;
                } else {
                    String origStr = result.originalBytes > 0
                            ? (MediaToolUtils.formatBytes(result.originalBytes) + " → ")
                            : "";
                    summary = origStr + outSizeStr;
                }

                metaSubtitleView.setText(result.outWidth + "×" + result.outHeight + " · Saved in Downloads");
                statusTextView.setTypeface(Typeface.DEFAULT_BOLD);
                statusTextView.setTextColor(dark ? Color.parseColor("#2DD4BF") : Color.parseColor("#0D9488"));
                statusTextView.setText("✓ File done! Saved to Downloads/" + result.savedFileName
                        + " (" + summary + ")");

                cancelOrCloseBtn.setText("Done");
                MediaToolUtils.updateButtonAppearance(ImageCompressActivity.this, cancelOrCloseBtn, dark, true);

                TopBarDropService.lastActionMessage =
                        "File done: Downloads/" + result.savedFileName + " (" + summary + ")";
            }

            @Override
            public void onError(String errorMessage) {
                if (cancelFlag.get()) {
                    return;
                }
                statusTextView.setText((upscale ? "Upscale" : "Compression") + " failed: " + errorMessage);
                cancelOrCloseBtn.setText("Close");
            }
        };

        if (upscale) {
            ImageProcessor.upscaleImage2xAsync(this, imageUri, cancelFlag, callback);
        } else {
            ImageProcessor.compressImageAsync(this, imageUri, cancelFlag, callback);
        }
    }

    private int dp(float value) {
        return MediaToolUtils.dpToPx(this, value);
    }
}
