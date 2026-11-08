package com.example.topbardrop;

import android.accessibilityservice.AccessibilityServiceInfo;
import android.app.Activity;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.ComponentName;
import android.content.ContentValues;
import android.content.Intent;
import android.content.res.Configuration;
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
    private TextView updateStatusView;
    private Button checkUpdateBtn;
    private View setupCard;
    private TextView lastActionView;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        current = this;
        buildThemedContentView();
        triggerAutoUpdateCheck(false);
    }

    @Override
    public void onConfigurationChanged(Configuration newConfig) {
        super.onConfigurationChanged(newConfig);
        buildThemedContentView();
    }

    private boolean isDarkMode() {
        int nightMask = getResources().getConfiguration().uiMode & Configuration.UI_MODE_NIGHT_MASK;
        return nightMask == Configuration.UI_MODE_NIGHT_YES;
    }

    private void buildThemedContentView() {
        boolean dark = isDarkMode();
        String verName = AutoUpdater.getInstalledVersionName(this);

        int bgWindow = dark ? Color.parseColor("#121316") : Color.parseColor("#F1F3F4");
        int textPrimary = dark ? Color.parseColor("#F1F3F4") : Color.parseColor("#202124");
        int textSecondary = dark ? Color.parseColor("#9AA0A6") : Color.parseColor("#5F6368");
        int dividerColor = dark ? Color.parseColor("#2D3036") : Color.parseColor("#E8EAED");
        int accentTeal = dark ? Color.parseColor("#2DD4BF") : Color.parseColor("#0D9488");

        ScrollView scrollView = new ScrollView(this);
        scrollView.setFillViewport(true);
        scrollView.setBackgroundColor(bgWindow);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        int pad = dpToPx(22f);
        root.setPadding(pad, dpToPx(18f), pad, pad);
        scrollView.addView(root);

        // --- Header Row with Icon, Title, Version Badge, Author Credit, and Update Controls ---
        LinearLayout titleRow = new LinearLayout(this);
        titleRow.setOrientation(LinearLayout.HORIZONTAL);
        titleRow.setGravity(Gravity.CENTER_VERTICAL);

        ImageView appIcon = new ImageView(this);
        appIcon.setImageResource(R.drawable.ic_drop_arrow);
        LinearLayout.LayoutParams iconLp = new LinearLayout.LayoutParams(dpToPx(44f), dpToPx(44f));
        iconLp.rightMargin = dpToPx(14f);
        titleRow.addView(appIcon, iconLp);

        LinearLayout titleTextCol = new LinearLayout(this);
        titleTextCol.setOrientation(LinearLayout.VERTICAL);

        LinearLayout nameAndVerRow = new LinearLayout(this);
        nameAndVerRow.setOrientation(LinearLayout.HORIZONTAL);
        nameAndVerRow.setGravity(Gravity.CENTER_VERTICAL);

        TextView title = new TextView(this);
        title.setText("BookTools");
        title.setTextSize(TypedValue.COMPLEX_UNIT_SP, 21f);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        title.setTextColor(textPrimary);
        nameAndVerRow.addView(title);

        TextView verBadge = new TextView(this);
        verBadge.setText("v" + verName);
        verBadge.setTextColor(accentTeal);
        verBadge.setTypeface(Typeface.create(Typeface.MONOSPACE, Typeface.BOLD));
        verBadge.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f);
        verBadge.setPadding(dpToPx(8f), dpToPx(2f), dpToPx(8f), dpToPx(2f));
        GradientDrawable verBg = new GradientDrawable();
        verBg.setCornerRadius(dpToPx(10f));
        verBg.setColor(dark ? Color.parseColor("#162C2A") : Color.parseColor("#F0FDFA"));
        verBg.setStroke(dpToPx(1f), dark ? Color.parseColor("#0D9488") : Color.parseColor("#99F6E4"));
        verBadge.setBackground(verBg);
        LinearLayout.LayoutParams verLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
        );
        verLp.leftMargin = dpToPx(10f);
        nameAndVerRow.addView(verBadge, verLp);

        titleTextCol.addView(nameAndVerRow);

        TextView authorLine = new TextView(this);
        authorLine.setText("Made by Christopher Gammello");
        authorLine.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12.5f);
        authorLine.setTypeface(Typeface.DEFAULT_BOLD);
        authorLine.setTextColor(accentTeal);
        authorLine.setPadding(0, dpToPx(2f), 0, dpToPx(2f));
        titleTextCol.addView(authorLine);

        TextView subtitle = new TextView(this);
        subtitle.setText("Instant Clipboard Image-to-PNG Converter & Screen HEX Color Picker");
        subtitle.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f);
        subtitle.setTextColor(textSecondary);
        titleTextCol.addView(subtitle);

        titleRow.addView(titleTextCol, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));

        // Update status & Check / Self-Update button on the right of the header
        LinearLayout updateCol = new LinearLayout(this);
        updateCol.setOrientation(LinearLayout.VERTICAL);
        updateCol.setGravity(Gravity.END);

        checkUpdateBtn = createActionButton(dark, "Check Update", false);
        checkUpdateBtn.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (AutoUpdater.pendingInstallApkFile != null && AutoUpdater.pendingInstallApkFile.exists()) {
                    AutoUpdater.installDownloadedApkIfPermitted(MainActivity.this, createUpdateCallback());
                } else {
                    triggerAutoUpdateCheck(true);
                }
            }
        });
        updateCol.addView(checkUpdateBtn, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
        ));

        updateStatusView = new TextView(this);
        updateStatusView.setText(AutoUpdater.updateStatusText);
        updateStatusView.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f);
        updateStatusView.setTextColor(textSecondary);
        updateStatusView.setPadding(0, dpToPx(4f), 0, 0);
        updateCol.addView(updateStatusView, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
        ));

        titleRow.addView(updateCol);
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

        // --- Card 1: Streamlined 2-Step Setup (including "Allow restricted settings") ---
        setupCard = buildCardContainer(dark);
        LinearLayout setupLayout = (LinearLayout) setupCard;

        TextView setupHeader = new TextView(this);
        setupHeader.setText("Quick 2-Step Setup");
        setupHeader.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f);
        setupHeader.setTypeface(Typeface.DEFAULT_BOLD);
        setupHeader.setTextColor(textPrimary);
        setupLayout.addView(setupHeader);

        setupLayout.addView(buildStepRow(
                dark,
                "1",
                "Allow Restricted Settings",
                "Tap 'Open App Info (⋮)', click the ⋮ (three dots) in the top-right corner of the system page, and choose 'Allow restricted settings'. (If ⋮ isn't shown yet, click BookTools once inside Accessibility Settings first.)",
                "1. Open App Info (⋮)",
                new View.OnClickListener() {
                    @Override
                    public void onClick(View v) {
                        openAppInfo();
                    }
                }
        ));

        View divider = new View(this);
        divider.setBackgroundColor(dividerColor);
        LinearLayout.LayoutParams divLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dpToPx(1f));
        divLp.topMargin = dpToPx(10f);
        divLp.bottomMargin = dpToPx(10f);
        setupLayout.addView(divider, divLp);

        setupLayout.addView(buildStepRow(
                dark,
                "2",
                "Enable BookTools Shortcut Service",
                "Tap 'Open Accessibility', select 'BookTools Shortcuts', and switch it ON. (If updating from an older version, toggle it OFF and back ON once to refresh permissions.)",
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
        LinearLayout shortcutsCard = buildCardContainer(dark);

        TextView shortcutsHeader = new TextView(this);
        shortcutsHeader.setText("What You Can Do");
        shortcutsHeader.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f);
        shortcutsHeader.setTypeface(Typeface.DEFAULT_BOLD);
        shortcutsHeader.setTextColor(textPrimary);
        shortcutsCard.addView(shortcutsHeader);

        shortcutsCard.addView(buildShortcutItem(
                dark,
                "Ctrl + Shift + D",
                "Clipboard Image → PNG Download & Drag Popup",
                "Converts any copied image (WebP, JPEG, PNG, etc.) into a lossless .png file in Downloads and opens a frosted glass popup card. Click & drag the PNG directly into any app, or swipe / right-click to dismiss."
        ));

        shortcutsCard.addView(buildShortcutItem(
                dark,
                "Ctrl + Shift + C",
                "Screen Color Picker (#HEX to Clipboard)",
                "Opens a precision crosshair with a frosted glass magnified pixel loupe (auto-flips above or below your cursor near screen edges). Click any pixel to copy its #RRGGBB HEX code to your clipboard."
        ));

        LinearLayout.LayoutParams cardLp2 = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
        );
        cardLp2.topMargin = dpToPx(12f);
        root.addView(shortcutsCard, cardLp2);

        // --- Card 3: Built-in Test Bar ---
        LinearLayout testCard = buildCardContainer(dark);

        TextView testHeader = new TextView(this);
        testHeader.setText("Test Tools");
        testHeader.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f);
        testHeader.setTypeface(Typeface.DEFAULT_BOLD);
        testHeader.setTextColor(textPrimary);
        testCard.addView(testHeader);

        LinearLayout testRow = new LinearLayout(this);
        testRow.setOrientation(LinearLayout.HORIZONTAL);
        LinearLayout.LayoutParams testLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
        );
        testLp.topMargin = dpToPx(8f);

        Button copyWebpBtn = createActionButton(dark, "Copy Sample WebP", false);
        copyWebpBtn.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                copySampleWebpToClipboard();
            }
        });
        testRow.addView(copyWebpBtn, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));

        Button triggerPngBtn = createActionButton(dark, "Test PNG (Ctrl+Shift+D)", true);
        triggerPngBtn.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (TopBarDropService.instance != null) {
                    TopBarDropService.instance.triggerClipboardToPngPopup();
                } else {
                    Toast.makeText(MainActivity.this, "Complete Steps 1 & 2 above to turn on the service first!", Toast.LENGTH_LONG).show();
                }
            }
        });
        LinearLayout.LayoutParams btnLp2 = new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
        btnLp2.leftMargin = dpToPx(8f);
        testRow.addView(triggerPngBtn, btnLp2);

        Button triggerColorBtn = createActionButton(dark, "Test Color (Ctrl+Shift+C)", true);
        triggerColorBtn.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (TopBarDropService.instance != null) {
                    TopBarDropService.instance.triggerColorPickerOverlay();
                } else {
                    Toast.makeText(MainActivity.this, "Complete Steps 1 & 2 above to turn on the service first!", Toast.LENGTH_LONG).show();
                }
            }
        });
        LinearLayout.LayoutParams btnLp3 = new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
        btnLp3.leftMargin = dpToPx(8f);
        testRow.addView(triggerColorBtn, btnLp3);

        testCard.addView(testRow, testLp);

        lastActionView = new TextView(this);
        lastActionView.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12.5f);
        lastActionView.setTextColor(accentTeal);
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

    private AutoUpdater.StatusCallback createUpdateCallback() {
        return new AutoUpdater.StatusCallback() {
            @Override
            public void onStatusChanged(String status, boolean hasUpdate) {
                if (updateStatusView != null) {
                    updateStatusView.setText(status);
                    boolean dark = isDarkMode();
                    updateStatusView.setTextColor(hasUpdate
                            ? (dark ? Color.parseColor("#A3E635") : Color.parseColor("#0D9488"))
                            : (dark ? Color.parseColor("#9AA0A6") : Color.parseColor("#5F6368")));
                }
                if (checkUpdateBtn != null) {
                    if (AutoUpdater.pendingInstallApkFile != null && AutoUpdater.pendingInstallApkFile.exists()) {
                        checkUpdateBtn.setText("Install Update");
                    } else if (hasUpdate && AutoUpdater.latestRemoteVersion != null) {
                        checkUpdateBtn.setText("Update v" + AutoUpdater.latestRemoteVersion);
                    } else {
                        checkUpdateBtn.setText("Check Update");
                    }
                }
            }
        };
    }

    private void triggerAutoUpdateCheck(boolean manual) {
        AutoUpdater.checkForUpdates(this, manual, true, createUpdateCallback());
    }

    public Bitmap captureActivityWindowBitmap() {
        try {
            View root = getWindow().getDecorView().getRootView();
            if (root != null && root.getWidth() > 0 && root.getHeight() > 0) {
                Bitmap b = Bitmap.createBitmap(root.getWidth(), root.getHeight(), Bitmap.Config.ARGB_8888);
                Canvas c = new Canvas(b);
                root.draw(c);
                return b;
            }
        } catch (Exception ignored) {
        }
        return null;
    }

    private LinearLayout buildCardContainer(boolean dark) {
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        int pad = dpToPx(16f);
        card.setPadding(pad, dpToPx(14f), pad, dpToPx(14f));
        GradientDrawable bg = new GradientDrawable();
        bg.setCornerRadius(dpToPx(14f));
        bg.setColor(dark ? Color.parseColor("#1E2024") : Color.WHITE);
        bg.setStroke(dpToPx(1f), dark ? Color.parseColor("#34373D") : Color.parseColor("#DADCE0"));
        card.setBackground(bg);
        return card;
    }

    private View buildStepRow(boolean dark, String stepNumber, String titleText, String bodyText, String buttonLabel, View.OnClickListener onClick) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(0, dpToPx(8f), 0, 0);

        TextView badge = new TextView(this);
        badge.setText(stepNumber);
        badge.setTextColor(dark ? Color.parseColor("#121316") : Color.WHITE);
        badge.setTypeface(Typeface.DEFAULT_BOLD);
        badge.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f);
        badge.setGravity(Gravity.CENTER);
        GradientDrawable badgeBg = new GradientDrawable();
        badgeBg.setShape(GradientDrawable.OVAL);
        badgeBg.setColor(dark ? Color.parseColor("#2DD4BF") : Color.parseColor("#0D9488"));
        badge.setBackground(badgeBg);
        LinearLayout.LayoutParams badgeLp = new LinearLayout.LayoutParams(dpToPx(26f), dpToPx(26f));
        badgeLp.rightMargin = dpToPx(12f);
        row.addView(badge, badgeLp);

        LinearLayout textCol = new LinearLayout(this);
        textCol.setOrientation(LinearLayout.VERTICAL);

        TextView t = new TextView(this);
        t.setText(titleText);
        t.setTextColor(dark ? Color.parseColor("#F1F3F4") : Color.parseColor("#202124"));
        t.setTypeface(Typeface.DEFAULT_BOLD);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13.5f);
        textCol.addView(t);

        TextView b = new TextView(this);
        b.setText(bodyText);
        b.setTextColor(dark ? Color.parseColor("#9AA0A6") : Color.parseColor("#5F6368"));
        b.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f);
        b.setPadding(0, dpToPx(2f), 0, 0);
        textCol.addView(b);

        LinearLayout.LayoutParams colLp = new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
        colLp.rightMargin = dpToPx(12f);
        row.addView(textCol, colLp);

        Button btn = createActionButton(dark, buttonLabel, false);
        btn.setOnClickListener(onClick);
        row.addView(btn, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
        ));

        return row;
    }

    private View buildShortcutItem(boolean dark, String keys, String titleText, String descText) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.TOP);
        row.setPadding(0, dpToPx(10f), 0, 0);

        TextView keyBadge = new TextView(this);
        keyBadge.setText(keys);
        keyBadge.setTextColor(dark ? Color.parseColor("#2DD4BF") : Color.parseColor("#0D9488"));
        keyBadge.setTypeface(Typeface.create(Typeface.MONOSPACE, Typeface.BOLD));
        keyBadge.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f);
        keyBadge.setPadding(dpToPx(10f), dpToPx(6f), dpToPx(10f), dpToPx(6f));
        GradientDrawable keyBg = new GradientDrawable();
        keyBg.setCornerRadius(dpToPx(8f));
        keyBg.setColor(dark ? Color.parseColor("#162C2A") : Color.parseColor("#F0FDFA"));
        keyBg.setStroke(dpToPx(1f), dark ? Color.parseColor("#0D9488") : Color.parseColor("#99F6E4"));
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
        t.setTextColor(dark ? Color.parseColor("#F1F3F4") : Color.parseColor("#202124"));
        t.setTypeface(Typeface.DEFAULT_BOLD);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13.5f);
        col.addView(t);

        TextView d = new TextView(this);
        d.setText(descText);
        d.setTextColor(dark ? Color.parseColor("#9AA0A6") : Color.parseColor("#5F6368"));
        d.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f);
        d.setPadding(0, dpToPx(2f), 0, 0);
        col.addView(d);

        row.addView(col, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        return row;
    }

    private Button createActionButton(boolean dark, String text, boolean primary) {
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
            bg.setColor(dark ? Color.parseColor("#2DD4BF") : Color.parseColor("#0D9488"));
            btn.setTextColor(dark ? Color.parseColor("#121316") : Color.WHITE);
        } else {
            bg.setColor(dark ? Color.parseColor("#282A2E") : Color.parseColor("#F1F3F4"));
            bg.setStroke(dpToPx(1f), dark ? Color.parseColor("#44474E") : Color.parseColor("#BDC1C6"));
            btn.setTextColor(dark ? Color.parseColor("#E8EAED") : Color.parseColor("#202124"));
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
        // If user just granted 'Install unknown apps' and returned, finish installing the pending APK
        if (AutoUpdater.pendingInstallApkFile != null && AutoUpdater.pendingInstallApkFile.exists()) {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O || getPackageManager().canRequestPackageInstalls()) {
                AutoUpdater.installDownloadedApkIfPermitted(this, createUpdateCallback());
                return;
            }
        }
        triggerAutoUpdateCheck(false);
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
        if (statusBanner == null || lastActionView == null) return;
        boolean active = isAccessibilityServiceEnabled();
        boolean dark = isDarkMode();
        GradientDrawable bg = new GradientDrawable();
        bg.setCornerRadius(dpToPx(12f));
        if (active) {
            bg.setColor(dark ? Color.parseColor("#142E24") : Color.parseColor("#E6F4EA"));
            bg.setStroke(dpToPx(1f), dark ? Color.parseColor("#1E4D3B") : Color.parseColor("#CEEAD6"));
            statusBanner.setTextColor(dark ? Color.parseColor("#5BB974") : Color.parseColor("#137333"));
            statusBanner.setText("✓ SERVICE ACTIVE — Press Ctrl + Shift + D (Clipboard PNG) or Ctrl + Shift + C (Color Picker) anytime.");
        } else {
            bg.setColor(dark ? Color.parseColor("#361919") : Color.parseColor("#FCE8E6"));
            bg.setStroke(dpToPx(1f), dark ? Color.parseColor("#5C2B29") : Color.parseColor("#FAD2CF"));
            statusBanner.setTextColor(dark ? Color.parseColor("#F28B82") : Color.parseColor("#C5221F"));
            statusBanner.setText("⚠ SERVICE INACTIVE — Follow Steps 1 & 2 below to enable global shortcuts.");
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
                            Color.parseColor("#A3E635"), Color.parseColor("#0D9488"), Shader.TileMode.CLAMP));
                    canvas.drawRect(0, 0, 480, 280, bgPaint);

                    Paint textPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
                    textPaint.setColor(Color.WHITE);
                    textPaint.setTypeface(Typeface.DEFAULT_BOLD);
                    textPaint.setTextSize(34f);
                    canvas.drawText("BookTools WebP Test", 75f, 130f, textPaint);
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
