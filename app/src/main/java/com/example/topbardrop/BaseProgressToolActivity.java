package com.example.topbardrop;

import android.app.Activity;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.Window;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;

/**
 * Shared borderless floating desktop dialog activity for BookTools' offline media tools
 * (Video Compressor, Image Compressor, and 2x HD Image Upscaler).
 * Consolidates all window styling, progress bar, info box, and lifecycle management in one place.
 */
public abstract class BaseProgressToolActivity extends Activity {

    protected final Handler mainHandler = new Handler(Looper.getMainLooper());
    protected TextView titleTextView;
    protected TextView subtitleTextView;
    protected TextView fileNameTextView;
    protected TextView statusTextView;
    protected ProgressBar progressBar;
    protected Button cancelOrCloseBtn;
    protected boolean dark;

    protected abstract String getInitialTitle();

    protected abstract String getInitialSubtitle();

    protected abstract String getInitialFilePlaceholder();

    protected abstract String getInitialStatusText();

    protected abstract String getMissingUriErrorMessage();

    protected abstract void onStartToolForUri(Uri mediaUri);

    protected abstract void onCancelActiveTool();

    protected abstract boolean isToolCompletedOrFailed();

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        requestWindowFeature(Window.FEATURE_NO_TITLE);

        Window window = getWindow();
        if (window != null) {
            window.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
            window.addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND);
            WindowManager.LayoutParams params = window.getAttributes();
            params.dimAmount = 0.35f;
            window.setAttributes(params);
        }

        dark = MediaToolUtils.isDarkMode(this);
        buildDialogUi();

        Uri mediaUri = MediaToolUtils.extractMediaUri(getIntent());
        if (mediaUri == null) {
            showErrorState(getMissingUriErrorMessage());
            return;
        }
        onStartToolForUri(mediaUri);
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        Uri mediaUri = MediaToolUtils.extractMediaUri(intent);
        if (mediaUri != null) {
            onCancelActiveTool();
            onStartToolForUri(mediaUri);
        }
    }

    @Override
    protected void onDestroy() {
        onCancelActiveTool();
        super.onDestroy();
    }

    private void buildDialogUi() {
        int cardBgColor = dark ? Color.parseColor("#181B22") : Color.parseColor("#FFFFFF");
        int innerBgColor = dark ? Color.parseColor("#12151B") : Color.parseColor("#F3F6FA");
        int textPrimary = dark ? Color.parseColor("#F5F7FA") : Color.parseColor("#111827");
        int textSecondary = dark ? Color.parseColor("#B4BCC8") : Color.parseColor("#4B5563");
        int accentTeal = dark ? Color.parseColor("#2DD4BF") : Color.parseColor("#0D9488");

        FrameLayout outerWrap = new FrameLayout(this);
        int outerPad = dp(18f);
        outerWrap.setPadding(outerPad, outerPad, outerPad, outerPad);

        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        int pad = dp(22f);
        card.setPadding(pad, pad, pad, pad);

        GradientDrawable cardBg = new GradientDrawable();
        cardBg.setCornerRadius(dp(22f));
        cardBg.setColor(cardBgColor);
        card.setBackground(cardBg);

        // Header Row: Icon + Title
        LinearLayout headerRow = new LinearLayout(this);
        headerRow.setOrientation(LinearLayout.HORIZONTAL);
        headerRow.setGravity(Gravity.CENTER_VERTICAL);

        ImageView icon = new ImageView(this);
        icon.setImageResource(R.drawable.ic_drop_arrow);
        LinearLayout.LayoutParams iconLp = new LinearLayout.LayoutParams(dp(34f), dp(34f));
        iconLp.rightMargin = dp(12f);
        headerRow.addView(icon, iconLp);

        LinearLayout titleCol = new LinearLayout(this);
        titleCol.setOrientation(LinearLayout.VERTICAL);

        titleTextView = new TextView(this);
        titleTextView.setText(getInitialTitle());
        titleTextView.setTextColor(textPrimary);
        titleTextView.setTypeface(Typeface.DEFAULT_BOLD);
        titleTextView.setTextSize(TypedValue.COMPLEX_UNIT_SP, 17f);
        titleCol.addView(titleTextView);

        subtitleTextView = new TextView(this);
        subtitleTextView.setText(getInitialSubtitle());
        subtitleTextView.setTextColor(textSecondary);
        subtitleTextView.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f);
        titleCol.addView(subtitleTextView);

        headerRow.addView(titleCol, new LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        card.addView(headerRow);

        // File + Progress Info Box (no outline border)
        LinearLayout infoBox = new LinearLayout(this);
        infoBox.setOrientation(LinearLayout.VERTICAL);
        int boxPad = dp(16f);
        infoBox.setPadding(boxPad, boxPad, boxPad, boxPad);
        GradientDrawable infoBg = new GradientDrawable();
        infoBg.setCornerRadius(dp(14f));
        infoBg.setColor(innerBgColor);
        infoBox.setBackground(infoBg);

        LinearLayout.LayoutParams infoLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
        );
        infoLp.topMargin = dp(16f);

        fileNameTextView = new TextView(this);
        fileNameTextView.setText(getInitialFilePlaceholder());
        fileNameTextView.setTextColor(textPrimary);
        fileNameTextView.setTypeface(Typeface.DEFAULT_BOLD);
        fileNameTextView.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13.5f);
        infoBox.addView(fileNameTextView);

        progressBar = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
        progressBar.setMax(100);
        progressBar.setProgress(0);
        progressBar.setProgressDrawable(MediaToolUtils.buildSmoothProgressDrawable(this, dark, accentTeal));
        LinearLayout.LayoutParams pbLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                dp(10f)
        );
        pbLp.topMargin = dp(12f);
        pbLp.bottomMargin = dp(10f);
        infoBox.addView(progressBar, pbLp);

        statusTextView = new TextView(this);
        statusTextView.setText(getInitialStatusText());
        statusTextView.setTextColor(textSecondary);
        statusTextView.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f);
        infoBox.addView(statusTextView);

        card.addView(infoBox, infoLp);

        // Bottom Button Row (Cancel / Done)
        LinearLayout actionsRow = new LinearLayout(this);
        actionsRow.setOrientation(LinearLayout.HORIZONTAL);
        actionsRow.setGravity(Gravity.END | Gravity.CENTER_VERTICAL);
        LinearLayout.LayoutParams actionsLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
        );
        actionsLp.topMargin = dp(16f);

        cancelOrCloseBtn = MediaToolUtils.createFlatButton(this, dark, "Cancel", false);
        cancelOrCloseBtn.setOnClickListener(v -> {
            if (!isToolCompletedOrFailed()) {
                onCancelActiveTool();
            }
            finish();
        });
        actionsRow.addView(cancelOrCloseBtn, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, dp(38f)));

        card.addView(actionsRow, actionsLp);

        FrameLayout.LayoutParams cardLp = new FrameLayout.LayoutParams(
                dp(420f),
                FrameLayout.LayoutParams.WRAP_CONTENT,
                Gravity.CENTER
        );
        outerWrap.addView(card, cardLp);
        setContentView(outerWrap);
    }

    protected void showDoneButton() {
        cancelOrCloseBtn.setText("Done");
        MediaToolUtils.updateButtonAppearance(this, cancelOrCloseBtn, dark, true);
    }

    protected void showErrorState(String message) {
        titleTextView.setText("⚠ Failed");
        subtitleTextView.setText("Could not process media file");
        statusTextView.setText(message);
        cancelOrCloseBtn.setText("Close");
    }

    protected int dp(float value) {
        return MediaToolUtils.dpToPx(this, value);
    }
}
