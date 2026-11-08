package com.example.topbardrop;

import android.accessibilityservice.AccessibilityServiceInfo;
import android.app.Activity;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.ComponentName;
import android.content.ContentValues;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Shader;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.provider.MediaStore;
import android.provider.Settings;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.accessibility.AccessibilityManager;
import android.widget.Button;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.io.OutputStream;
import java.util.List;

public class MainActivity extends Activity {

    public static volatile MainActivity current;

    private TextView statusBanner;
    private View setupCard;
    private TextView lastActionView;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        current = this;

        ScrollView scrollView = new ScrollView(this);
        scrollView.setFillViewport(true);
        scrollView.setBackgroundColor(Color.parseColor("#F1F3F4"));

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        int pad = dpToPx(22f);
        root.setPadding(pad, dpToPx(18f), pad, pad);
        scrollView.addView(root);

        // --- Header Row with Monochromatic Rounded Downward Arrow Icon ---
        LinearLayout titleRow = new LinearLayout(this);
        titleRow.setOrientation(LinearLayout.HORIZONTAL);
        titleRow.setGravity(Gravity.CENTER_VERTICAL);

        ImageView appIcon = new ImageView(this);
        appIcon.setImageResource(R.drawable.ic_drop_arrow);
        LinearLayout.LayoutParams iconLp = new LinearLayout.LayoutParams(dpToPx(34f), dpToPx(34f));
        iconLp.rightMargin = dpToPx(10f);
        titleRow.addView(appIcon, iconLp);

        LinearLayout titleTextCol = new LinearLayout(this);
        titleTextCol.setOrientation(LinearLayout.VERTICAL);

        TextView title = new TextView(this);
        title.setText("TopBarDrop");
        title.setTextSize(TypedValue.COMPLEX_UNIT_SP, 20f);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        title.setTextColor(Color.parseColor("#202124"));
        titleTextCol.addView(title);

