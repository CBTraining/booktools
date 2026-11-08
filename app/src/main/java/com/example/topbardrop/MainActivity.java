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
import android.view.KeyEvent;
import android.view.View;
import android.view.accessibility.AccessibilityManager;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import java.io.OutputStream;
import java.util.List;

public class MainActivity extends Activity {

    public static volatile MainActivity current;

    private TextView statusBanner;
    private TextView lastActionView;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        current = this;

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(Color.parseColor("#F8F9FA"));
        int pad = dpToPx(24f);
        root.setPadding(pad, pad, pad, pad);

        LinearLayout titleRow = new LinearLayout(this);
        titleRow.setOrientation(LinearLayout.HORIZONTAL);
        titleRow.setGravity(android.view.Gravity.CENTER_VERTICAL);

        android.widget.ImageView appIcon = new android.widget.ImageView(this);
        appIcon.setImageResource(R.drawable.ic_drop_arrow);
        int iconPad = dpToPx(6f);
        appIcon.setPadding(iconPad, iconPad, iconPad, iconPad);
        GradientDrawable iconBg = new GradientDrawable();
        iconBg.setShape(GradientDrawable.OVAL);
        iconBg.setColor(Color.parseColor("#18191B"));
        appIcon.setBackground(iconBg);
        LinearLayout.LayoutParams iconLp = new LinearLayout.LayoutParams(dpToPx(34f), dpToPx(34f));
        iconLp.rightMargin = dpToPx(12f);
        titleRow.addView(appIcon, iconLp);

        TextView title = new TextView(this);
        title.setText("Clipboard PNG Drop (Ctrl + Shift + D)");
        title.setTextSize(TypedValue.COMPLEX_UNIT_SP, 20f);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        title.setTextColor(Color.parseColor("#202124"));
        titleRow.addView(title);
        root.addView(titleRow);

