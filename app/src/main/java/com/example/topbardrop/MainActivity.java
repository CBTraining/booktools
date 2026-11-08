package com.example.topbardrop;

import android.accessibilityservice.AccessibilityServiceInfo;
import android.app.Activity;
import android.content.ClipData;
import android.content.ComponentName;
import android.content.ContentValues;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
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
import android.view.View;
import android.view.accessibility.AccessibilityManager;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import java.io.OutputStream;
import java.util.List;

/**
 * Setup & test window for TopBarDrop, using the exact same Googlebook Accessibility
 * setup flow as BentoBar (App Info -> Allow restricted settings -> Accessibility Settings).
 */
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

        TextView title = new TextView(this);
        title.setText("TopBarDrop for Googlebook (v3.0)");
        title.setTextSize(TypedValue.COMPLEX_UNIT_SP, 20f);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        title.setTextColor(Color.parseColor("#202124"));
        root.addView(title);

        statusBanner = new TextView(this);
        statusBanner.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f);
        statusBanner.setPadding(dpToPx(14f), dpToPx(12f), dpToPx(14f), dpToPx(12f));
        LinearLayout.LayoutParams bannerLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
        );
        bannerLp.topMargin = dpToPx(12f);
        bannerLp.bottomMargin = dpToPx(16f);
        root.addView(statusBanner, bannerLp);

        TextView instructions = new TextView(this);
        instructions.setText(
                "How Googlebook status bar apps (like BentoBar) work:\n" +
                "Googlebook OS only allows apps to place icons directly on the top status bar (y = 0, inside DesktopStatusBarSpacer) via an Accessibility Service.\n\n" +
                "Setup Steps (same as BentoBar):\n" +
                "1. Click 'Open Accessibility Settings' below and tap TopBarDrop's switch. If Android shows 'Restricted setting', tap OK.\n" +
                "2. Click 'Open App Info' below → tap ⋮ in the top-right corner → 'Allow restricted settings' and enter your PIN.\n" +
                "3. Click 'Open Accessibility Settings' again and switch TopBarDrop ON."
        );
        instructions.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f);
        instructions.setTextColor(Color.parseColor("#3C4043"));
        root.addView(instructions);

        LinearLayout btnRow = new LinearLayout(this);
        btnRow.setOrientation(LinearLayout.HORIZONTAL);
        LinearLayout.LayoutParams rowLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
        );
        rowLp.topMargin = dpToPx(16f);

        Button a11yBtn = new Button(this);
        a11yBtn.setText("Open Accessibility Settings");
        a11yBtn.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                openAccessibilitySettings();
            }
        });
        btnRow.addView(a11yBtn, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));

        Button appInfoBtn = new Button(this);
        appInfoBtn.setText("Open App Info (⋮)");
        appInfoBtn.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                openAppInfo();
            }
        });
        LinearLayout.LayoutParams infoLp = new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
        infoLp.leftMargin = dpToPx(12f);
        btnRow.addView(appInfoBtn, infoLp);

        root.addView(btnRow, rowLp);

        lastActionView = new TextView(this);
        lastActionView.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f);
        lastActionView.setTextColor(Color.parseColor("#1A73E8"));
        lastActionView.setPadding(0, dpToPx(16f), 0, dpToPx(8f));
        root.addView(lastActionView);

        // Built-in draggable sample card so the user can test dragging to the top status bar icon
        TextView dragCard = new TextView(this);
        dragCard.setText("🖼  Long-press & drag me up to the '⬇ dl' icon in the top status bar!\n(Test file: sample_photo.png → sample_photo_dl.png)");
        dragCard.setTextColor(Color.WHITE);
        dragCard.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f);
        dragCard.setGravity(Gravity.CENTER);
        int cardPad = dpToPx(18f);
        dragCard.setPadding(cardPad, cardPad, cardPad, cardPad);

        GradientDrawable cardBg = new GradientDrawable();
        cardBg.setCornerRadius(dpToPx(16f));
        cardBg.setColor(Color.parseColor("#1A73E8"));
        dragCard.setBackground(cardBg);

        LinearLayout.LayoutParams cardLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
        );
        cardLp.topMargin = dpToPx(12f);
        root.addView(dragCard, cardLp);

        dragCard.setOnLongClickListener(new View.OnLongClickListener() {
            @Override
            public boolean onLongClick(View v) {
                Uri sampleUri = createSampleImageUri();
                if (sampleUri == null) {
                    Toast.makeText(MainActivity.this, "Could not create sample image", Toast.LENGTH_SHORT).show();
                    return false;
                }
                ClipData clipData = ClipData.newUri(getContentResolver(), "sample_photo.png", sampleUri);
                View.DragShadowBuilder shadow = new View.DragShadowBuilder(v);
                int flags = View.DRAG_FLAG_GLOBAL | View.DRAG_FLAG_GLOBAL_URI_READ;
                v.startDragAndDrop(clipData, shadow, null, flags);
                return true;
            }
        });

        setContentView(root);
        refreshUiState();
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
            statusBanner.setText("✓ LIVE IN STATUS BAR — Look at the top status bar (to the left of the system/Wi-Fi/battery icons) for the blue '⬇ dl' icon! Drag any file or image to it.");
        } else {
            bg.setColor(Color.parseColor("#FCE8E6"));
            statusBanner.setTextColor(Color.parseColor("#C5221F"));
            statusBanner.setText("⚠ Accessibility Service is OFF — Follow the 3 steps below to turn TopBarDrop on in your Googlebook status bar.");
        }
        statusBanner.setBackground(bg);
        lastActionView.setText("Last status: " + TopBarDropService.lastActionMessage);
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

    private Uri createSampleImageUri() {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                ContentValues values = new ContentValues();
                values.put(MediaStore.Images.Media.DISPLAY_NAME, "sample_photo.png");
                values.put(MediaStore.Images.Media.MIME_TYPE, "image/png");
                values.put(MediaStore.Images.Media.RELATIVE_PATH, Environment.DIRECTORY_PICTURES);

                Uri uri = getContentResolver().insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values);
                if (uri != null) {
                    Bitmap bmp = Bitmap.createBitmap(320, 200, Bitmap.Config.ARGB_8888);
                    Canvas canvas = new Canvas(bmp);
                    canvas.drawColor(Color.parseColor("#1A73E8"));
                    Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
                    p.setColor(Color.WHITE);
                    p.setTextSize(32f);
                    canvas.drawText("sample_photo.png", 28f, 110f, p);
                    try (OutputStream out = getContentResolver().openOutputStream(uri)) {
                        bmp.compress(Bitmap.CompressFormat.PNG, 100, out);
                    }
                    return uri;
                }
            }
        } catch (Exception ignored) {
        }
        return null;
    }

    private int dpToPx(float dp) {
        return Math.round(TypedValue.applyDimension(
                TypedValue.COMPLEX_UNIT_DIP,
                dp,
                getResources().getDisplayMetrics()
        ));
    }
}