        TextView subtitle = new TextView(this);
        subtitle.setText("Clipboard PNG Downloader & Screen Color Picker · No Top-Bar Clutter");
        subtitle.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f);
        subtitle.setTextColor(Color.parseColor("#5F6368"));
        titleTextCol.addView(subtitle);

        titleRow.addView(titleTextCol);
        root.addView(titleRow);

        // --- Status Banner ---
        statusBanner = new TextView(this);
        statusBanner.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13.5f);
        statusBanner.setTypeface(Typeface.DEFAULT_BOLD);
        statusBanner.setPadding(dpToPx(14f), dpToPx(11f), dpToPx(14f), dpToPx(11f));
        LinearLayout.LayoutParams bannerLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
        );
        bannerLp.topMargin = dpToPx(14f);
        root.addView(statusBanner, bannerLp);

        // --- Card 1: Streamlined 2-Step First-Time Setup (including "Allow restricted settings") ---
        setupCard = buildCardContainer();
        LinearLayout setupLayout = (LinearLayout) setupCard;

        TextView setupHeader = new TextView(this);
        setupHeader.setText("Quick 2-Step Setup (One-Time)");
        setupHeader.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f);
        setupHeader.setTypeface(Typeface.DEFAULT_BOLD);
        setupHeader.setTextColor(Color.parseColor("#202124"));
        setupLayout.addView(setupHeader);

        setupLayout.addView(buildStepRow(
                "1",
                "Unlock Restricted Settings",
                "First, tap 'Open Accessibility' and click TopBarDrop (if Android blocks it, that triggers the setting). Then tap 'Open App Info', click the ⋮ (three dots) in the top-right corner, and tap 'Allow restricted settings'.",
                "1. Open App Info (⋮)",
                new View.OnClickListener() {
                    @Override
                    public void onClick(View v) {
                        openAppInfo();
                    }
                }
        ));

        View divider = new View(this);
        divider.setBackgroundColor(Color.parseColor("#E8EAED"));
        LinearLayout.LayoutParams divLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dpToPx(1f));
        divLp.topMargin = dpToPx(10f);
        divLp.bottomMargin = dpToPx(10f);
        setupLayout.addView(divider, divLp);

        setupLayout.addView(buildStepRow(
                "2",
                "Turn On TopBarDrop Shortcut Service",
                "Once Restricted Settings are allowed, open Accessibility Settings, select 'TopBarDrop Shortcuts', and switch it ON.",
                "2. Open Accessibility",
                new View.OnClickListener() {
                    @Override
                    public void onClick(View v) {
                        openAccessibilitySettings();
                    }
                }
        ));

        LinearLayout.LayoutParams cardLp1 = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
        );
        cardLp1.topMargin = dpToPx(12f);
        root.addView(setupCard, cardLp1);

        // --- Card 2: Keyboard Shortcuts Reference ---
        LinearLayout shortcutsCard = buildCardContainer();

        TextView shortcutsHeader = new TextView(this);
        shortcutsHeader.setText("Global Keyboard Shortcuts");
        shortcutsHeader.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f);
        shortcutsHeader.setTypeface(Typeface.DEFAULT_BOLD);
        shortcutsHeader.setTextColor(Color.parseColor("#202124"));
        shortcutsCard.addView(shortcutsHeader);

        shortcutsCard.addView(buildShortcutItem(
                "Ctrl + Shift + D",
                "Clipboard Image → PNG Popup",
                "Converts any copied image (WebP, JPEG, PNG) into a lossless .png in Downloads and pops up an animated window. Click & drag the PNG into any app, or swipe / right-click to dismiss."
        ));

        shortcutsCard.addView(buildShortcutItem(
                "Ctrl + Shift + C",
                "Screen Color Picker (#HEX)",
                "Opens a precision crosshair and magnified pixel loupe (auto-flips above/below cursor near screen edges). Click any pixel to copy its #RRGGBB HEX code to the clipboard."
        ));

        LinearLayout.LayoutParams cardLp2 = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
        );
        cardLp2.topMargin = dpToPx(12f);
        root.addView(shortcutsCard, cardLp2);

        // --- Card 3: Built-in Test Bar ---
        LinearLayout testCard = buildCardContainer();

        TextView testHeader = new TextView(this);
        testHeader.setText("Try It Now");
        testHeader.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f);
        testHeader.setTypeface(Typeface.DEFAULT_BOLD);
        testHeader.setTextColor(Color.parseColor("#202124"));
        testCard.addView(testHeader);

        LinearLayout testRow = new LinearLayout(this);
        testRow.setOrientation(LinearLayout.HORIZONTAL);
        LinearLayout.LayoutParams testLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
        );
        testLp.topMargin = dpToPx(8f);

        Button copyWebpBtn = createActionButton("Copy Sample WebP", false);
        copyWebpBtn.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                copySampleWebpToClipboard();
            }
        });
        testRow.addView(copyWebpBtn, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));

        Button triggerPngBtn = createActionButton("Test PNG (Ctrl+Shift+D)", true);
        triggerPngBtn.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (TopBarDropService.instance != null) {
                    TopBarDropService.instance.triggerClipboardToPngPopup();
                } else {
                    Toast.makeText(MainActivity.this, "Complete Steps 1 & 2 above to turn on TopBarDrop first!", Toast.LENGTH_LONG).show();
                }
            }
        });
        LinearLayout.LayoutParams btnLp2 = new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
        btnLp2.leftMargin = dpToPx(8f);
        testRow.addView(triggerPngBtn, btnLp2);

        Button triggerColorBtn = createActionButton("Test Color (Ctrl+Shift+C)", true);
        triggerColorBtn.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (TopBarDropService.instance != null) {
                    TopBarDropService.instance.triggerColorPickerOverlay();
                } else {
                    Toast.makeText(MainActivity.this, "Complete Steps 1 & 2 above to turn on TopBarDrop first!", Toast.LENGTH_LONG).show();
                }
            }
        });
        LinearLayout.LayoutParams btnLp3 = new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
        btnLp3.leftMargin = dpToPx(8f);
        testRow.addView(triggerColorBtn, btnLp3);

        testCard.addView(testRow, testLp);

        lastActionView = new TextView(this);
        lastActionView.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12.5f);
        lastActionView.setTextColor(Color.parseColor("#1A73E8"));
        lastActionView.setPadding(0, dpToPx(10f), 0, 0);
        testCard.addView(lastActionView);

        LinearLayout.LayoutParams cardLp3 = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
        );
        cardLp3.topMargin = dpToPx(12f);
        root.addView(testCard, cardLp3);

        setContentView(scrollView);
        refreshUiState();
    }

    private LinearLayout buildCardContainer() {
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        int pad = dpToPx(16f);
        card.setPadding(pad, dpToPx(14f), pad, dpToPx(14f));
        GradientDrawable bg = new GradientDrawable();
        bg.setCornerRadius(dpToPx(14f));
        bg.setColor(Color.WHITE);
        bg.setStroke(dpToPx(1f), Color.parseColor("#DADCE0"));
        card.setBackground(bg);
        return card;
    }

    private View buildStepRow(String stepNumber, String titleText, String bodyText, String buttonLabel, View.OnClickListener onClick) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(0, dpToPx(8f), 0, 0);

        TextView badge = new TextView(this);
        badge.setText(stepNumber);
        badge.setTextColor(Color.WHITE);
        badge.setTypeface(Typeface.DEFAULT_BOLD);
        badge.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f);
        badge.setGravity(Gravity.CENTER);
        GradientDrawable badgeBg = new GradientDrawable();
        badgeBg.setShape(GradientDrawable.OVAL);
        badgeBg.setColor(Color.parseColor("#202124"));
        badge.setBackground(badgeBg);
        LinearLayout.LayoutParams badgeLp = new LinearLayout.LayoutParams(dpToPx(26f), dpToPx(26f));
        badgeLp.rightMargin = dpToPx(12f);
        row.addView(badge, badgeLp);

        LinearLayout textCol = new LinearLayout(this);
        textCol.setOrientation(LinearLayout.VERTICAL);

        TextView t = new TextView(this);
        t.setText(titleText);
        t.setTextColor(Color.parseColor("#202124"));
        t.setTypeface(Typeface.DEFAULT_BOLD);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13.5f);
        textCol.addView(t);

        TextView b = new TextView(this);
        b.setText(bodyText);
        b.setTextColor(Color.parseColor("#5F6368"));
        b.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f);
        b.setPadding(0, dpToPx(2f), 0, 0);
        textCol.addView(b);

        LinearLayout.LayoutParams colLp = new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
        colLp.rightMargin = dpToPx(12f);
        row.addView(textCol, colLp);

        Button btn = createActionButton(buttonLabel, false);
        btn.setOnClickListener(onClick);
        row.addView(btn, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
        ));

        return row;
    }

    private View buildShortcutItem(String keys, String titleText, String descText) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.TOP);
        row.setPadding(0, dpToPx(10f), 0, 0);

        TextView keyBadge = new TextView(this);
        keyBadge.setText(keys);
        keyBadge.setTextColor(Color.parseColor("#18191B"));
        keyBadge.setTypeface(Typeface.create(Typeface.MONOSPACE, Typeface.BOLD));
        keyBadge.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f);
        keyBadge.setPadding(dpToPx(10f), dpToPx(6f), dpToPx(10f), dpToPx(6f));
        GradientDrawable keyBg = new GradientDrawable();
        keyBg.setCornerRadius(dpToPx(8f));
        keyBg.setColor(Color.parseColor("#F1F3F4"));
        keyBg.setStroke(dpToPx(1f), Color.parseColor("#BDC1C6"));
        keyBadge.setBackground(keyBg);

        LinearLayout.LayoutParams keyLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
        );
        keyLp.rightMargin = dpToPx(12f);
        row.addView(keyBadge, keyLp);

        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);

        TextView t = new TextView(this);
        t.setText(titleText);
        t.setTextColor(Color.parseColor("#202124"));
        t.setTypeface(Typeface.DEFAULT_BOLD);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13.5f);
        col.addView(t);

        TextView d = new TextView(this);
        d.setText(descText);
        d.setTextColor(Color.parseColor("#5F6368"));
        d.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f);
        d.setPadding(0, dpToPx(2f), 0, 0);
        col.addView(d);

        row.addView(col, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        return row;
    }

    private Button createActionButton(String text, boolean primary) {
        Button btn = new Button(this);
        btn.setAllCaps(false);
        btn.setText(text);
        btn.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12.5f);
        btn.setTypeface(Typeface.DEFAULT_BOLD);
        int hPad = dpToPx(12f);
        int vPad = dpToPx(8f);
        btn.setPadding(hPad, vPad, hPad, vPad);

        GradientDrawable bg = new GradientDrawable();
        bg.setCornerRadius(dpToPx(10f));
        if (primary) {
            bg.setColor(Color.parseColor("#18191B"));
            btn.setTextColor(Color.WHITE);
        } else {
            bg.setColor(Color.parseColor("#F1F3F4"));
            bg.setStroke(dpToPx(1f), Color.parseColor("#BDC1C6"));
            btn.setTextColor(Color.parseColor("#202124"));
        }
        btn.setBackground(bg);
        return btn;
    }

    @Override
    public boolean dispatchKeyEvent(KeyEvent event) {
        if (event != null
                && event.getAction() == KeyEvent.ACTION_DOWN
                && event.isCtrlPressed()
                && event.isShiftPressed()) {
            if (event.getKeyCode() == KeyEvent.KEYCODE_D && TopBarDropService.instance != null) {
                TopBarDropService.instance.triggerClipboardToPngPopup();
                return true;
            }
            if (event.getKeyCode() == KeyEvent.KEYCODE_C && TopBarDropService.instance != null) {
                TopBarDropService.instance.triggerColorPickerOverlay();
                return true;
            }
        }
        return super.dispatchKeyEvent(event);
    }

    @Override
    protected void onResume() {
        super.onResume();
        current = this;
        refreshUiState();
    }

    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        if (hasFocus) {
            refreshUiState();
        }
    }

    @Override
    protected void onDestroy() {
        if (current == this) {
            current = null;
        }
        super.onDestroy();
    }

    public void refreshUiState() {
        boolean active = isAccessibilityServiceEnabled();
        GradientDrawable bg = new GradientDrawable();
        bg.setCornerRadius(dpToPx(12f));
        if (active) {
            bg.setColor(Color.parseColor("#E6F4EA"));
            bg.setStroke(dpToPx(1f), Color.parseColor("#CEEAD6"));
            statusBanner.setTextColor(Color.parseColor("#137333"));
            statusBanner.setText("✓ ACTIVE — Press Ctrl + Shift + D (Clipboard PNG) or Ctrl + Shift + C (Color Picker) anytime.");
        } else {
            bg.setColor(Color.parseColor("#FCE8E6"));
            bg.setStroke(dpToPx(1f), Color.parseColor("#FAD2CF"));
            statusBanner.setTextColor(Color.parseColor("#C5221F"));
            statusBanner.setText("⚠ SETUP NEEDED — Complete Steps 1 & 2 below so shortcuts work across all apps.");
        }
        statusBanner.setBackground(bg);
        lastActionView.setText("Status: " + TopBarDropService.lastActionMessage);
    }

    private void copySampleWebpToClipboard() {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                ContentValues values = new ContentValues();
                values.put(MediaStore.Images.Media.DISPLAY_NAME, "sample_graphic.webp");
                values.put(MediaStore.Images.Media.MIME_TYPE, "image/webp");
                values.put(MediaStore.Images.Media.RELATIVE_PATH, Environment.DIRECTORY_PICTURES);

                Uri uri = getContentResolver().insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values);
                if (uri != null) {
                    Bitmap bmp = Bitmap.createBitmap(480, 280, Bitmap.Config.ARGB_8888);
                    Canvas canvas = new Canvas(bmp);
                    Paint bgPaint = new Paint();
                    bgPaint.setShader(new LinearGradient(0, 0, 480, 280,
                            Color.parseColor("#1A73E8"), Color.parseColor("#9334E6"), Shader.TileMode.CLAMP));
                    canvas.drawRect(0, 0, 480, 280, bgPaint);

                    Paint textPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
                    textPaint.setColor(Color.WHITE);
                    textPaint.setTypeface(Typeface.DEFAULT_BOLD);
                    textPaint.setTextSize(34f);
                    canvas.drawText("WebP → PNG Test", 95f, 130f, textPaint);
                    textPaint.setTextSize(22f);
                    canvas.drawText("Press Ctrl + Shift + D!", 125f, 175f, textPaint);

                    try (OutputStream out = getContentResolver().openOutputStream(uri)) {
                        bmp.compress(Bitmap.CompressFormat.WEBP, 90, out);
                    }

                    ClipboardManager cm = getSystemService(ClipboardManager.class);
                    if (cm != null) {
                        ClipData clip = ClipData.newUri(getContentResolver(), "sample_graphic.webp", uri);
                        cm.setPrimaryClip(clip);
                        Toast.makeText(this, "Copied sample_graphic.webp to clipboard! Now press Ctrl + Shift + D.", Toast.LENGTH_LONG).show();
                    }
                }
            }
        } catch (Exception e) {
            Toast.makeText(this, "Error creating sample WebP: " + e.getMessage(), Toast.LENGTH_SHORT).show();
        }
    }

    private boolean isAccessibilityServiceEnabled() {
        if (TopBarDropService.instance != null) {
            return true;
        }
        try {
            AccessibilityManager am = (AccessibilityManager) getSystemService(ACCESSIBILITY_SERVICE);
            if (am != null) {
                List<AccessibilityServiceInfo> list = am.getEnabledAccessibilityServiceList(AccessibilityServiceInfo.FEEDBACK_ALL_MASK);
                if (list != null) {
                    for (AccessibilityServiceInfo info : list) {
                        if (info.getId() != null && info.getId().contains(getPackageName())) {
                            return true;
                        }
                    }
                }
            }
        } catch (Exception ignored) {
        }
        try {
            String enabled = Settings.Secure.getString(getContentResolver(), Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES);
            if (enabled != null) {
                ComponentName cn = new ComponentName(this, TopBarDropService.class);
                return enabled.contains(cn.flattenToString()) || enabled.contains(getPackageName());
            }
        } catch (Exception ignored) {
        }
        return false;
    }

    private void openAccessibilitySettings() {
        try {
            Intent intent = new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS);
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            startActivity(intent);
        } catch (Exception e) {
            Toast.makeText(this, "Could not open Accessibility Settings", Toast.LENGTH_SHORT).show();
        }
    }

    private void openAppInfo() {
        try {
            Intent intent = new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS);
            intent.setData(Uri.parse("package:" + getPackageName()));
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            startActivity(intent);
        } catch (Exception e) {
            Toast.makeText(this, "Could not open App Info", Toast.LENGTH_SHORT).show();
        }
    }

    private int dpToPx(float dp) {
        return Math.round(TypedValue.applyDimension(
                TypedValue.COMPLEX_UNIT_DIP,
                dp,
                getResources().getDisplayMetrics()
        ));
    }
}