        statusBanner = new TextView(this);
        statusBanner.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f);
        statusBanner.setPadding(dpToPx(14f), dpToPx(12f), dpToPx(14f), dpToPx(12f));
        LinearLayout.LayoutParams bannerLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
        );
        bannerLp.topMargin = dpToPx(12f);
        bannerLp.bottomMargin = dpToPx(14f);
        root.addView(statusBanner, bannerLp);

        TextView info = new TextView(this);
        info.setText(
                "• No top-bar icon is drawn, so it never conflicts with BentoBar.\n" +
                "• Copy any image (WebP, JPEG, PNG, screenshot) anywhere on your Googlebook and press Ctrl + Shift + D.\n" +
                "• It converts the clipboard image to a lossless .png in Downloads and pops up a floating card.\n" +
                "• Click & drag the PNG out of the popup into any app, or dismiss the popup by right-clicking it or swiping its header/footer."
        );
        info.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f);
        info.setTextColor(Color.parseColor("#3C4043"));
        root.addView(info);

        LinearLayout setupRow = new LinearLayout(this);
        setupRow.setOrientation(LinearLayout.HORIZONTAL);
        LinearLayout.LayoutParams setupLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
        );
        setupLp.topMargin = dpToPx(16f);

        Button a11yBtn = new Button(this);
        a11yBtn.setText("Accessibility Settings");
        a11yBtn.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                openAccessibilitySettings();
            }
        });
        setupRow.addView(a11yBtn, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));

        Button appInfoBtn = new Button(this);
        appInfoBtn.setText("App Info (⋮)");
        appInfoBtn.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                openAppInfo();
            }
        });
        LinearLayout.LayoutParams infoLp = new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
        infoLp.leftMargin = dpToPx(12f);
        setupRow.addView(appInfoBtn, infoLp);
        root.addView(setupRow, setupLp);

        // Built-in test buttons: 1) Put a real WebP image onto the clipboard, 2) Test the popup
        LinearLayout testRow = new LinearLayout(this);
        testRow.setOrientation(LinearLayout.HORIZONTAL);
        LinearLayout.LayoutParams testLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
        );
        testLp.topMargin = dpToPx(12f);

        Button copyWebpBtn = new Button(this);
        copyWebpBtn.setText("1. Copy Sample WebP to Clipboard");
        copyWebpBtn.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                copySampleWebpToClipboard();
            }
        });
        testRow.addView(copyWebpBtn, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));

        Button triggerBtn = new Button(this);
        triggerBtn.setText("2. Test Popup (Ctrl+Shift+D)");
        triggerBtn.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (TopBarDropService.instance != null) {
                    TopBarDropService.instance.triggerClipboardToPngPopup();
                } else {
                    Toast.makeText(MainActivity.this, "Turn on TopBarDrop in Accessibility Settings first!", Toast.LENGTH_LONG).show();
                }
            }
        });
        LinearLayout.LayoutParams trigLp = new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
        trigLp.leftMargin = dpToPx(12f);
        testRow.addView(triggerBtn, trigLp);
        root.addView(testRow, testLp);

        lastActionView = new TextView(this);
        lastActionView.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f);
        lastActionView.setTextColor(Color.parseColor("#1A73E8"));
        lastActionView.setPadding(0, dpToPx(16f), 0, 0);
        root.addView(lastActionView);

        setContentView(root);
        refreshUiState();
    }

    @Override
    public boolean dispatchKeyEvent(KeyEvent event) {
        if (event != null
                && event.getAction() == KeyEvent.ACTION_DOWN
                && event.getKeyCode() == KeyEvent.KEYCODE_D
                && event.isCtrlPressed()
                && event.isShiftPressed()) {
            if (TopBarDropService.instance != null) {
                TopBarDropService.instance.triggerClipboardToPngPopup();
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
            statusBanner.setTextColor(Color.parseColor("#137333"));
            statusBanner.setText("✓ READY — Press Ctrl + Shift + D anytime to save the clipboard image as a PNG and open the drag-and-drop popup.");
        } else {
            bg.setColor(Color.parseColor("#FCE8E6"));
            statusBanner.setTextColor(Color.parseColor("#C5221F"));
            statusBanner.setText("⚠ Service OFF — Turn on TopBarDrop in Accessibility Settings so Ctrl + Shift + D works across all apps.");
        }
        statusBanner.setBackground(bg);
        lastActionView.setText("Last status: " + TopBarDropService.lastActionMessage);
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
            Toast.makeText(this, "Error copying sample WebP: " + e.getMessage(), Toast.LENGTH_LONG).show();
        }
    }

    private boolean isAccessibilityServiceEnabled() {
        if (TopBarDropService.instance != null) return true;
        AccessibilityManager am = getSystemService(AccessibilityManager.class);
        if (am == null) return false;
        ComponentName me = new ComponentName(this, TopBarDropService.class);
        List<AccessibilityServiceInfo> list = am.getEnabledAccessibilityServiceList(AccessibilityServiceInfo.FEEDBACK_ALL_MASK);
        for (AccessibilityServiceInfo info : list) {
            if (info.getResolveInfo() != null && info.getResolveInfo().serviceInfo != null) {
                String pkg = info.getResolveInfo().serviceInfo.packageName;
                String cls = info.getResolveInfo().serviceInfo.name;
                if (me.getPackageName().equals(pkg) && me.getClassName().equals(cls)) {
                    return true;
                }
            }
        }
        return false;
    }

    private void openAccessibilitySettings() {
        String cn = new ComponentName(this, TopBarDropService.class).flattenToString();
        Intent intent = new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS);
        intent.putExtra(":settings:fragment_args_key", cn);
        Bundle bundle = new Bundle();
        bundle.putString(":settings:fragment_args_key", cn);
        intent.putExtra(":settings:show_fragment_args", bundle);
        startActivity(intent);
    }

    private void openAppInfo() {
        Intent intent = new Intent(
                Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                Uri.fromParts("package", getPackageName(), null)
        );
        startActivity(intent);
    }

    private int dpToPx(float dp) {
        return Math.round(TypedValue.applyDimension(
                TypedValue.COMPLEX_UNIT_DIP,
                dp,
                getResources().getDisplayMetrics()
        ));
    }
}
