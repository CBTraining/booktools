package com.example.topbardrop;

import android.accessibilityservice.AccessibilityService;
import android.accessibilityservice.AccessibilityServiceInfo;
import android.accessibilityservice.GestureDescription;
import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.animation.ValueAnimator;
import android.content.ClipData;
import android.content.ClipDescription;
import android.content.ClipboardManager;
import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.res.Configuration;
import android.database.Cursor;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.ColorSpace;
import android.graphics.DashPathEffect;
import android.graphics.ImageDecoder;
import android.graphics.Insets;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.Shader;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.hardware.HardwareBuffer;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.provider.MediaStore;
import android.provider.OpenableColumns;
import android.util.Base64;
import android.util.DisplayMetrics;
import android.util.TypedValue;
import android.view.Display;
import android.view.DragEvent;
import android.view.Gravity;
import android.view.InputDevice;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.PointerIcon;
import android.view.View;
import android.view.ViewConfiguration;
import android.view.ViewTreeObserver;
import android.view.WindowInsets;
import android.view.WindowManager;
import android.view.WindowMetrics;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;
import android.view.accessibility.AccessibilityWindowInfo;
import android.view.animation.AccelerateInterpolator;
import android.view.animation.OvershootInterpolator;
import android.text.TextUtils;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Global keyboard shortcut & window management service for BookTools:
 *  - Ctrl + Shift + D: Reads clipboard image (WebP, JPEG, PNG, GIF, BMP, data URI),
 *    saves it as a lossless .png in Downloads on a background I/O thread, and opens a frosted
 *    glass popup card (without blurring the whole screen!) where you can click-and-drag the PNG
 *    into any app, or dismiss by swiping or right-clicking.
 *  - Ctrl + Shift + C: Captures the screen and opens an interactive Crosshair + Frosted Glass
 *    Zoom Magnifier Loupe (which flips automatically between top and bottom if near screen edges)
 *    with 1px Arrow-Key precision nudging, copying the pointed pixel's #RRGGBB HEX code.
 *  - Ctrl + Shift + G: Opens the 100% Local Clipboard History (up to 30 recent Text snippets,
 *    HEX Colors, and PNG Images, ignoring EXTRA_IS_SENSITIVE passwords) with [1]-[9] quick-keys,
 *    click-to-copy, and click-and-drag directly into any app.
 *  - Hold Shift while dragging any window (or press Ctrl + Shift + Z): PowerToys FancyZones
 *    window snapper — outlines the screen into 4 Quarters, Half & Half, or Triple Column so you
 *    can drop/snap the active window to fit the zone.
 *  - Automatically adapts overlays to Device Dark Mode / Light Mode settings.
 */
public class TopBarDropService extends AccessibilityService {

    public static volatile TopBarDropService instance;
    public static volatile String lastActionMessage =
            "Ready — Ctrl+Shift+D (PNG) · Ctrl+Shift+C (Color) · Ctrl+Shift+G (History) · Shift+Drag (Snap Zones)";

    private WindowManager windowManager;
    private Handler handler;
    private final ExecutorService ioExecutor = Executors.newSingleThreadExecutor();
    private ClipboardManager clipboardManager;
    private ClipboardManager.OnPrimaryClipChangedListener clipChangedListener;
    private View focusProbeView;

    // --- Ctrl + Shift + D (Clipboard PNG Popup) state ---
    private PopupRootView popupRoot;
    private FrostedGlassCardLayout popupCard;
    private WindowManager.LayoutParams popupParams;
    private boolean popupShown = false;
    private boolean popupDismissing = false;

    private TextView headerTitleView;
    private TextView subtitleView;
    private ImageView previewImageView;
    private TextView dragHintView;
    private LinearLayout dragBox;

    private Uri currentPngUri;
    private String currentPngFileName;
    private Bitmap currentPreviewBitmap;
    private boolean clipboardProcessedForCurrentPopup = false;
    private long lastShortcutTriggerMs = 0L;

    // --- Ctrl + Shift + C (Screen Color Picker) state ---
    private ColorPickerRootView colorPickerRoot;
    private ColorPickerCanvasView colorPickerCanvas;
    private WindowManager.LayoutParams colorPickerParams;
    private boolean colorPickerShown = false;
    private boolean colorPickerDismissing = false;
    private boolean colorPickerCaptureInFlight = false;
    private Bitmap lastCapturedScreenBitmap;
    private Bitmap lastBlurredScreenBitmap;
    private long lastColorPickerTriggerMs = 0L;

    // --- Ctrl + Shift + G (100% Local Clipboard History) state ---
    private HistoryRootView historyRoot;
    private FrostedGlassCardLayout historyCard;
    private WindowManager.LayoutParams historyParams;
    private LinearLayout historyItemsContainer;
    private TextView historySubtitleView;
    private final List<Bitmap> activeHistoryThumbBitmaps = new ArrayList<>();
    private boolean historyShown = false;
    private boolean historyDismissing = false;
    private boolean historySyncedCurrentClip = false;
    private long lastHistoryTriggerMs = 0L;

    // --- Shift + Window Drag / Ctrl + Shift + Z (PowerToys FancyZones Snap Zones) state ---
    private SnapZonesRootView snapZonesRoot;
    private SnapZonesCanvasView snapZonesCanvas;
    private WindowManager.LayoutParams snapZonesParams;
    private boolean snapZonesShown = false;
    private boolean snapZonesDismissing = false;
    private boolean snapZonesInteractive = false;
    private boolean shiftKeyHeld = false;
    private int trackedWindowId = -1;
    private final Rect trackedWindowRect = new Rect();
    private int draggedWindowId = -1;
    private final Rect draggedWindowRect = new Rect();
    private CharSequence draggedWindowTitle = "";
    private long lastWindowDragTimeMs = 0L;
    private long lastSnapZonesMoveMs = 0L;
    private long lastSnapZonesTriggerMs = 0L;
    private List<SnapZonesManager.SnapZone> currentSnapZones = new ArrayList<>();
    private int hoveredSnapZoneIndex = -1;

    @Override
    public void onCreate() {
        super.onCreate();
        instance = this;
        handler = new Handler(Looper.getMainLooper());
        windowManager = (WindowManager) getSystemService(WINDOW_SERVICE);
        registerLocalClipboardMonitor();
    }

    @Override
    protected void onServiceConnected() {
        super.onServiceConnected();
        instance = this;
        try {
            AccessibilityServiceInfo info = getServiceInfo();
            if (info != null) {
                info.flags |= AccessibilityServiceInfo.FLAG_REQUEST_FILTER_KEY_EVENTS
                        | AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS;
                info.eventTypes |= AccessibilityEvent.TYPE_WINDOWS_CHANGED
                        | AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED;
                setServiceInfo(info);
            }
        } catch (Exception ignored) {
        }
        lastActionMessage = "Active — Ctrl+Shift+D (PNG) · Ctrl+Shift+C (Color) · Ctrl+Shift+G (History) · Shift+Drag (Zones)";
        notifyActivityStatus();
        AutoUpdater.checkForUpdates(this, false, true, null);
    }

    @Override
    public void onAccessibilityEvent(AccessibilityEvent event) {
        if (event == null) return;
        int type = event.getEventType();
        if (type == AccessibilityEvent.TYPE_WINDOWS_CHANGED
                || type == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {
            inspectWindowsForDrag(false);
        }
    }

    private boolean isDarkMode() {
        int nightMask = getResources().getConfiguration().uiMode & Configuration.UI_MODE_NIGHT_MASK;
        return nightMask == Configuration.UI_MODE_NIGHT_YES;
    }

    @Override
    protected boolean onKeyEvent(KeyEvent event) {
        if (event == null) return false;
        int action = event.getAction();
        int keyCode = event.getKeyCode();

        // Track Shift key press/release for FancyZones window-drag snapping
        if (keyCode == KeyEvent.KEYCODE_SHIFT_LEFT || keyCode == KeyEvent.KEYCODE_SHIFT_RIGHT) {
            if (action == KeyEvent.ACTION_DOWN) {
                if (!shiftKeyHeld && !event.isCtrlPressed() && !event.isAltPressed()) {
                    shiftKeyHeld = true;
                    handler.post(new Runnable() {
                        @Override
                        public void run() {
                            onShiftPressedForSnapZones();
                        }
                    });
                }
            } else if (action == KeyEvent.ACTION_UP) {
                if (shiftKeyHeld) {
                    shiftKeyHeld = false;
                    handler.post(new Runnable() {
                        @Override
                        public void run() {
                            onShiftReleasedForSnapZones();
                        }
                    });
                }
            }
        }

        if (action == KeyEvent.ACTION_DOWN) {
            if (shiftKeyHeld
                    && keyCode != KeyEvent.KEYCODE_SHIFT_LEFT
                    && keyCode != KeyEvent.KEYCODE_SHIFT_RIGHT
                    && keyCode != KeyEvent.KEYCODE_TAB
                    && !(keyCode >= KeyEvent.KEYCODE_1 && keyCode <= KeyEvent.KEYCODE_4)
                    && !(keyCode >= KeyEvent.KEYCODE_NUMPAD_1 && keyCode <= KeyEvent.KEYCODE_NUMPAD_4)) {
                otherKeyPressedDuringShift = true;
                handler.removeCallbacks(shiftShowZonesRunnable);
                if (snapZonesShown && !windowMovedWhileZonesShown && !event.isCtrlPressed()) {
                    handler.post(new Runnable() {
                        @Override
                        public void run() {
                            dismissSnapZonesOverlay(false);
                        }
                    });
                }
            }

            // If SnapZones overlay is visible, handle Tab (cycle layouts), 1-4 (pick zone), or Esc
            if (snapZonesShown && !snapZonesDismissing) {
                if (keyCode == KeyEvent.KEYCODE_TAB) {
                    handler.post(new Runnable() {
                        @Override
                        public void run() {
                            cycleSnapZonesLayoutOnTheFly();
                        }
                    });
                    return true;
                }
                int zoneKeyIdx = -1;
                if (keyCode >= KeyEvent.KEYCODE_1 && keyCode <= KeyEvent.KEYCODE_4) {
                    zoneKeyIdx = keyCode - KeyEvent.KEYCODE_1;
                } else if (keyCode >= KeyEvent.KEYCODE_NUMPAD_1 && keyCode <= KeyEvent.KEYCODE_NUMPAD_4) {
                    zoneKeyIdx = keyCode - KeyEvent.KEYCODE_NUMPAD_1;
                }
                if (zoneKeyIdx >= 0 && zoneKeyIdx < currentSnapZones.size()) {
                    final int chosenIdx = zoneKeyIdx;
                    handler.post(new Runnable() {
                        @Override
                        public void run() {
                            hoveredSnapZoneIndex = chosenIdx;
                            commitHoveredSnapZoneAndDismiss();
                        }
                    });
                    return true;
                }
            }

            // Ctrl + Shift + Z -> Interactive FancyZones Window Snapper for the active window
            if (keyCode == KeyEvent.KEYCODE_Z && event.isCtrlPressed() && event.isShiftPressed()) {
                long now = System.currentTimeMillis();
                if (now - lastSnapZonesTriggerMs > 250L) {
                    lastSnapZonesTriggerMs = now;
                    handler.post(new Runnable() {
                        @Override
                        public void run() {
                            if (snapZonesShown && !snapZonesDismissing) {
                                dismissSnapZonesOverlay(true);
                            } else {
                                triggerSnapZonesInteractive();
                            }
                        }
                    });
                }
                return true;
            }

            // Ctrl + Shift + D -> Clipboard Image to PNG Popup
            if (keyCode == KeyEvent.KEYCODE_D && event.isCtrlPressed() && event.isShiftPressed()) {
                long now = System.currentTimeMillis();
                if (now - lastShortcutTriggerMs > 250L) {
                    lastShortcutTriggerMs = now;
                    handler.post(new Runnable() {
                        @Override
                        public void run() {
                            triggerClipboardToPngPopup();
                        }
                    });
                }
                return true;
            }

            // Ctrl + Shift + C -> Crosshair & Zoom Loupe Color Picker
            if (keyCode == KeyEvent.KEYCODE_C && event.isCtrlPressed() && event.isShiftPressed()) {
                long now = System.currentTimeMillis();
                if (now - lastColorPickerTriggerMs > 250L) {
                    lastColorPickerTriggerMs = now;
                    handler.post(new Runnable() {
                        @Override
                        public void run() {
                            if (colorPickerShown && !colorPickerDismissing) {
                                dismissColorPicker(true);
                            } else {
                                triggerColorPickerOverlay();
                            }
                        }
                    });
                }
                return true;
            }

            // Ctrl + Shift + G -> 100% Local Clipboard History Popup (up to 30 items)
            if (keyCode == KeyEvent.KEYCODE_G && event.isCtrlPressed() && event.isShiftPressed()) {
                long now = System.currentTimeMillis();
                if (now - lastHistoryTriggerMs > 250L) {
                    lastHistoryTriggerMs = now;
                    handler.post(new Runnable() {
                        @Override
                        public void run() {
                            if (historyShown && !historyDismissing) {
                                dismissHistoryPopup(true);
                            } else {
                                triggerClipboardHistoryPopup();
                            }
                        }
                    });
                }
                return true;
            }

            // Detect standard Ctrl + C or Ctrl + X (without Shift) to capture newly copied items locally
            if ((keyCode == KeyEvent.KEYCODE_C || keyCode == KeyEvent.KEYCODE_X)
                    && event.isCtrlPressed() && !event.isShiftPressed() && !event.isAltPressed()) {
                handler.postDelayed(new Runnable() {
                    @Override
                    public void run() {
                        captureSystemClipboardToLocalHistory(true);
                    }
                }, 90L);
            }

            // Escape dismisses whichever overlay is open
            if (keyCode == KeyEvent.KEYCODE_ESCAPE) {
                if (snapZonesShown && !snapZonesDismissing) {
                    handler.post(new Runnable() {
                        @Override
                        public void run() {
                            dismissSnapZonesOverlay(true);
                        }
                    });
                    return true;
                }
                if (colorPickerShown && !colorPickerDismissing) {
                    handler.post(new Runnable() {
                        @Override
                        public void run() {
                            dismissColorPicker(true);
                        }
                    });
                    return true;
                }
                if (historyShown && !historyDismissing) {
                    handler.post(new Runnable() {
                        @Override
                        public void run() {
                            dismissHistoryPopup(true);
                        }
                    });
                    return true;
                }
                if (popupShown && !popupDismissing) {
                    handler.post(new Runnable() {
                        @Override
                        public void run() {
                            dismissPopup(true);
                        }
                    });
                    return true;
                }
            }
        }
        return super.onKeyEvent(event);
    }

    @Override
    public void onInterrupt() {
    }

    @Override
    public boolean onUnbind(android.content.Intent intent) {
        dismissPopup(false);
        dismissColorPicker(false);
        dismissHistoryPopup(false);
        dismissSnapZonesOverlay(false);
        removeFocusProbeViewImmediate();
        instance = null;
        notifyActivityStatus();
        return super.onUnbind(intent);
    }

    @Override
    public void onDestroy() {
        unregisterLocalClipboardMonitor();
        ioExecutor.shutdownNow();
        dismissPopup(false);
        dismissColorPicker(false);
        dismissHistoryPopup(false);
        dismissSnapZonesOverlay(false);
        removeFocusProbeViewImmediate();
        if (lastCapturedScreenBitmap != null && !lastCapturedScreenBitmap.isRecycled()) {
            lastCapturedScreenBitmap.recycle();
            lastCapturedScreenBitmap = null;
        }
        if (lastBlurredScreenBitmap != null && !lastBlurredScreenBitmap.isRecycled()) {
            lastBlurredScreenBitmap.recycle();
            lastBlurredScreenBitmap = null;
        }
        instance = null;
        notifyActivityStatus();
        super.onDestroy();
    }

    // =========================================================================
    // 1. CTRL + SHIFT + D: FROSTED GLASS CLIPBOARD IMAGE TO PNG POPUP
    // =========================================================================

    public void triggerClipboardToPngPopup() {
        if (Looper.myLooper() != Looper.getMainLooper()) {
            handler.post(new Runnable() {
                @Override
                public void run() {
                    triggerClipboardToPngPopup();
                }
            });
            return;
        }

        if (colorPickerShown) {
            dismissColorPicker(false);
        }
        if (historyShown) {
            dismissHistoryPopup(false);
        }

        dismissPopup(false);
        clipboardProcessedForCurrentPopup = false;
        currentPngUri = null;
        currentPngFileName = null;

        // Capture a background snapshot BEFORE adding the popup so the popup card itself can
        // render a genuine blurred backdrop of the exact screen patch underneath it without
        // blurring the rest of the screen!
        captureBackdropForPopupAndShow();
    }

    private void captureBackdropForPopupAndShow() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            final boolean[] shown = new boolean[]{false};
            handler.postDelayed(new Runnable() {
                @Override
                public void run() {
                    if (!shown[0]) {
                        shown[0] = true;
                        openPopupAndProcessClipboard();
                    }
                }
            }, 90);

            int displayId = Display.DEFAULT_DISPLAY;
            try {
                if (windowManager != null && windowManager.getDefaultDisplay() != null) {
                    displayId = windowManager.getDefaultDisplay().getDisplayId();
                }
            } catch (Exception ignored) {
            }

            try {
                takeScreenshot(
                        displayId,
                        getMainExecutor(),
                        new TakeScreenshotCallback() {
                            @Override
                            public void onSuccess(ScreenshotResult screenshot) {
                                try {
                                    HardwareBuffer hb = screenshot.getHardwareBuffer();
                                    ColorSpace cs = screenshot.getColorSpace();
                                    if (hb != null) {
                                        Bitmap hw = Bitmap.wrapHardwareBuffer(hb, cs);
                                        if (hw != null) {
                                            Bitmap sw = hw.copy(Bitmap.Config.ARGB_8888, false);
                                            hw.recycle();
                                            if (sw != null) {
                                                updateCachedScreenBitmaps(sw);
                                            }
                                        }
                                        hb.close();
                                    }
                                } catch (Exception ignored) {
                                }
                                if (!shown[0]) {
                                    shown[0] = true;
                                    openPopupAndProcessClipboard();
                                } else if (popupCard != null) {
                                    popupCard.invalidate();
                                }
                            }

                            @Override
                            public void onFailure(int errorCode) {
                                if (!shown[0]) {
                                    shown[0] = true;
                                    openPopupAndProcessClipboard();
                                }
                            }
                        }
                );
                return;
            } catch (Throwable ignored) {
            }
        }
        openPopupAndProcessClipboard();
    }

    private void updateCachedScreenBitmaps(Bitmap swBitmap) {
        if (lastCapturedScreenBitmap != null && !lastCapturedScreenBitmap.isRecycled()) {
            lastCapturedScreenBitmap.recycle();
        }
        if (lastBlurredScreenBitmap != null && !lastBlurredScreenBitmap.isRecycled()) {
            lastBlurredScreenBitmap.recycle();
        }
        lastCapturedScreenBitmap = swBitmap;
        lastBlurredScreenBitmap = createBlurredBackdropBitmap(swBitmap);
    }

    private void openPopupAndProcessClipboard() {
        buildAndShowPopupWindow();

        int[] delays = new int[]{0, 60, 150, 300};
        for (int delay : delays) {
            handler.postDelayed(new Runnable() {
                @Override
                public void run() {
                    if (popupShown && !popupDismissing && !clipboardProcessedForCurrentPopup) {
                        tryProcessClipboardImage(false);
                    }
                }
            }, delay);
        }
        handler.postDelayed(new Runnable() {
            @Override
            public void run() {
                if (popupShown && !popupDismissing && !clipboardProcessedForCurrentPopup) {
                    tryProcessClipboardImage(true);
                }
            }
        }, 450);
    }

    private void buildAndShowPopupWindow() {
        boolean dark = isDarkMode();
        int textPrimary = dark ? Color.WHITE : Color.parseColor("#181A1F");
        int textSecondary = dark ? Color.parseColor("#D2D6DC") : Color.parseColor("#4A5058");
        int textMuted = dark ? Color.parseColor("#A8B0BA") : Color.parseColor("#68707A");
        int boxSurface = dark ? Color.parseColor("#55101216") : Color.parseColor("#77FFFFFF");
        int accentTeal = dark ? Color.parseColor("#2DD4BF") : Color.parseColor("#0D9488");

        popupRoot = new PopupRootView(this);
        int outerPad = dpToPx(10f);
        popupRoot.setPadding(outerPad, outerPad, outerPad, outerPad);
        popupRoot.setClipChildren(false);
        popupRoot.setClipToPadding(false);

        popupCard = new FrostedGlassCardLayout(this, dark, dpToPx(22f));
        popupCard.setOrientation(LinearLayout.VERTICAL);
        int cardPad = dpToPx(16f);
        popupCard.setPadding(cardPad, dpToPx(14f), cardPad, cardPad);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            popupCard.setElevation(dpToPx(10f));
        }

        LinearLayout headerRow = new LinearLayout(this);
        headerRow.setOrientation(LinearLayout.HORIZONTAL);
        headerRow.setGravity(Gravity.CENTER_VERTICAL);

        ImageView headerIcon = new ImageView(this);
        headerIcon.setImageResource(R.drawable.ic_drop_arrow);
        LinearLayout.LayoutParams iconLp = new LinearLayout.LayoutParams(dpToPx(22f), dpToPx(22f));
        iconLp.rightMargin = dpToPx(8f);
        headerRow.addView(headerIcon, iconLp);

        headerTitleView = new TextView(this);
        headerTitleView.setText("Reading clipboard image...");
        headerTitleView.setTextColor(textPrimary);
        headerTitleView.setTypeface(Typeface.DEFAULT_BOLD);
        headerTitleView.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f);
        headerRow.addView(headerTitleView, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));

        TextView closeBtn = new TextView(this);
        closeBtn.setText("✕");
        closeBtn.setTextColor(textMuted);
        closeBtn.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f);
        closeBtn.setPadding(dpToPx(8f), dpToPx(2f), dpToPx(4f), dpToPx(2f));
        closeBtn.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                dismissPopup(true);
            }
        });
        headerRow.addView(closeBtn);
        popupCard.addView(headerRow);

        subtitleView = new TextView(this);
        subtitleView.setText("Converting clipboard image to PNG in Downloads...");
        subtitleView.setTextColor(textSecondary);
        subtitleView.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f);
        subtitleView.setPadding(0, dpToPx(4f), 0, dpToPx(10f));
        popupCard.addView(subtitleView);

        dragBox = new LinearLayout(this);
        dragBox.setOrientation(LinearLayout.VERTICAL);
        dragBox.setGravity(Gravity.CENTER);
        int boxPad = dpToPx(12f);
        dragBox.setPadding(boxPad, boxPad, boxPad, boxPad);

        GradientDrawable boxBg = new GradientDrawable();
        boxBg.setCornerRadius(dpToPx(14f));
        boxBg.setColor(boxSurface);
        boxBg.setStroke(dpToPx(1.5f), accentTeal);
        dragBox.setBackground(boxBg);

        previewImageView = new ImageView(this);
        previewImageView.setScaleType(ImageView.ScaleType.FIT_CENTER);
        previewImageView.setAdjustViewBounds(true);
        previewImageView.setMaxHeight(dpToPx(150f));
        LinearLayout.LayoutParams imgLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                dpToPx(130f)
        );
        dragBox.addView(previewImageView, imgLp);

        dragHintView = new TextView(this);
        dragHintView.setText("⬇ Click & Drag this PNG into any app");
        dragHintView.setTextColor(accentTeal);
        dragHintView.setTypeface(Typeface.DEFAULT_BOLD);
        dragHintView.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f);
        dragHintView.setGravity(Gravity.CENTER);
        dragHintView.setPadding(0, dpToPx(8f), 0, 0);
        dragBox.addView(dragHintView);

        setupImmediateClickAndDragOnPreview(dragBox);
        popupCard.addView(dragBox, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
        ));

        TextView footerHint = new TextView(this);
        footerHint.setText("Swipe header/footer or Right-click anywhere to dismiss");
        footerHint.setTextColor(textMuted);
        footerHint.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f);
        footerHint.setGravity(Gravity.CENTER);
        footerHint.setPadding(0, dpToPx(10f), 0, 0);
        popupCard.addView(footerHint);

        SwipeDismissTouchListener swipeListener = new SwipeDismissTouchListener();
        headerRow.setOnTouchListener(swipeListener);
        subtitleView.setOnTouchListener(swipeListener);
        footerHint.setOnTouchListener(swipeListener);

        popupRoot.addView(popupCard, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.WRAP_CONTENT
        ));

        popupRoot.setOnDragListener(new View.OnDragListener() {
            @Override
            public boolean onDrag(View v, DragEvent event) {
                if (event.getAction() == DragEvent.ACTION_DRAG_ENDED) {
                    if (event.getResult()) {
                        dismissPopup(true);
                    } else if (popupRoot != null) {
                        popupRoot.animate().alpha(1.0f).setDuration(150).start();
                    }
                }
                return true;
            }
        });

        popupRoot.getViewTreeObserver().addOnWindowFocusChangeListener(
                new ViewTreeObserver.OnWindowFocusChangeListener() {
                    @Override
                    public void onWindowFocusChanged(boolean hasFocus) {
                        if (hasFocus && popupShown && !popupDismissing && !clipboardProcessedForCurrentPopup) {
                            tryProcessClipboardImage(false);
                        }
                    }
                }
        );

        popupParams = new WindowManager.LayoutParams();
        popupParams.type = WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY;
        popupParams.format = PixelFormat.TRANSLUCENT;
        popupParams.gravity = Gravity.TOP | Gravity.RIGHT;
        popupParams.width = dpToPx(326f);
        popupParams.height = WindowManager.LayoutParams.WRAP_CONTENT;
        popupParams.x = dpToPx(18f);
        popupParams.y = dpToPx(46f);
        // NOTE: Do NOT set FLAG_BLUR_BEHIND here, because FLAG_BLUR_BEHIND blurs the entire screen!
        // Instead, FrostedGlassCardLayout blurs ONLY the region inside the popup card itself.
        popupParams.flags = WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
                | WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL
                | WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED;
        popupParams.setTitle("BookTools PNG Popup");

        popupCard.setPivotX(dpToPx(260f));
        popupCard.setPivotY(dpToPx(20f));
        popupCard.setScaleX(0.78f);
        popupCard.setScaleY(0.78f);
        popupCard.setAlpha(0f);

        try {
            windowManager.addView(popupRoot, popupParams);
            popupShown = true;
            popupDismissing = false;

            popupCard.animate()
                    .scaleX(1.0f)
                    .scaleY(1.0f)
                    .alpha(1.0f)
                    .setDuration(220)
                    .setInterpolator(new OvershootInterpolator(1.08f))
                    .start();
        } catch (Exception e) {
            popupShown = false;
            popupDismissing = false;
        }
    }

    /**
     * Custom LinearLayout that renders a genuine frosted blurry glass effect strictly inside
     * its own rounded card bounds (sampling the blurred screen backdrop at its exact screen
     * coordinates + translucent glass sheen + specular glass rim), leaving the rest of the
     * screen 100% sharp and unblurred.
     */
    private class FrostedGlassCardLayout extends LinearLayout {
        private final boolean dark;
        private final float cornerRadius;
        private final Path clipPath = new Path();
        private final RectF cardBounds = new RectF();
        private final RectF innerBounds = new RectF();
        private final Rect screenDestRect = new Rect();
        private final int[] locOnScreen = new int[2];

        private final Paint bmpPaint = new Paint(Paint.FILTER_BITMAP_FLAG);
        private final Paint tintPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint sheenPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint outerStrokePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint innerRimPaint = new Paint(Paint.ANTI_ALIAS_FLAG);

        public FrostedGlassCardLayout(android.content.Context context, boolean dark, float cornerRadius) {
            super(context);
            this.dark = dark;
            this.cornerRadius = cornerRadius;
            setWillNotDraw(false);

            tintPaint.setStyle(Paint.Style.FILL);
            tintPaint.setColor(dark ? Color.parseColor("#A81A1D26") : Color.parseColor("#B8F2F6FC"));

            outerStrokePaint.setStyle(Paint.Style.STROKE);
            outerStrokePaint.setStrokeWidth(dpToPx(1.6f));
            outerStrokePaint.setColor(dark ? Color.parseColor("#882DD4BF") : Color.parseColor("#880D9488"));

            innerRimPaint.setStyle(Paint.Style.STROKE);
            innerRimPaint.setStrokeWidth(dpToPx(1f));
            innerRimPaint.setColor(dark ? 0x55FFFFFF : 0xBBFFFFFF);
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

            // Draw the blurred desktop patch directly behind this card's exact screen position
            if (lastBlurredScreenBitmap != null && !lastBlurredScreenBitmap.isRecycled()
                    && lastCapturedScreenBitmap != null && !lastCapturedScreenBitmap.isRecycled()) {
                getLocationOnScreen(locOnScreen);
                int screenW = lastCapturedScreenBitmap.getWidth();
                int screenH = lastCapturedScreenBitmap.getHeight();
                screenDestRect.set(
                        -locOnScreen[0],
                        -locOnScreen[1],
                        screenW - locOnScreen[0],
                        screenH - locOnScreen[1]
                );
                canvas.drawBitmap(lastBlurredScreenBitmap, null, screenDestRect, bmpPaint);
            }

            // Frosted glass tint + diagonal glass sheen
            canvas.drawRect(cardBounds, tintPaint);
            canvas.drawRect(cardBounds, sheenPaint);
            canvas.restore();

            // Specular dual glass border
            float halfStroke = outerStrokePaint.getStrokeWidth() / 2f;
            innerBounds.set(cardBounds);
            innerBounds.inset(halfStroke, halfStroke);
            canvas.drawRoundRect(innerBounds, cornerRadius, cornerRadius, outerStrokePaint);

            innerBounds.inset(dpToPx(1.2f), dpToPx(1.2f));
            canvas.drawRoundRect(
                    innerBounds,
                    Math.max(0f, cornerRadius - dpToPx(1.2f)),
                    Math.max(0f, cornerRadius - dpToPx(1.2f)),
                    innerRimPaint
            );

            super.onDraw(canvas);
        }
    }

    public void dismissPopup(boolean animate) {
        if (!popupShown || popupRoot == null) {
            return;
        }
        if (!animate || popupCard == null) {
            removePopupViewImmediate();
            return;
        }
        if (popupDismissing) {
            return;
        }
        popupDismissing = true;
        final View rootToRemove = popupRoot;
        popupCard.animate()
                .scaleX(0.80f)
                .scaleY(0.80f)
                .alpha(0f)
                .setDuration(165)
                .setInterpolator(new AccelerateInterpolator(1.2f))
                .withEndAction(new Runnable() {
                    @Override
                    public void run() {
                        if (popupRoot == rootToRemove) {
                            removePopupViewImmediate();
                        }
                    }
                })
                .start();
    }

    private void removePopupViewImmediate() {
        if (previewImageView != null) {
            previewImageView.setImageDrawable(null);
        }
        if (currentPreviewBitmap != null && !currentPreviewBitmap.isRecycled()) {
            currentPreviewBitmap.recycle();
            currentPreviewBitmap = null;
        }
        if (popupRoot != null && windowManager != null) {
            try {
                windowManager.removeViewImmediate(popupRoot);
            } catch (Exception ignored) {
                try {
                    windowManager.removeView(popupRoot);
                } catch (Exception ignored2) {
                }
            }
        }
        popupRoot = null;
        popupCard = null;
        popupShown = false;
        popupDismissing = false;
        releaseScreenBitmapsIfIdle();
    }

    private void releaseScreenBitmapsIfIdle() {
        if (!popupShown && !colorPickerShown && !historyShown && !colorPickerCaptureInFlight) {
            if (lastCapturedScreenBitmap != null && !lastCapturedScreenBitmap.isRecycled()) {
                lastCapturedScreenBitmap.recycle();
            }
            lastCapturedScreenBitmap = null;
            if (lastBlurredScreenBitmap != null && !lastBlurredScreenBitmap.isRecycled()) {
                lastBlurredScreenBitmap.recycle();
            }
            lastBlurredScreenBitmap = null;
        }
    }

    private void tryProcessClipboardImage(boolean isFinalAttempt) {
        if (clipboardProcessedForCurrentPopup) return;

        ClipboardManager cm = getSystemService(ClipboardManager.class);
        if (cm == null) {
            cm = (ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
        }
        if (cm == null) return;

        ClipData clip = null;
        try {
            clip = cm.getPrimaryClip();
        } catch (Exception ignored) {
        }

        if (clip == null || clip.getItemCount() == 0) {
            if (isFinalAttempt) {
                showPopupError("Clipboard is empty", "Copy an image (WebP, JPEG, PNG) first, then press Ctrl + Shift + D.");
            }
            return;
        }

        ContentResolver resolver = getContentResolver();
        Bitmap decodedBitmap = null;
        String baseName = null;

        for (int i = 0; i < clip.getItemCount(); i++) {
            ClipData.Item item = clip.getItemAt(i);
            Uri uri = item.getUri();
            if (uri != null) {
                decodedBitmap = decodeBitmapFromUri(resolver, uri);
                if (decodedBitmap != null) {
                    baseName = extractBaseName(queryDisplayName(resolver, uri));
                    break;
                }
            }
            if (decodedBitmap == null && item.getText() != null) {
                decodedBitmap = decodeBitmapFromDataUriText(item.getText().toString());
            }
            if (decodedBitmap == null && item.getHtmlText() != null) {
                decodedBitmap = decodeBitmapFromDataUriText(item.getHtmlText());
            }
        }

        if (decodedBitmap == null) {
            if (isFinalAttempt) {
                showPopupError(
                        "No image found on clipboard",
                        "Right-click an image (WebP, PNG, JPG) and choose 'Copy image', then press Ctrl + Shift + D."
                );
            }
            return;
        }

        clipboardProcessedForCurrentPopup = true;
        saveBitmapAsPngAndPopulatePopup(decodedBitmap, baseName);
    }

    private Bitmap decodeBitmapFromUri(ContentResolver resolver, Uri uri) {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                ImageDecoder.Source source = ImageDecoder.createSource(resolver, uri);
                return ImageDecoder.decodeBitmap(source, new ImageDecoder.OnHeaderDecodedListener() {
                    @Override
                    public void onHeaderDecoded(ImageDecoder decoder, ImageDecoder.ImageInfo info, ImageDecoder.Source src) {
                        decoder.setAllocator(ImageDecoder.ALLOCATOR_SOFTWARE);
                    }
                });
            }
        } catch (Exception ignored) {
        }
        try (InputStream in = resolver.openInputStream(uri)) {
            if (in != null) {
                return BitmapFactory.decodeStream(in);
            }
        } catch (Exception ignored) {
        }
        return null;
    }

    private Bitmap decodeBitmapFromDataUriText(String text) {
        if (text == null || text.length() > 16 * 1024 * 1024) return null;
        int idx = text.indexOf("data:image/");
        if (idx < 0) return null;
        int base64Marker = text.indexOf(";base64,", idx);
        if (base64Marker < 0) return null;
        int start = base64Marker + ";base64,".length();
        int end = start;
        while (end < text.length()) {
            char c = text.charAt(end);
            if (c == '"' || c == '\'' || c == ')' || c == ' ' || c == '>' || c == '\n' || c == '\r') {
                break;
            }
            end++;
        }
        try {
            byte[] raw = Base64.decode(text.substring(start, end), Base64.DEFAULT);
            return BitmapFactory.decodeByteArray(raw, 0, raw.length);
        } catch (Exception ignored) {
            return null;
        }
    }

    private void saveBitmapAsPngAndPopulatePopup(final Bitmap bitmap, final String baseName) {
        final int fullWidth = bitmap.getWidth();
        final int fullHeight = bitmap.getHeight();
        final int maxPreviewDim = dpToPx(280f);

        ioExecutor.execute(new Runnable() {
            @Override
            public void run() {
                try {
                    String timestamp = new SimpleDateFormat("HHmmss", Locale.US).format(new Date());
                    String cleanBase = (baseName != null && !baseName.isEmpty())
                            ? baseName + "_dl"
                            : "clipboard_" + timestamp + "_dl";
                    final String pngFileName = cleanBase + ".png";

                    final Uri savedUri;
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                        ContentValues values = new ContentValues();
                        values.put(MediaStore.Downloads.DISPLAY_NAME, pngFileName);
                        values.put(MediaStore.Downloads.MIME_TYPE, "image/png");
                        values.put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS);

                        savedUri = getContentResolver().insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values);
                        if (savedUri == null) {
                            throw new IllegalStateException("Could not create PNG in Downloads");
                        }
                        try (OutputStream out = getContentResolver().openOutputStream(savedUri)) {
                            bitmap.compress(Bitmap.CompressFormat.PNG, 100, out);
                        }
                    } else {
                        File dir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS);
                        if (!dir.exists()) dir.mkdirs();
                        File outFile = new File(dir, pngFileName);
                        try (OutputStream out = new FileOutputStream(outFile)) {
                            bitmap.compress(Bitmap.CompressFormat.PNG, 100, out);
                        }
                        savedUri = Uri.fromFile(outFile);
                    }

                    // Create a lightweight downscaled thumbnail for the popup preview and immediately
                    // recycle the full-resolution bitmap so large 4K clipboard images never sit in RAM.
                    Bitmap scaledPreview = bitmap;
                    if (fullWidth > maxPreviewDim || fullHeight > maxPreviewDim) {
                        float ratio = Math.min((float) maxPreviewDim / fullWidth, (float) maxPreviewDim / fullHeight);
                        int pw = Math.max(1, Math.round(fullWidth * ratio));
                        int ph = Math.max(1, Math.round(fullHeight * ratio));
                        scaledPreview = Bitmap.createScaledBitmap(bitmap, pw, ph, true);
                        if (scaledPreview != bitmap) {
                            bitmap.recycle();
                        }
                    }
                    final Bitmap finalPreviewBmp = scaledPreview;

                    // Save to 100% Local Clipboard History (Ctrl + Shift + G) on background thread
                    ClipboardHistoryManager.addImageItem(
                            TopBarDropService.this,
                            savedUri,
                            pngFileName,
                            finalPreviewBmp,
                            fullWidth,
                            fullHeight
                    );

                    handler.post(new Runnable() {
                        @Override
                        public void run() {
                            if (!popupShown || popupDismissing) {
                                if (finalPreviewBmp != null && !finalPreviewBmp.isRecycled()) {
                                    finalPreviewBmp.recycle();
                                }
                                return;
                            }
                            currentPngUri = savedUri;
                            currentPngFileName = pngFileName;
                            if (currentPreviewBitmap != null && currentPreviewBitmap != finalPreviewBmp && !currentPreviewBitmap.isRecycled()) {
                                currentPreviewBitmap.recycle();
                            }
                            currentPreviewBitmap = finalPreviewBmp;

                            headerTitleView.setText("Saved PNG: " + pngFileName);
                            subtitleView.setText(fullWidth + " × " + fullHeight + " px · Downloaded to Downloads");
                            previewImageView.setImageBitmap(finalPreviewBmp);
                            dragHintView.setText("⬇ Click & Drag this PNG into any app");

                            dragBox.setScaleX(0.95f);
                            dragBox.setScaleY(0.95f);
                            dragBox.animate()
                                    .scaleX(1.0f)
                                    .scaleY(1.0f)
                                    .setDuration(160)
                                    .setInterpolator(new OvershootInterpolator(1.2f))
                                    .start();

                            lastActionMessage = "Saved " + pngFileName + " (" + fullWidth + "×" + fullHeight + ") to Downloads";
                            notifyActivityStatus();
                        }
                    });
                } catch (final Exception e) {
                    if (!bitmap.isRecycled()) {
                        bitmap.recycle();
                    }
                    handler.post(new Runnable() {
                        @Override
                        public void run() {
                            showPopupError("Failed to save PNG", e.getMessage());
                        }
                    });
                }
            }
        });
    }

    private void showPopupError(String title, String details) {
        if (!popupShown) return;
        headerTitleView.setText("⚠ " + title);
        subtitleView.setText(details);
        dragHintView.setText("Right-click or swipe to dismiss");
        lastActionMessage = title + " — " + details;
        notifyActivityStatus();
    }

    private String queryDisplayName(ContentResolver resolver, Uri uri) {
        try (Cursor c = resolver.query(uri, new String[]{OpenableColumns.DISPLAY_NAME}, null, null, null)) {
            if (c != null && c.moveToFirst()) {
                return c.getString(0);
            }
        } catch (Exception ignored) {
        }
        return uri.getLastPathSegment();
    }

    private String extractBaseName(String name) {
        if (name == null) return null;
        int slash = Math.max(name.lastIndexOf('/'), name.lastIndexOf(':'));
        if (slash >= 0 && slash + 1 < name.length()) {
            name = name.substring(slash + 1);
        }
        int dot = name.lastIndexOf('.');
        if (dot > 0) {
            name = name.substring(0, dot);
        }
        name = name.replaceAll("[^a-zA-Z0-9._-]", "_");
        return name.isEmpty() ? null : name;
    }

    private void setupImmediateClickAndDragOnPreview(final View target) {
        target.setOnTouchListener(new View.OnTouchListener() {
            private float downX;
            private float downY;
            private boolean dragStarted;
            private final int slop = dpToPx(5f);

            @Override
            public boolean onTouch(View v, MotionEvent event) {
                if ((event.getButtonState() & MotionEvent.BUTTON_SECONDARY) != 0) {
                    dismissPopup(true);
                    return true;
                }

                switch (event.getActionMasked()) {
                    case MotionEvent.ACTION_DOWN:
                        downX = event.getX();
                        downY = event.getY();
                        dragStarted = false;
                        return true;

                    case MotionEvent.ACTION_MOVE:
                        if (!dragStarted && currentPngUri != null) {
                            float dx = event.getX() - downX;
                            float dy = event.getY() - downY;
                            if (Math.hypot(dx, dy) > slop) {
                                dragStarted = true;
                                startPngDragOut(v);
                            }
                        }
                        return true;
                }
                return false;
            }
        });
    }

    private void startPngDragOut(View sourceView) {
        if (currentPngUri == null) return;
        try {
            ClipDescription desc = new ClipDescription(
                    currentPngFileName != null ? currentPngFileName : "image.png",
                    new String[]{"image/png", "application/octet-stream"}
            );
            ClipData.Item item = new ClipData.Item(currentPngUri);
            ClipData clipData = new ClipData(desc, item);

            View.DragShadowBuilder shadow = new View.DragShadowBuilder(
                    previewImageView.getDrawable() != null ? previewImageView : sourceView
            );

            int flags = View.DRAG_FLAG_GLOBAL | View.DRAG_FLAG_GLOBAL_URI_READ;
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                sourceView.startDragAndDrop(clipData, shadow, null, flags);
            } else {
                sourceView.startDrag(clipData, shadow, null, flags);
            }

            if (popupRoot != null) {
                popupRoot.animate().alpha(0.30f).setDuration(120).start();
            }
        } catch (Exception e) {
            Toast.makeText(this, "Drag failed: " + e.getMessage(), Toast.LENGTH_SHORT).show();
        }
    }

    private class SwipeDismissTouchListener implements View.OnTouchListener {
        private float downRawX;
        private boolean swiping;
        private final int touchSlop = ViewConfiguration.get(TopBarDropService.this).getScaledTouchSlop();

        @Override
        public boolean onTouch(View v, MotionEvent event) {
            if ((event.getButtonState() & MotionEvent.BUTTON_SECONDARY) != 0) {
                dismissPopup(true);
                return true;
            }
            switch (event.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    downRawX = event.getRawX();
                    swiping = false;
                    return true;

                case MotionEvent.ACTION_MOVE:
                    float dx = event.getRawX() - downRawX;
                    if (Math.abs(dx) > touchSlop) {
                        swiping = true;
                    }
                    if (swiping && popupCard != null) {
                        popupCard.setTranslationX(dx);
                        popupCard.invalidate();
                        float progress = Math.min(1f, Math.abs(dx) / (float) dpToPx(180f));
                        popupCard.setAlpha(Math.max(0.15f, 1f - progress * 0.85f));
                        float scale = 1f - progress * 0.12f;
                        popupCard.setScaleX(scale);
                        popupCard.setScaleY(scale);
                    }
                    return true;

                case MotionEvent.ACTION_UP:
                case MotionEvent.ACTION_CANCEL:
                    if (swiping && popupCard != null) {
                        float totalDx = event.getRawX() - downRawX;
                        if (Math.abs(totalDx) > dpToPx(65f)) {
                            popupDismissing = true;
                            final View rootToRemove = popupRoot;
                            float targetX = totalDx > 0 ? dpToPx(260f) : -dpToPx(260f);
                            popupCard.animate()
                                    .translationX(targetX)
                                    .scaleX(0.82f)
                                    .scaleY(0.82f)
                                    .alpha(0f)
                                    .setDuration(150)
                                    .withEndAction(new Runnable() {
                                        @Override
                                        public void run() {
                                            if (popupRoot == rootToRemove) {
                                                removePopupViewImmediate();
                                            }
                                        }
                                    })
                                    .start();
                        } else {
                            popupCard.animate()
                                    .translationX(0f)
                                    .scaleX(1f)
                                    .scaleY(1f)
                                    .alpha(1f)
                                    .setDuration(150)
                                    .setInterpolator(new OvershootInterpolator(1.1f))
                                    .start();
                        }
                    }
                    swiping = false;
                    return true;
            }
            return false;
        }
    }

    private class PopupRootView extends FrameLayout {
        public PopupRootView(android.content.Context context) {
            super(context);
            setFocusable(true);
            setFocusableInTouchMode(true);
        }

        @Override
        public boolean dispatchGenericMotionEvent(MotionEvent event) {
            if (event != null && (event.getButtonState() & MotionEvent.BUTTON_SECONDARY) != 0) {
                dismissPopup(true);
                return true;
            }
            return super.dispatchGenericMotionEvent(event);
        }

        @Override
        public boolean dispatchTouchEvent(MotionEvent event) {
            if (event != null && (event.getButtonState() & MotionEvent.BUTTON_SECONDARY) != 0) {
                dismissPopup(true);
                return true;
            }
            return super.dispatchTouchEvent(event);
        }

        @Override
        public boolean dispatchKeyEvent(KeyEvent event) {
            if (event != null && event.getAction() == KeyEvent.ACTION_DOWN) {
                if (event.getKeyCode() == KeyEvent.KEYCODE_ESCAPE) {
                    dismissPopup(true);
                    return true;
                }
            }
            return super.dispatchKeyEvent(event);
        }
    }

    // =========================================================================
    // 2. CTRL + SHIFT + C: SCREEN COLOR PICKER (FROSTED GLASS ZOOM LOUPE -> HEX)
    // =========================================================================

    public void triggerColorPickerOverlay() {
        if (Looper.myLooper() != Looper.getMainLooper()) {
            handler.post(new Runnable() {
                @Override
                public void run() {
                    triggerColorPickerOverlay();
                }
            });
            return;
        }

        if (popupShown) {
            dismissPopup(false);
        }
        if (historyShown) {
            dismissHistoryPopup(false);
        }
        if (colorPickerShown) {
            dismissColorPicker(false);
        }

        lastActionMessage = "Opening Color Picker (Ctrl+Shift+C)...";
        notifyActivityStatus();

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            captureScreenAndOpenColorPicker();
        } else {
            openColorPickerWithFallback("Android 11+ required for full-screen pixel capture");
        }
    }

    private void captureScreenAndOpenColorPicker() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
            openColorPickerWithFallback(null);
            return;
        }

        colorPickerCaptureInFlight = true;

        handler.postDelayed(new Runnable() {
            @Override
            public void run() {
                if (colorPickerCaptureInFlight && !colorPickerShown) {
                    colorPickerCaptureInFlight = false;
                    openColorPickerWithFallback("Toggle BookTools OFF & ON once in Accessibility Settings to refresh screen capture");
                }
            }
        }, 220);

        int displayId = Display.DEFAULT_DISPLAY;
        try {
            if (windowManager != null && windowManager.getDefaultDisplay() != null) {
                displayId = windowManager.getDefaultDisplay().getDisplayId();
            }
        } catch (Exception ignored) {
        }

        try {
            takeScreenshot(
                    displayId,
                    getMainExecutor(),
                    new TakeScreenshotCallback() {
                        @Override
                        public void onSuccess(ScreenshotResult screenshot) {
                            if (!colorPickerCaptureInFlight) return;
                            colorPickerCaptureInFlight = false;

                            Bitmap swBitmap = null;
                            try {
                                HardwareBuffer hb = screenshot.getHardwareBuffer();
                                ColorSpace cs = screenshot.getColorSpace();
                                if (hb != null) {
                                    Bitmap hw = Bitmap.wrapHardwareBuffer(hb, cs);
                                    if (hw != null) {
                                        swBitmap = hw.copy(Bitmap.Config.ARGB_8888, false);
                                        hw.recycle();
                                    }
                                    hb.close();
                                }
                            } catch (Exception ignored) {
                            }

                            if (swBitmap != null) {
                                updateCachedScreenBitmaps(swBitmap);
                                showColorPickerOverlay(swBitmap, null);
                            } else {
                                openColorPickerWithFallback(null);
                            }
                        }

                        @Override
                        public void onFailure(int errorCode) {
                            if (!colorPickerCaptureInFlight) return;
                            colorPickerCaptureInFlight = false;
                            String hint = (errorCode == ERROR_TAKE_SCREENSHOT_NO_ACCESSIBILITY_ACCESS
                                    || errorCode == ERROR_TAKE_SCREENSHOT_INTERNAL_ERROR)
                                    ? "Tip: Toggle BookTools OFF & ON once in Accessibility Settings to enable full-screen pixel capture"
                                    : null;
                            openColorPickerWithFallback(hint);
                        }
                    }
            );
        } catch (Throwable t) {
            colorPickerCaptureInFlight = false;
            openColorPickerWithFallback("Tip: Toggle BookTools OFF & ON once in Accessibility Settings to enable full-screen pixel capture");
        }
    }

    private void openColorPickerWithFallback(String bannerHint) {
        if (lastCapturedScreenBitmap != null && !lastCapturedScreenBitmap.isRecycled()) {
            showColorPickerOverlay(lastCapturedScreenBitmap, bannerHint);
            return;
        }
        if (MainActivity.current != null) {
            Bitmap actBmp = MainActivity.current.captureActivityWindowBitmap();
            if (actBmp != null) {
                updateCachedScreenBitmaps(actBmp);
                showColorPickerOverlay(actBmp, bannerHint);
                return;
            }
        }
        Bitmap fallbackBmp = createFallbackSpectrumBitmap();
        updateCachedScreenBitmaps(fallbackBmp);
        showColorPickerOverlay(fallbackBmp, bannerHint);
    }

    private Bitmap createFallbackSpectrumBitmap() {
        DisplayMetrics dm = getResources().getDisplayMetrics();
        int w = Math.max(800, dm.widthPixels);
        int h = Math.max(600, dm.heightPixels);
        Bitmap bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
        Canvas c = new Canvas(bmp);
        Paint p = new Paint();
        p.setShader(new LinearGradient(0, 0, w, h,
                new int[]{
                        Color.parseColor("#A3E635"),
                        Color.parseColor("#2DD4BF"),
                        Color.parseColor("#0D9488"),
                        Color.parseColor("#3B82F6"),
                        Color.parseColor("#8B5CF6"),
                        Color.parseColor("#EC4899")
                },
                null,
                Shader.TileMode.CLAMP));
        c.drawRect(0, 0, w, h, p);
        return bmp;
    }

    private static Bitmap createBlurredBackdropBitmap(Bitmap src) {
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
                            int nx = clamp(x + k, 0, smallW - 1);
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
                            int ny = clamp(y + k, 0, smallH - 1);
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

    private void showColorPickerOverlay(Bitmap bitmap, String bannerHint) {
        dismissColorPicker(false);

        colorPickerRoot = new ColorPickerRootView(this);
        colorPickerCanvas = new ColorPickerCanvasView(this, bitmap, lastBlurredScreenBitmap, bannerHint, isDarkMode());
        colorPickerRoot.addView(colorPickerCanvas, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
        ));

        colorPickerParams = new WindowManager.LayoutParams();
        colorPickerParams.type = WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY;
        colorPickerParams.format = PixelFormat.TRANSLUCENT;
        colorPickerParams.gravity = Gravity.TOP | Gravity.LEFT;
        colorPickerParams.width = WindowManager.LayoutParams.MATCH_PARENT;
        colorPickerParams.height = WindowManager.LayoutParams.MATCH_PARENT;
        colorPickerParams.flags = WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
                | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS
                | WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED;
        colorPickerParams.setTitle("BookTools Color Picker");

        try {
            windowManager.addView(colorPickerRoot, colorPickerParams);
            colorPickerShown = true;
            colorPickerDismissing = false;
            colorPickerRoot.requestFocus();
            colorPickerCanvas.startAppearAnimation();
            lastActionMessage = "Color Picker active — Click any pixel to copy #HEX (Right-click or Esc to cancel)";
            notifyActivityStatus();
        } catch (Exception e) {
            colorPickerShown = false;
            colorPickerDismissing = false;
            lastActionMessage = "Color Picker error: " + e.getMessage();
            notifyActivityStatus();
        }
    }

    public void dismissColorPicker(boolean animate) {
        if (!colorPickerShown || colorPickerRoot == null) {
            return;
        }
        if (!animate || colorPickerCanvas == null) {
            removeColorPickerImmediate();
            return;
        }
        if (colorPickerDismissing) {
            return;
        }
        colorPickerDismissing = true;
        final View rootToRemove = colorPickerRoot;
        colorPickerCanvas.startDisappearAnimation(new Runnable() {
            @Override
            public void run() {
                if (colorPickerRoot == rootToRemove) {
                    removeColorPickerImmediate();
                }
            }
        });
    }

    private void removeColorPickerImmediate() {
        if (colorPickerCanvas != null) {
            colorPickerCanvas.cancelRunningAnimator();
        }
        if (colorPickerRoot != null && windowManager != null) {
            try {
                windowManager.removeViewImmediate(colorPickerRoot);
            } catch (Exception ignored) {
                try {
                    windowManager.removeView(colorPickerRoot);
                } catch (Exception ignored2) {
                }
            }
        }
        colorPickerRoot = null;
        colorPickerCanvas = null;
        colorPickerShown = false;
        colorPickerDismissing = false;
        releaseScreenBitmapsIfIdle();
    }

    private class ColorPickerRootView extends FrameLayout {
        public ColorPickerRootView(android.content.Context context) {
            super(context);
            setFocusable(true);
            setFocusableInTouchMode(true);
        }

        @Override
        public boolean dispatchGenericMotionEvent(MotionEvent event) {
            if (event != null) {
                if ((event.getButtonState() & MotionEvent.BUTTON_SECONDARY) != 0) {
                    dismissColorPicker(true);
                    return true;
                }
                if (colorPickerCanvas != null) {
                    colorPickerCanvas.updatePointerFromMotionEvent(event);
                }
            }
            return super.dispatchGenericMotionEvent(event);
        }

        @Override
        public boolean dispatchTouchEvent(MotionEvent event) {
            if (event != null && (event.getButtonState() & MotionEvent.BUTTON_SECONDARY) != 0) {
                dismissColorPicker(true);
                return true;
            }
            return super.dispatchTouchEvent(event);
        }

        @Override
        public boolean dispatchKeyEvent(KeyEvent event) {
            if (event != null && event.getAction() == KeyEvent.ACTION_DOWN) {
                int code = event.getKeyCode();
                if (code == KeyEvent.KEYCODE_ESCAPE) {
                    dismissColorPicker(true);
                    return true;
                }
                if (code == KeyEvent.KEYCODE_ENTER || code == KeyEvent.KEYCODE_SPACE || code == KeyEvent.KEYCODE_NUMPAD_ENTER) {
                    if (colorPickerCanvas != null) {
                        colorPickerCanvas.pickCurrentColorAndClose();
                        return true;
                    }
                }
                // 1px precision nudging with Arrow Keys (or 10px with Shift + Arrow)
                if (colorPickerCanvas != null) {
                    int step = event.isShiftPressed() ? 10 : 1;
                    if (code == KeyEvent.KEYCODE_DPAD_LEFT) {
                        colorPickerCanvas.nudgePointerByPixels(-step, 0);
                        return true;
                    } else if (code == KeyEvent.KEYCODE_DPAD_RIGHT) {
                        colorPickerCanvas.nudgePointerByPixels(step, 0);
                        return true;
                    } else if (code == KeyEvent.KEYCODE_DPAD_UP) {
                        colorPickerCanvas.nudgePointerByPixels(0, -step);
                        return true;
                    } else if (code == KeyEvent.KEYCODE_DPAD_DOWN) {
                        colorPickerCanvas.nudgePointerByPixels(0, step);
                        return true;
                    }
                }
            }
            return super.dispatchKeyEvent(event);
        }
    }

    private class ColorPickerCanvasView extends View {
        private final Bitmap screenBmp;
        private final Bitmap blurredBmp;
        private final String bannerHint;
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
        private final Paint cardStrokePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint glassRimPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
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

        public ColorPickerCanvasView(android.content.Context context, Bitmap bitmap, Bitmap blurred, String hint, boolean dark) {
            super(context);
            this.screenBmp = bitmap;
            this.blurredBmp = blurred != null ? blurred : createBlurredBackdropBitmap(bitmap);
            this.bannerHint = hint;

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                setPointerIcon(PointerIcon.getSystemIcon(context, PointerIcon.TYPE_CROSSHAIR));
            }

            gridLinePaint.setStyle(Paint.Style.STROKE);
            gridLinePaint.setStrokeWidth(1f);
            gridLinePaint.setColor(0x28FFFFFF);

            glassTintPaint.setStyle(Paint.Style.FILL);
            glassTintPaint.setColor(dark ? Color.parseColor("#A6181A22") : Color.parseColor("#B8F4F8FC"));

            cardStrokePaint.setStyle(Paint.Style.STROKE);
            cardStrokePaint.setStrokeWidth(dpToPx(1.8f));
            cardStrokePaint.setColor(dark ? Color.parseColor("#992DD4BF") : Color.parseColor("#990D9488"));

            glassRimPaint.setStyle(Paint.Style.STROKE);
            glassRimPaint.setStrokeWidth(dpToPx(1f));
            glassRimPaint.setColor(dark ? 0x55FFFFFF : 0xAAFFFFFF);

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
            crosshairDarkPaint.setStrokeWidth(dpToPx(3f));
            crosshairDarkPaint.setColor(0xDD000000);
            crosshairDarkPaint.setStrokeCap(Paint.Cap.ROUND);

            crosshairLightPaint.setStyle(Paint.Style.STROKE);
            crosshairLightPaint.setStrokeWidth(dpToPx(1.6f));
            crosshairLightPaint.setColor(0xFFFFFFFF);
            crosshairLightPaint.setStrokeCap(Paint.Cap.ROUND);

            centerOuterPaint.setStyle(Paint.Style.STROKE);
            centerOuterPaint.setStrokeWidth(dpToPx(2f));
            centerOuterPaint.setColor(Color.WHITE);

            centerInnerPaint.setStyle(Paint.Style.STROKE);
            centerInnerPaint.setStrokeWidth(dpToPx(1f));
            centerInnerPaint.setColor(Color.BLACK);
        }

        public void cancelRunningAnimator() {
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

        public void startAppearAnimation() {
            if (currentAnimator != null) {
                currentAnimator.cancel();
            }
            animProgress = 0f;
            currentAnimator = ValueAnimator.ofFloat(0f, 1f);
            currentAnimator.setDuration(190);
            currentAnimator.setInterpolator(new OvershootInterpolator(1.08f));
            currentAnimator.addUpdateListener(new ValueAnimator.AnimatorUpdateListener() {
                @Override
                public void onAnimationUpdate(ValueAnimator animation) {
                    animProgress = (Float) animation.getAnimatedValue();
                    invalidate();
                }
            });
            currentAnimator.start();
        }

        public void startDisappearAnimation(final Runnable onEnd) {
            if (currentAnimator != null) {
                currentAnimator.cancel();
            }
            currentAnimator = ValueAnimator.ofFloat(animProgress, 0f);
            currentAnimator.setDuration(150);
            currentAnimator.setInterpolator(new AccelerateInterpolator(1.2f));
            currentAnimator.addUpdateListener(new ValueAnimator.AnimatorUpdateListener() {
                @Override
                public void onAnimationUpdate(ValueAnimator animation) {
                    animProgress = (Float) animation.getAnimatedValue();
                    invalidate();
                }
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

        public void updatePointerFromMotionEvent(MotionEvent event) {
            pointerX = event.getX();
            pointerY = event.getY();
            invalidate();
        }

        public void nudgePointerByPixels(int dx, int dy) {
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

            canvas.drawRoundRect(bounds, radius, radius, cardStrokePaint);
            tempRect.set(bounds);
            tempRect.inset(dpToPx(1.2f), dpToPx(1.2f));
            canvas.drawRoundRect(tempRect, Math.max(0f, radius - dpToPx(1.2f)), Math.max(0f, radius - dpToPx(1.2f)), glassRimPaint);
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
            float pillWidth = bannerTextPaint.measureText(topMsg) + dpToPx(34f);
            float pillHeight = dpToPx(36f);
            float pillLeft = (viewW - pillWidth) / 2f;
            float pillTop = dpToPx(50f);
            RectF pillBounds = new RectF(pillLeft, pillTop, pillLeft + pillWidth, pillTop + pillHeight);
            canvas.save();
            canvas.scale(scale, scale, viewW / 2f, pillTop + pillHeight / 2f);
            drawFrostedGlassCard(canvas, pillBounds, dpToPx(18f), screenBmp.getWidth(), screenBmp.getHeight());
            canvas.drawText(topMsg, viewW / 2f, pillTop + dpToPx(23f), bannerTextPaint);
            canvas.restore();

            // 2. Draw high-contrast precision Crosshair at (pointerX, pointerY)
            float chGap = dpToPx(5f);
            float chLen = dpToPx(18f);

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
            float cellPx = dpToPx(11f);
            float gridSize = cellPx * gridCount;
            float pad = dpToPx(10f);
            float infoHeight = dpToPx(46f);
            float cardW = gridSize + pad * 2f;
            float cardH = gridSize + pad * 2f + infoHeight;
            float cursorOffset = dpToPx(26f);
            float screenEdgeMargin = dpToPx(14f);

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
            drawFrostedGlassCard(canvas, cardBounds, dpToPx(18f), screenBmp.getWidth(), screenBmp.getHeight());

            // Draw Magnified 11x11 Pixel Grid inside rounded clip
            float gridLeft = cardLeft + pad;
            float gridTop = cardTop + pad;
            tempRect.set(gridLeft, gridTop, gridLeft + gridSize, gridTop + gridSize);

            canvas.save();
            clipPath.reset();
            clipPath.addRoundRect(tempRect, dpToPx(10f), dpToPx(10f), Path.Direction.CW);
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
            tempRect.inset(dpToPx(1.5f), dpToPx(1.5f));
            canvas.drawRect(tempRect, centerInnerPaint);

            // Draw Color Swatch + #RRGGBB HEX + RGB(r,g,b) below the magnified grid
            float infoTop = gridTop + gridSize + dpToPx(8f);
            float swatchSize = dpToPx(24f);
            float swatchLeft = gridLeft + dpToPx(2f);
            float swatchTop = infoTop + dpToPx(4f);

            tempRect.set(swatchLeft, swatchTop, swatchLeft + swatchSize, swatchTop + swatchSize);
            pixelPaint.setColor(centerColor);
            pixelPaint.setAntiAlias(true);
            canvas.drawRoundRect(tempRect, dpToPx(6f), dpToPx(6f), pixelPaint);
            canvas.drawRoundRect(tempRect, dpToPx(6f), dpToPx(6f), cardStrokePaint);

            float textLeft = swatchLeft + swatchSize + dpToPx(8f);
            canvas.drawText(hex, textLeft, swatchTop + dpToPx(13f), textPaint);
            canvas.drawText("RGB(" + r + "," + g + "," + b + ")", textLeft, swatchTop + dpToPx(26f), subTextPaint);

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
                dismissColorPicker(true);
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

        public void pickCurrentColorAndClose() {
            if (screenBmp == null || screenBmp.isRecycled()) {
                dismissColorPicker(true);
                return;
            }
            int bmpX = clamp(Math.round(pointerX), 0, screenBmp.getWidth() - 1);
            int bmpY = clamp(Math.round(pointerY), 0, screenBmp.getHeight() - 1);
            int pickedColor = screenBmp.getPixel(bmpX, bmpY);
            String hex = String.format(Locale.US, "#%06X", (0xFFFFFF & pickedColor));

            ClipboardHistoryManager.markInternalClipboardWrite();
            ClipboardManager cm = getSystemService(ClipboardManager.class);
            if (cm == null) {
                cm = (ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
            }
            if (cm != null) {
                cm.setPrimaryClip(ClipData.newPlainText("Color HEX", hex));
            }
            ClipboardHistoryManager.addTextOrColorItem(TopBarDropService.this, hex);

            lastActionMessage = "Copied color " + hex + " to clipboard";
            notifyActivityStatus();
            showCopiedColorBadge(pickedColor, hex);
            dismissColorPicker(true);
        }
    }

    private void showCopiedColorBadge(int color, String hex) {
        showFloatingStatusPill(color, true, "Copied " + hex + " to clipboard");
    }

    private void showFloatingStatusPill(int swatchColor, boolean showColorSwatch, String message) {
        boolean dark = isDarkMode();
        final FrameLayout badgeRoot = new FrameLayout(this);
        int outerPad = dpToPx(8f);
        badgeRoot.setPadding(outerPad, outerPad, outerPad, outerPad);

        final FrostedGlassCardLayout pill = new FrostedGlassCardLayout(this, dark, dpToPx(24f));
        pill.setOrientation(LinearLayout.HORIZONTAL);
        pill.setGravity(Gravity.CENTER_VERTICAL);
        pill.setPadding(dpToPx(14f), dpToPx(10f), dpToPx(16f), dpToPx(10f));

        if (showColorSwatch) {
            View swatch = new View(this);
            GradientDrawable swatchBg = new GradientDrawable();
            swatchBg.setShape(GradientDrawable.OVAL);
            swatchBg.setColor(swatchColor);
            swatchBg.setStroke(dpToPx(1.5f), dark ? Color.WHITE : Color.parseColor("#202124"));
            swatch.setBackground(swatchBg);
            LinearLayout.LayoutParams swatchLp = new LinearLayout.LayoutParams(dpToPx(20f), dpToPx(20f));
            swatchLp.rightMargin = dpToPx(10f);
            pill.addView(swatch, swatchLp);
        }

        TextView label = new TextView(this);
        label.setText(message);
        label.setTextColor(dark ? Color.WHITE : Color.parseColor("#181A1F"));
        label.setTypeface(Typeface.DEFAULT_BOLD);
        label.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f);
        pill.addView(label);

        badgeRoot.addView(pill);

        WindowManager.LayoutParams lp = new WindowManager.LayoutParams();
        lp.type = WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY;
        lp.format = PixelFormat.TRANSLUCENT;
        lp.gravity = Gravity.TOP | Gravity.RIGHT;
        lp.width = WindowManager.LayoutParams.WRAP_CONTENT;
        lp.height = WindowManager.LayoutParams.WRAP_CONTENT;
        lp.x = dpToPx(18f);
        lp.y = dpToPx(46f);
        // No FLAG_BLUR_BEHIND so the screen stays 100% sharp!
        lp.flags = WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
                | WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                | WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
                | WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED;

        pill.setScaleX(0.75f);
        pill.setScaleY(0.75f);
        pill.setAlpha(0f);

        try {
            windowManager.addView(badgeRoot, lp);
            pill.animate()
                    .scaleX(1f)
                    .scaleY(1f)
                    .alpha(1f)
                    .setDuration(180)
                    .setInterpolator(new OvershootInterpolator(1.15f))
                    .start();

            handler.postDelayed(new Runnable() {
                @Override
                public void run() {
                    pill.animate()
                            .scaleX(0.8f)
                            .scaleY(0.8f)
                            .alpha(0f)
                            .setDuration(160)
                            .setInterpolator(new AccelerateInterpolator())
                            .withEndAction(new Runnable() {
                                @Override
                                public void run() {
                                    try {
                                        windowManager.removeView(badgeRoot);
                                    } catch (Exception ignored) {
                                    }
                                }
                            })
                            .start();
                }
            }, 1500);
        } catch (Exception ignored) {
        }
    }

    // =========================================================================
    // 3. CTRL + SHIFT + G: 100% LOCAL CLIPBOARD HISTORY & MULTI-DROP POPUP
    // =========================================================================

    private void registerLocalClipboardMonitor() {
        try {
            clipboardManager = getSystemService(ClipboardManager.class);
            if (clipboardManager == null) {
                clipboardManager = (ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
            }
            if (clipboardManager != null && clipChangedListener == null) {
                clipChangedListener = new ClipboardManager.OnPrimaryClipChangedListener() {
                    @Override
                    public void onPrimaryClipChanged() {
                        if (ClipboardHistoryManager.isInternalClipboardWriteSuppressed()) {
                            return;
                        }
                        handler.postDelayed(new Runnable() {
                            @Override
                            public void run() {
                                captureSystemClipboardToLocalHistory(true);
                            }
                        }, 60L);
                    }
                };
                clipboardManager.addPrimaryClipChangedListener(clipChangedListener);
            }
        } catch (Exception ignored) {
        }
    }

    private void unregisterLocalClipboardMonitor() {
        try {
            if (clipboardManager != null && clipChangedListener != null) {
                clipboardManager.removePrimaryClipChangedListener(clipChangedListener);
                clipChangedListener = null;
            }
        } catch (Exception ignored) {
        }
    }

    private void captureSystemClipboardToLocalHistory(boolean allowFocusProbeIfUnfocused) {
        if (ClipboardHistoryManager.isInternalClipboardWriteSuppressed()) {
            return;
        }
        if (clipboardManager == null) {
            clipboardManager = getSystemService(ClipboardManager.class);
        }
        if (clipboardManager == null) return;

        ClipData clip = null;
        try {
            clip = clipboardManager.getPrimaryClip();
        } catch (Exception ignored) {
        }

        if (clip != null && clip.getItemCount() > 0) {
            ingestClipDataIntoLocalHistory(clip);
            if (historyShown && !historyDismissing) {
                populateHistoryItemsList();
            }
            return;
        }

        // On Android 10–17, getPrimaryClip() returns null when no window of the service has focus.
        // Briefly attach a 1x1 transparent focus probe for ~15ms to read the new clip locally.
        if (allowFocusProbeIfUnfocused && !popupShown && !colorPickerShown && !historyShown && focusProbeView == null) {
            attachBriefFocusProbeForClipboard();
        }
    }

    private void attachBriefFocusProbeForClipboard() {
        if (windowManager == null || focusProbeView != null) return;
        try {
            final View probe = new View(this);
            probe.setFocusable(true);
            probe.setFocusableInTouchMode(true);
            probe.getViewTreeObserver().addOnWindowFocusChangeListener(
                    new ViewTreeObserver.OnWindowFocusChangeListener() {
                        @Override
                        public void onWindowFocusChanged(boolean hasFocus) {
                            if (hasFocus) {
                                try {
                                    if (clipboardManager != null) {
                                        ClipData clip = clipboardManager.getPrimaryClip();
                                        if (clip != null && clip.getItemCount() > 0) {
                                            ingestClipDataIntoLocalHistory(clip);
                                        }
                                    }
                                } catch (Exception ignored) {
                                } finally {
                                    removeFocusProbeViewImmediate();
                                }
                            }
                        }
                    }
            );

            WindowManager.LayoutParams lp = new WindowManager.LayoutParams();
            lp.type = WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY;
            lp.format = PixelFormat.TRANSLUCENT;
            lp.gravity = Gravity.TOP | Gravity.LEFT;
            lp.width = 1;
            lp.height = 1;
            lp.x = 0;
            lp.y = 0;
            lp.flags = WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL
                    | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
                    | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS;
            lp.windowAnimations = 0;

            focusProbeView = probe;
            windowManager.addView(probe, lp);
            probe.requestFocus();

            handler.postDelayed(new Runnable() {
                @Override
                public void run() {
                    removeFocusProbeViewImmediate();
                }
            }, 140L);
        } catch (Exception ignored) {
            removeFocusProbeViewImmediate();
        }
    }

    private void removeFocusProbeViewImmediate() {
        if (focusProbeView != null && windowManager != null) {
            try {
                windowManager.removeViewImmediate(focusProbeView);
            } catch (Exception ignored) {
                try {
                    windowManager.removeView(focusProbeView);
                } catch (Exception ignored2) {
                }
            }
        }
        focusProbeView = null;
    }

    private void ingestClipDataIntoLocalHistory(ClipData clip) {
        if (clip == null || clip.getItemCount() == 0) return;
        // Privacy & Security: Never record sensitive clips (e.g., passwords from password managers)
        if (ClipboardHistoryManager.isSensitiveClip(clip)) {
            return;
        }
        final ContentResolver resolver = getContentResolver();

        for (int i = 0; i < clip.getItemCount(); i++) {
            ClipData.Item item = clip.getItemAt(i);
            if (item == null) continue;

            final Uri uri = item.getUri();
            if (uri != null) {
                ioExecutor.execute(new Runnable() {
                    @Override
                    public void run() {
                        Bitmap bmp = decodeBitmapFromUri(resolver, uri);
                        if (bmp != null) {
                            saveClipBitmapQuietlyToHistory(bmp, extractBaseName(queryDisplayName(resolver, uri)));
                        }
                    }
                });
                continue;
            }

            CharSequence textCs = item.getText();
            if (textCs != null && textCs.length() > 0) {
                final String text = textCs.toString();
                if (text.contains("data:image/")) {
                    ioExecutor.execute(new Runnable() {
                        @Override
                        public void run() {
                            Bitmap dataBmp = decodeBitmapFromDataUriText(text);
                            if (dataBmp != null) {
                                saveClipBitmapQuietlyToHistory(dataBmp, null);
                            }
                        }
                    });
                } else {
                    ClipboardHistoryManager.addTextOrColorItem(this, text);
                    notifyActivityStatus();
                }
                continue;
            }

            final String html = item.getHtmlText();
            if (html != null && html.contains("data:image/")) {
                ioExecutor.execute(new Runnable() {
                    @Override
                    public void run() {
                        Bitmap dataBmp = decodeBitmapFromDataUriText(html);
                        if (dataBmp != null) {
                            saveClipBitmapQuietlyToHistory(dataBmp, null);
                        }
                    }
                });
            }
        }
    }

    private void saveClipBitmapQuietlyToHistory(Bitmap bitmap, String baseName) {
        if (bitmap == null || bitmap.isRecycled()) return;
        int fullWidth = bitmap.getWidth();
        int fullHeight = bitmap.getHeight();
        try {
            String timestamp = new SimpleDateFormat("HHmmss", Locale.US).format(new Date());
            String cleanBase = (baseName != null && !baseName.isEmpty())
                    ? baseName + "_dl"
                    : "clipboard_" + timestamp + "_dl";
            String pngFileName = cleanBase + ".png";

            Uri savedUri;
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                ContentValues values = new ContentValues();
                values.put(MediaStore.Downloads.DISPLAY_NAME, pngFileName);
                values.put(MediaStore.Downloads.MIME_TYPE, "image/png");
                values.put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS);
                savedUri = getContentResolver().insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values);
                if (savedUri == null) return;
                try (OutputStream out = getContentResolver().openOutputStream(savedUri)) {
                    bitmap.compress(Bitmap.CompressFormat.PNG, 100, out);
                }
            } else {
                File dir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS);
                if (!dir.exists()) dir.mkdirs();
                File outFile = new File(dir, pngFileName);
                try (OutputStream out = new FileOutputStream(outFile)) {
                    bitmap.compress(Bitmap.CompressFormat.PNG, 100, out);
                }
                savedUri = Uri.fromFile(outFile);
            }

            ClipboardHistoryManager.addImageItem(this, savedUri, pngFileName, bitmap, fullWidth, fullHeight);
            handler.post(new Runnable() {
                @Override
                public void run() {
                    if (historyShown && !historyDismissing) {
                        populateHistoryItemsList();
                    }
                    notifyActivityStatus();
                }
            });
        } catch (Exception ignored) {
        } finally {
            if (!bitmap.isRecycled()) {
                bitmap.recycle();
            }
        }
    }

    public void triggerClipboardHistoryPopup() {
        if (Looper.myLooper() != Looper.getMainLooper()) {
            handler.post(new Runnable() {
                @Override
                public void run() {
                    triggerClipboardHistoryPopup();
                }
            });
            return;
        }

        if (popupShown) {
            dismissPopup(false);
        }
        if (colorPickerShown) {
            dismissColorPicker(false);
        }
        dismissHistoryPopup(false);
        historySyncedCurrentClip = false;

        captureBackdropAndOpenHistoryPopup();
    }

    private void captureBackdropAndOpenHistoryPopup() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            final boolean[] shown = new boolean[]{false};
            handler.postDelayed(new Runnable() {
                @Override
                public void run() {
                    if (!shown[0]) {
                        shown[0] = true;
                        buildAndShowHistoryPopupWindow();
                    }
                }
            }, 85L);

            int displayId = Display.DEFAULT_DISPLAY;
            try {
                if (windowManager != null && windowManager.getDefaultDisplay() != null) {
                    displayId = windowManager.getDefaultDisplay().getDisplayId();
                }
            } catch (Exception ignored) {
            }

            try {
                takeScreenshot(
                        displayId,
                        getMainExecutor(),
                        new TakeScreenshotCallback() {
                            @Override
                            public void onSuccess(ScreenshotResult screenshot) {
                                try {
                                    HardwareBuffer hb = screenshot.getHardwareBuffer();
                                    ColorSpace cs = screenshot.getColorSpace();
                                    if (hb != null) {
                                        Bitmap hw = Bitmap.wrapHardwareBuffer(hb, cs);
                                        if (hw != null) {
                                            Bitmap sw = hw.copy(Bitmap.Config.ARGB_8888, false);
                                            hw.recycle();
                                            if (sw != null) {
                                                updateCachedScreenBitmaps(sw);
                                            }
                                        }
                                        hb.close();
                                    }
                                } catch (Exception ignored) {
                                }
                                if (!shown[0]) {
                                    shown[0] = true;
                                    buildAndShowHistoryPopupWindow();
                                } else if (historyCard != null) {
                                    historyCard.invalidate();
                                }
                            }

                            @Override
                            public void onFailure(int errorCode) {
                                if (!shown[0]) {
                                    shown[0] = true;
                                    buildAndShowHistoryPopupWindow();
                                }
                            }
                        }
                );
                return;
            } catch (Throwable ignored) {
            }
        }
        buildAndShowHistoryPopupWindow();
    }

    private void buildAndShowHistoryPopupWindow() {
        boolean dark = isDarkMode();
        int textPrimary = dark ? Color.WHITE : Color.parseColor("#181A1F");
        int textSecondary = dark ? Color.parseColor("#D2D6DC") : Color.parseColor("#4A5058");
        int textMuted = dark ? Color.parseColor("#A8B0BA") : Color.parseColor("#68707A");
        int accentTeal = dark ? Color.parseColor("#2DD4BF") : Color.parseColor("#0D9488");

        historyRoot = new HistoryRootView(this);
        int outerPad = dpToPx(10f);
        historyRoot.setPadding(outerPad, outerPad, outerPad, outerPad);
        historyRoot.setClipChildren(false);
        historyRoot.setClipToPadding(false);

        historyCard = new FrostedGlassCardLayout(this, dark, dpToPx(22f));
        historyCard.setOrientation(LinearLayout.VERTICAL);
        int cardPad = dpToPx(16f);
        historyCard.setPadding(cardPad, dpToPx(14f), cardPad, cardPad);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            historyCard.setElevation(dpToPx(10f));
        }

        // Header Row: Icon + "Local Clipboard History" + "Clear All" + "✕"
        LinearLayout headerRow = new LinearLayout(this);
        headerRow.setOrientation(LinearLayout.HORIZONTAL);
        headerRow.setGravity(Gravity.CENTER_VERTICAL);

        ImageView headerIcon = new ImageView(this);
        headerIcon.setImageResource(R.drawable.ic_drop_arrow);
        LinearLayout.LayoutParams iconLp = new LinearLayout.LayoutParams(dpToPx(22f), dpToPx(22f));
        iconLp.rightMargin = dpToPx(8f);
        headerRow.addView(headerIcon, iconLp);

        TextView titleView = new TextView(this);
        titleView.setText("Local Clipboard History");
        titleView.setTextColor(textPrimary);
        titleView.setTypeface(Typeface.DEFAULT_BOLD);
        titleView.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14.5f);
        headerRow.addView(titleView, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));

        TextView clearBtn = new TextView(this);
        clearBtn.setText("Clear All");
        clearBtn.setTextColor(accentTeal);
        clearBtn.setTypeface(Typeface.DEFAULT_BOLD);
        clearBtn.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11.5f);
        clearBtn.setPadding(dpToPx(10f), dpToPx(4f), dpToPx(10f), dpToPx(4f));
        GradientDrawable clearBg = new GradientDrawable();
        clearBg.setCornerRadius(dpToPx(12f));
        clearBg.setColor(dark ? Color.parseColor("#332DD4BF") : Color.parseColor("#1F0D9488"));
        clearBg.setStroke(dpToPx(1f), dark ? Color.parseColor("#662DD4BF") : Color.parseColor("#550D9488"));
        clearBtn.setBackground(clearBg);
        clearBtn.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                ClipboardHistoryManager.clearAll(TopBarDropService.this);
                populateHistoryItemsList();
                lastActionMessage = "Cleared local clipboard history";
                notifyActivityStatus();
            }
        });
        LinearLayout.LayoutParams clearLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
        );
        clearLp.rightMargin = dpToPx(6f);
        headerRow.addView(clearBtn, clearLp);

        TextView closeBtn = new TextView(this);
        closeBtn.setText("✕");
        closeBtn.setTextColor(textMuted);
        closeBtn.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f);
        closeBtn.setPadding(dpToPx(8f), dpToPx(2f), dpToPx(4f), dpToPx(2f));
        closeBtn.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                dismissHistoryPopup(true);
            }
        });
        headerRow.addView(closeBtn);
        historyCard.addView(headerRow);

        historySubtitleView = new TextView(this);
        historySubtitleView.setText("100% On-Device · Click any item to copy or Drag into any app");
        historySubtitleView.setTextColor(textSecondary);
        historySubtitleView.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11.5f);
        historySubtitleView.setPadding(0, dpToPx(4f), 0, dpToPx(10f));
        historyCard.addView(historySubtitleView);

        // Scrollable container for up to 30 local history items
        ScrollView scrollView = new ScrollView(this) {
            @Override
            protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
                int maxH = dpToPx(360f);
                int cappedSpec = MeasureSpec.makeMeasureSpec(maxH, MeasureSpec.AT_MOST);
                super.onMeasure(widthMeasureSpec, cappedSpec);
            }
        };
        scrollView.setVerticalScrollBarEnabled(true);
        scrollView.setOverScrollMode(View.OVER_SCROLL_IF_CONTENT_SCROLLS);

        historyItemsContainer = new LinearLayout(this);
        historyItemsContainer.setOrientation(LinearLayout.VERTICAL);
        scrollView.addView(historyItemsContainer, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.WRAP_CONTENT
        ));
        historyCard.addView(scrollView, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
        ));

        TextView footerHint = new TextView(this);
        footerHint.setText("Ctrl+Shift+G or Right-click / Esc to close · Stored 100% locally");
        footerHint.setTextColor(textMuted);
        footerHint.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f);
        footerHint.setGravity(Gravity.CENTER);
        footerHint.setPadding(0, dpToPx(10f), 0, 0);
        historyCard.addView(footerHint);

        HistorySwipeDismissListener swipeListener = new HistorySwipeDismissListener();
        headerRow.setOnTouchListener(swipeListener);
        historySubtitleView.setOnTouchListener(swipeListener);
        footerHint.setOnTouchListener(swipeListener);

        historyRoot.addView(historyCard, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.WRAP_CONTENT
        ));

        historyRoot.setOnDragListener(new View.OnDragListener() {
            @Override
            public boolean onDrag(View v, DragEvent event) {
                if (event.getAction() == DragEvent.ACTION_DRAG_ENDED) {
                    if (event.getResult()) {
                        dismissHistoryPopup(true);
                    } else if (historyRoot != null) {
                        historyRoot.animate().alpha(1.0f).setDuration(150).start();
                    }
                }
                return true;
            }
        });

        historyRoot.getViewTreeObserver().addOnWindowFocusChangeListener(
                new ViewTreeObserver.OnWindowFocusChangeListener() {
                    @Override
                    public void onWindowFocusChanged(boolean hasFocus) {
                        if (hasFocus && historyShown && !historyDismissing && !historySyncedCurrentClip) {
                            historySyncedCurrentClip = true;
                            captureSystemClipboardToLocalHistory(false);
                        }
                    }
                }
        );

        populateHistoryItemsList();

        historyParams = new WindowManager.LayoutParams();
        historyParams.type = WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY;
        historyParams.format = PixelFormat.TRANSLUCENT;
        historyParams.gravity = Gravity.TOP | Gravity.RIGHT;
        historyParams.width = dpToPx(380f);
        historyParams.height = WindowManager.LayoutParams.WRAP_CONTENT;
        historyParams.x = dpToPx(18f);
        historyParams.y = dpToPx(46f);
        // Card-only blur inside FrostedGlassCardLayout (never blurs the whole screen!)
        historyParams.flags = WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
                | WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL
                | WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED;
        historyParams.setTitle("BookTools Local Clipboard History");

        historyCard.setPivotX(dpToPx(310f));
        historyCard.setPivotY(dpToPx(20f));
        historyCard.setScaleX(0.78f);
        historyCard.setScaleY(0.78f);
        historyCard.setAlpha(0f);

        try {
            windowManager.addView(historyRoot, historyParams);
            historyShown = true;
            historyDismissing = false;

            historyCard.animate()
                    .scaleX(1.0f)
                    .scaleY(1.0f)
                    .alpha(1.0f)
                    .setDuration(220)
                    .setInterpolator(new OvershootInterpolator(1.08f))
                    .start();

            handler.postDelayed(new Runnable() {
                @Override
                public void run() {
                    if (historyShown && !historyDismissing && !historySyncedCurrentClip) {
                        historySyncedCurrentClip = true;
                        captureSystemClipboardToLocalHistory(false);
                    }
                }
            }, 70L);

            lastActionMessage = "Opened Local Clipboard History (Ctrl+Shift+G)";
            notifyActivityStatus();
        } catch (Exception e) {
            historyShown = false;
            historyDismissing = false;
        }
    }

    private void recycleActiveHistoryThumbBitmaps() {
        for (Bitmap bmp : activeHistoryThumbBitmaps) {
            if (bmp != null && !bmp.isRecycled()) {
                bmp.recycle();
            }
        }
        activeHistoryThumbBitmaps.clear();
    }

    private void populateHistoryItemsList() {
        if (historyItemsContainer == null) return;
        historyItemsContainer.removeAllViews();
        recycleActiveHistoryThumbBitmaps();

        boolean dark = isDarkMode();
        int textPrimary = dark ? Color.WHITE : Color.parseColor("#181A1F");
        int textSecondary = dark ? Color.parseColor("#D2D6DC") : Color.parseColor("#4A5058");
        int textMuted = dark ? Color.parseColor("#A8B0BA") : Color.parseColor("#68707A");
        int accentTeal = dark ? Color.parseColor("#2DD4BF") : Color.parseColor("#0D9488");
        int rowBgColor = dark ? Color.parseColor("#6612151C") : Color.parseColor("#88FFFFFF");
        int rowStrokeColor = dark ? Color.parseColor("#442DD4BF") : Color.parseColor("#440D9488");

        List<ClipboardHistoryManager.HistoryItem> items = ClipboardHistoryManager.getItems(this);

        if (historySubtitleView != null) {
            historySubtitleView.setText(items.size() + " / " + ClipboardHistoryManager.MAX_HISTORY_ITEMS
                    + " local items · Click to copy or Drag into any app");
        }

        if (items.isEmpty()) {
            LinearLayout emptyBox = new LinearLayout(this);
            emptyBox.setOrientation(LinearLayout.VERTICAL);
            emptyBox.setGravity(Gravity.CENTER);
            int pad = dpToPx(20f);
            emptyBox.setPadding(pad, pad, pad, pad);

            GradientDrawable emptyBg = new GradientDrawable();
            emptyBg.setCornerRadius(dpToPx(14f));
            emptyBg.setColor(rowBgColor);
            emptyBg.setStroke(dpToPx(1f), rowStrokeColor);
            emptyBox.setBackground(emptyBg);

            TextView emptyTitle = new TextView(this);
            emptyTitle.setText("Your local clipboard history is empty");
            emptyTitle.setTextColor(textPrimary);
            emptyTitle.setTypeface(Typeface.DEFAULT_BOLD);
            emptyTitle.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f);
            emptyTitle.setGravity(Gravity.CENTER);
            emptyBox.addView(emptyTitle);

            TextView emptySub = new TextView(this);
            emptySub.setText("Copy any text (Ctrl+C), pick a color (Ctrl+Shift+C), or convert an image (Ctrl+Shift+D) — up to 30 items are saved 100% locally on your device.");
            emptySub.setTextColor(textSecondary);
            emptySub.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11.5f);
            emptySub.setGravity(Gravity.CENTER);
            emptySub.setPadding(0, dpToPx(6f), 0, 0);
            emptyBox.addView(emptySub);

            historyItemsContainer.addView(emptyBox, new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
            ));
            return;
        }

        for (int i = 0; i < items.size(); i++) {
            final ClipboardHistoryManager.HistoryItem item = items.get(i);
            View rowView = createHistoryRowView(item, i, dark, textPrimary, textSecondary, textMuted, accentTeal, rowBgColor, rowStrokeColor);
            LinearLayout.LayoutParams rowLp = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
            );
            if (i < items.size() - 1) {
                rowLp.bottomMargin = dpToPx(8f);
            }
            historyItemsContainer.addView(rowView, rowLp);
        }
    }

    private View createHistoryRowView(
            final ClipboardHistoryManager.HistoryItem item,
            int index,
            boolean dark,
            int textPrimary,
            int textSecondary,
            int textMuted,
            int accentTeal,
            int rowBgColor,
            int rowStrokeColor
    ) {
        boolean isTopItem = (index == 0);
        final LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        int padH = dpToPx(10f);
        int padV = dpToPx(9f);
        row.setPadding(padH, padV, padH, padV);

        GradientDrawable bg = new GradientDrawable();
        bg.setCornerRadius(dpToPx(14f));
        bg.setColor(rowBgColor);
        bg.setStroke(dpToPx(isTopItem ? 1.5f : 1f), isTopItem ? accentTeal : rowStrokeColor);
        row.setBackground(bg);

        // 1. Left Badge / Thumbnail / Color Swatch
        if (ClipboardHistoryManager.TYPE_IMAGE.equals(item.type)) {
            ImageView thumbView = new ImageView(this);
            thumbView.setScaleType(ImageView.ScaleType.CENTER_CROP);
            GradientDrawable thumbBg = new GradientDrawable();
            thumbBg.setCornerRadius(dpToPx(9f));
            thumbBg.setColor(dark ? Color.parseColor("#2A303C") : Color.parseColor("#E2E8F0"));
            thumbBg.setStroke(dpToPx(1f), accentTeal);
            thumbView.setBackground(thumbBg);

            Bitmap thumbBmp = ClipboardHistoryManager.loadThumbnailBitmap(this, item);
            if (thumbBmp != null) {
                activeHistoryThumbBitmaps.add(thumbBmp);
                thumbView.setImageBitmap(thumbBmp);
            } else {
                thumbView.setImageResource(R.drawable.ic_drop_arrow);
            }

            LinearLayout.LayoutParams thumbLp = new LinearLayout.LayoutParams(dpToPx(44f), dpToPx(44f));
            thumbLp.rightMargin = dpToPx(10f);
            row.addView(thumbView, thumbLp);
        } else if (ClipboardHistoryManager.TYPE_COLOR.equals(item.type)) {
            View colorSwatch = new View(this);
            int parsedColor = accentTeal;
            try {
                parsedColor = Color.parseColor(item.textContent);
            } catch (Exception ignored) {
            }
            GradientDrawable swatchBg = new GradientDrawable();
            swatchBg.setCornerRadius(dpToPx(10f));
            swatchBg.setColor(parsedColor);
            swatchBg.setStroke(dpToPx(1.5f), dark ? Color.WHITE : Color.parseColor("#202124"));
            colorSwatch.setBackground(swatchBg);

            LinearLayout.LayoutParams swatchLp = new LinearLayout.LayoutParams(dpToPx(40f), dpToPx(40f));
            swatchLp.rightMargin = dpToPx(10f);
            row.addView(colorSwatch, swatchLp);
        } else {
            TextView textBadge = new TextView(this);
            boolean isUrl = item.textContent != null
                    && (item.textContent.startsWith("http://") || item.textContent.startsWith("https://"));
            textBadge.setText(isUrl ? "URL" : "Aa");
            textBadge.setTextColor(accentTeal);
            textBadge.setTypeface(Typeface.DEFAULT_BOLD);
            textBadge.setTextSize(TypedValue.COMPLEX_UNIT_SP, isUrl ? 10.5f : 13f);
            textBadge.setGravity(Gravity.CENTER);

            GradientDrawable badgeBg = new GradientDrawable();
            badgeBg.setCornerRadius(dpToPx(10f));
            badgeBg.setColor(dark ? Color.parseColor("#282DD4BF") : Color.parseColor("#1A0D9488"));
            badgeBg.setStroke(dpToPx(1f), rowStrokeColor);
            textBadge.setBackground(badgeBg);

            LinearLayout.LayoutParams badgeLp = new LinearLayout.LayoutParams(dpToPx(40f), dpToPx(40f));
            badgeLp.rightMargin = dpToPx(10f);
            row.addView(textBadge, badgeLp);
        }

        // 2. Center Title & Metadata Column
        LinearLayout infoCol = new LinearLayout(this);
        infoCol.setOrientation(LinearLayout.VERTICAL);

        TextView titleTv = new TextView(this);
        titleTv.setText(item.title != null ? item.title : "");
        titleTv.setTextColor(textPrimary);
        titleTv.setTypeface(ClipboardHistoryManager.TYPE_COLOR.equals(item.type)
                ? Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
                : Typeface.DEFAULT_BOLD);
        titleTv.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12.5f);
        titleTv.setMaxLines(2);
        titleTv.setEllipsize(TextUtils.TruncateAt.END);
        infoCol.addView(titleTv);

        TextView subTv = new TextView(this);
        String timeStr = item.getFormattedTime();
        String subText = (item.subtitle != null ? item.subtitle : "") + " · " + timeStr;
        subTv.setText(subText);
        subTv.setTextColor(textSecondary);
        subTv.setTextSize(TypedValue.COMPLEX_UNIT_SP, 10.5f);
        subTv.setSingleLine(true);
        subTv.setEllipsize(TextUtils.TruncateAt.END);
        subTv.setPadding(0, dpToPx(2f), 0, 0);
        infoCol.addView(subTv);

        row.addView(infoCol, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));

        // 3. Right Drag/Copy Chip (+ [1]-[9] quick-key hint) + Individual Delete Button
        TextView actionChip = new TextView(this);
        String keyPrefix = (index >= 0 && index < 9) ? "[" + (index + 1) + "] " : "";
        actionChip.setText(keyPrefix + (ClipboardHistoryManager.TYPE_IMAGE.equals(item.type) ? "⤓ Drag/Copy" : "Copy"));
        actionChip.setTextColor(accentTeal);
        actionChip.setTypeface(Typeface.DEFAULT_BOLD);
        actionChip.setTextSize(TypedValue.COMPLEX_UNIT_SP, 10.5f);
        actionChip.setPadding(dpToPx(7f), dpToPx(3f), dpToPx(7f), dpToPx(3f));
        GradientDrawable chipBg = new GradientDrawable();
        chipBg.setCornerRadius(dpToPx(8f));
        chipBg.setColor(dark ? Color.parseColor("#242DD4BF") : Color.parseColor("#180D9488"));
        actionChip.setBackground(chipBg);
        LinearLayout.LayoutParams chipLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
        );
        chipLp.leftMargin = dpToPx(6f);
        row.addView(actionChip, chipLp);

        TextView deleteItemBtn = new TextView(this);
        deleteItemBtn.setText("✕");
        deleteItemBtn.setTextColor(textMuted);
        deleteItemBtn.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f);
        deleteItemBtn.setPadding(dpToPx(8f), dpToPx(4f), dpToPx(4f), dpToPx(4f));
        deleteItemBtn.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                ClipboardHistoryManager.removeItem(TopBarDropService.this, item.id);
                populateHistoryItemsList();
                notifyActivityStatus();
            }
        });
        row.addView(deleteItemBtn);

        // Click to copy OR Click-and-Drag to drop directly into any app
        row.setOnTouchListener(new View.OnTouchListener() {
            private float downX;
            private float downY;
            private boolean dragStarted;
            private final int slop = dpToPx(6f);

            @Override
            public boolean onTouch(View v, MotionEvent event) {
                if ((event.getButtonState() & MotionEvent.BUTTON_SECONDARY) != 0) {
                    dismissHistoryPopup(true);
                    return true;
                }
                boolean isMouse = (event.getSource() & InputDevice.SOURCE_MOUSE) == InputDevice.SOURCE_MOUSE;

                switch (event.getActionMasked()) {
                    case MotionEvent.ACTION_DOWN:
                        downX = event.getX();
                        downY = event.getY();
                        dragStarted = false;
                        v.setAlpha(0.85f);
                        return true;

                    case MotionEvent.ACTION_MOVE:
                        if (!dragStarted) {
                            float dx = event.getX() - downX;
                            float dy = event.getY() - downY;
                            double dist = Math.hypot(dx, dy);
                            // With a mouse, moving > slop in any direction starts drag-and-drop;
                            // with touch, horizontal movement starts drag so vertical swipe can scroll.
                            if (dist > slop && (isMouse || Math.abs(dx) > Math.abs(dy) * 1.15f)) {
                                dragStarted = true;
                                v.setAlpha(1.0f);
                                if (v.getParent() != null) {
                                    v.getParent().requestDisallowInterceptTouchEvent(true);
                                }
                                startHistoryItemDragOut(v, item);
                            }
                        }
                        return true;

                    case MotionEvent.ACTION_UP:
                        v.setAlpha(1.0f);
                        if (!dragStarted) {
                            copyHistoryItemAndDismiss(item);
                        }
                        return true;

                    case MotionEvent.ACTION_CANCEL:
                        v.setAlpha(1.0f);
                        dragStarted = false;
                        return true;
                }
                return false;
            }
        });

        return row;
    }

    private void copyHistoryItemAndDismiss(ClipboardHistoryManager.HistoryItem item) {
        boolean ok = ClipboardHistoryManager.copyItemToSystemClipboard(this, item);
        if (ok) {
            if (ClipboardHistoryManager.TYPE_COLOR.equals(item.type)) {
                int c = Color.parseColor("#2DD4BF");
                try {
                    c = Color.parseColor(item.textContent);
                } catch (Exception ignored) {
                }
                showFloatingStatusPill(c, true, "Copied " + item.title + " to clipboard");
            } else if (ClipboardHistoryManager.TYPE_IMAGE.equals(item.type)) {
                showFloatingStatusPill(0, false, "Copied PNG (" + item.title + ") to clipboard");
            } else {
                showFloatingStatusPill(0, false, "Copied text from local history");
            }
            lastActionMessage = "Copied from History: " + item.title;
            notifyActivityStatus();
        }
        dismissHistoryPopup(true);
    }

    private void startHistoryItemDragOut(View sourceView, ClipboardHistoryManager.HistoryItem item) {
        if (item == null) return;
        try {
            ClipData clipData;
            if (ClipboardHistoryManager.TYPE_IMAGE.equals(item.type) && item.imageUriString != null) {
                Uri uri = Uri.parse(item.imageUriString);
                ClipDescription desc = new ClipDescription(
                        item.title != null ? item.title : "image.png",
                        new String[]{"image/png", "application/octet-stream"}
                );
                clipData = new ClipData(desc, new ClipData.Item(uri));
            } else if (item.textContent != null) {
                clipData = ClipData.newPlainText(
                        item.title != null ? item.title : "BookTools Snippet",
                        item.textContent
                );
            } else {
                return;
            }

            View.DragShadowBuilder shadow = new View.DragShadowBuilder(sourceView);
            int flags = View.DRAG_FLAG_GLOBAL | View.DRAG_FLAG_GLOBAL_URI_READ;
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                sourceView.startDragAndDrop(clipData, shadow, null, flags);
            } else {
                sourceView.startDrag(clipData, shadow, null, flags);
            }

            if (historyRoot != null) {
                historyRoot.animate().alpha(0.30f).setDuration(120).start();
            }
        } catch (Exception e) {
            Toast.makeText(this, "Drag failed: " + e.getMessage(), Toast.LENGTH_SHORT).show();
        }
    }

    public void dismissHistoryPopup(boolean animate) {
        if (!historyShown || historyRoot == null) {
            return;
        }
        if (!animate || historyCard == null) {
            removeHistoryPopupImmediate();
            return;
        }
        if (historyDismissing) {
            return;
        }
        historyDismissing = true;
        final View rootToRemove = historyRoot;
        historyCard.animate()
                .scaleX(0.80f)
                .scaleY(0.80f)
                .alpha(0f)
                .setDuration(165)
                .setInterpolator(new AccelerateInterpolator(1.2f))
                .withEndAction(new Runnable() {
                    @Override
                    public void run() {
                        if (historyRoot == rootToRemove) {
                            removeHistoryPopupImmediate();
                        }
                    }
                })
                .start();
    }

    private void removeHistoryPopupImmediate() {
        recycleActiveHistoryThumbBitmaps();
        if (historyRoot != null && windowManager != null) {
            try {
                windowManager.removeViewImmediate(historyRoot);
            } catch (Exception ignored) {
                try {
                    windowManager.removeView(historyRoot);
                } catch (Exception ignored2) {
                }
            }
        }
        historyRoot = null;
        historyCard = null;
        historyItemsContainer = null;
        historySubtitleView = null;
        historyShown = false;
        historyDismissing = false;
        releaseScreenBitmapsIfIdle();
    }

    private class HistorySwipeDismissListener implements View.OnTouchListener {
        private float downRawX;
        private boolean swiping;
        private final int touchSlop = ViewConfiguration.get(TopBarDropService.this).getScaledTouchSlop();

        @Override
        public boolean onTouch(View v, MotionEvent event) {
            if ((event.getButtonState() & MotionEvent.BUTTON_SECONDARY) != 0) {
                dismissHistoryPopup(true);
                return true;
            }
            switch (event.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    downRawX = event.getRawX();
                    swiping = false;
                    return true;

                case MotionEvent.ACTION_MOVE:
                    float dx = event.getRawX() - downRawX;
                    if (Math.abs(dx) > touchSlop) {
                        swiping = true;
                    }
                    if (swiping && historyCard != null) {
                        historyCard.setTranslationX(dx);
                        historyCard.invalidate();
                        float progress = Math.min(1f, Math.abs(dx) / (float) dpToPx(180f));
                        historyCard.setAlpha(Math.max(0.15f, 1f - progress * 0.85f));
                        float scale = 1f - progress * 0.12f;
                        historyCard.setScaleX(scale);
                        historyCard.setScaleY(scale);
                    }
                    return true;

                case MotionEvent.ACTION_UP:
                case MotionEvent.ACTION_CANCEL:
                    if (swiping && historyCard != null) {
                        float totalDx = event.getRawX() - downRawX;
                        if (Math.abs(totalDx) > dpToPx(65f)) {
                            historyDismissing = true;
                            final View rootToRemove = historyRoot;
                            float targetX = totalDx > 0 ? dpToPx(260f) : -dpToPx(260f);
                            historyCard.animate()
                                    .translationX(targetX)
                                    .scaleX(0.82f)
                                    .scaleY(0.82f)
                                    .alpha(0f)
                                    .setDuration(150)
                                    .withEndAction(new Runnable() {
                                        @Override
                                        public void run() {
                                            if (historyRoot == rootToRemove) {
                                                removeHistoryPopupImmediate();
                                            }
                                        }
                                    })
                                    .start();
                        } else {
                            historyCard.animate()
                                    .translationX(0f)
                                    .scaleX(1f)
                                    .scaleY(1f)
                                    .alpha(1f)
                                    .setDuration(150)
                                    .setInterpolator(new OvershootInterpolator(1.1f))
                                    .start();
                        }
                    }
                    swiping = false;
                    return true;
            }
            return false;
        }
    }

    private class HistoryRootView extends FrameLayout {
        public HistoryRootView(android.content.Context context) {
            super(context);
            setFocusable(true);
            setFocusableInTouchMode(true);
        }

        @Override
        public boolean dispatchGenericMotionEvent(MotionEvent event) {
            if (event != null && (event.getButtonState() & MotionEvent.BUTTON_SECONDARY) != 0) {
                dismissHistoryPopup(true);
                return true;
            }
            return super.dispatchGenericMotionEvent(event);
        }

        @Override
        public boolean dispatchTouchEvent(MotionEvent event) {
            if (event != null && (event.getButtonState() & MotionEvent.BUTTON_SECONDARY) != 0) {
                dismissHistoryPopup(true);
                return true;
            }
            return super.dispatchTouchEvent(event);
        }

        @Override
        public boolean dispatchKeyEvent(KeyEvent event) {
            if (event != null && event.getAction() == KeyEvent.ACTION_DOWN) {
                int code = event.getKeyCode();
                if (code == KeyEvent.KEYCODE_ESCAPE) {
                    dismissHistoryPopup(true);
                    return true;
                }
                int quickIndex = -1;
                if (code >= KeyEvent.KEYCODE_1 && code <= KeyEvent.KEYCODE_9) {
                    quickIndex = code - KeyEvent.KEYCODE_1;
                } else if (code >= KeyEvent.KEYCODE_NUMPAD_1 && code <= KeyEvent.KEYCODE_NUMPAD_9) {
                    quickIndex = code - KeyEvent.KEYCODE_NUMPAD_1;
                }
                if (quickIndex >= 0) {
                    List<ClipboardHistoryManager.HistoryItem> items =
                            ClipboardHistoryManager.getItems(TopBarDropService.this);
                    if (quickIndex < items.size()) {
                        copyHistoryItemAndDismiss(items.get(quickIndex));
                        return true;
                    }
                }
            }
            return super.dispatchKeyEvent(event);
        }
    }

    // =========================================================================
    // 4. SHIFT + DRAG / RIGHT-CLICK / CTRL+SHIFT+Z: POWERTOYS FANCYZONES SNAPPER
    // =========================================================================

    private long shiftPressTimeMs = 0L;
    private boolean otherKeyPressedDuringShift = false;
    private boolean windowMovedWhileZonesShown = false;
    private final Rect dragStartWindowRect = new Rect();

    private final Runnable shiftShowZonesRunnable = new Runnable() {
        @Override
        public void run() {
            if (shiftKeyHeld && !otherKeyPressedDuringShift && !snapZonesShown) {
                showSnapZonesOverlay(false);
            }
        }
    };

    private final Runnable makeZonesTouchableRunnable = new Runnable() {
        @Override
        public void run() {
            if (snapZonesShown && !snapZonesDismissing && !snapZonesInteractive) {
                setSnapZonesTouchable(true);
            }
        }
    };

    private final Runnable shiftDragPollRunnable = new Runnable() {
        @Override
        public void run() {
            if (!shiftKeyHeld && !snapZonesShown) {
                return;
            }
            inspectWindowsForDrag(true);
            if (shiftKeyHeld || snapZonesShown) {
                handler.postDelayed(this, 20L);
            }
        }
    };

    public boolean hasWindowAndGestureCapabilities() {
        try {
            AccessibilityServiceInfo info = getServiceInfo();
            if (info == null) return false;
            int caps = info.getCapabilities();
            boolean canWin = (caps & AccessibilityServiceInfo.CAPABILITY_CAN_RETRIEVE_WINDOW_CONTENT) != 0;
            boolean canGest = (caps & AccessibilityServiceInfo.CAPABILITY_CAN_PERFORM_GESTURES) != 0;
            return canWin && canGest;
        } catch (Exception e) {
            return false;
        }
    }

    private void ensureWindowRetrievalFlagSet() {
        try {
            AccessibilityServiceInfo info = getServiceInfo();
            if (info != null) {
                int desiredFlags = AccessibilityServiceInfo.FLAG_REQUEST_FILTER_KEY_EVENTS
                        | AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS;
                if ((info.flags & desiredFlags) != desiredFlags) {
                    info.flags |= desiredFlags;
                    info.eventTypes |= AccessibilityEvent.TYPE_WINDOWS_CHANGED
                            | AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED;
                    setServiceInfo(info);
                }
            }
        } catch (Exception ignored) {
        }
    }

    private void onShiftPressedForSnapZones() {
        if (colorPickerShown || historyShown || popupShown) {
            return;
        }
        ensureWindowRetrievalFlagSet();
        shiftPressTimeMs = System.currentTimeMillis();
        otherKeyPressedDuringShift = false;

        // Capture the active window's current bounds right as Shift is pressed
        Rect rect = new Rect();
        CharSequence[] titleOut = new CharSequence[1];
        int winId = findTargetApplicationWindow(-1, rect, titleOut);
        if (winId != -1 && !rect.isEmpty()) {
            draggedWindowId = winId;
            draggedWindowRect.set(rect);
            dragStartWindowRect.set(rect);
            trackedWindowId = winId;
            trackedWindowRect.set(rect);
            draggedWindowTitle = titleOut[0] != null ? titleOut[0] : "Window";
        }

        long now = System.currentTimeMillis();
        // Show immediately if a window was just moving, or after 110ms of holding Shift
        if (!snapZonesShown && (now - lastWindowDragTimeMs <= 1200L)) {
            showSnapZonesOverlay(false);
        } else {
            handler.removeCallbacks(shiftShowZonesRunnable);
            handler.postDelayed(shiftShowZonesRunnable, 110L);
        }
        handler.removeCallbacks(shiftDragPollRunnable);
        handler.postDelayed(shiftDragPollRunnable, 20L);
    }

    private void onShiftReleasedForSnapZones() {
        handler.removeCallbacks(shiftShowZonesRunnable);
        if (!snapZonesShown || snapZonesDismissing) {
            return;
        }
        long heldDuration = System.currentTimeMillis() - shiftPressTimeMs;
        // Re-check window bounds one more time in case Left-Click was just released right before Shift
        checkIfWindowMovedFromStart();
        if ((windowMovedWhileZonesShown || heldDuration >= 240L)
                && hoveredSnapZoneIndex >= 0
                && hoveredSnapZoneIndex < currentSnapZones.size()) {
            commitHoveredSnapZoneAndDismiss();
        } else {
            setSnapZonesTouchable(true);
            if (snapZonesCanvas != null) {
                snapZonesCanvas.invalidate();
            }
        }
    }

    public void triggerSnapZonesInteractive() {
        dismissPopup(false);
        dismissColorPicker(false);
        dismissHistoryPopup(false);
        ensureWindowRetrievalFlagSet();

        Rect rect = new Rect();
        CharSequence[] titleOut = new CharSequence[1];
        int winId = findTargetApplicationWindow(-1, rect, titleOut);
        if (winId != -1) {
            draggedWindowId = winId;
            draggedWindowRect.set(rect);
            dragStartWindowRect.set(rect);
            draggedWindowTitle = titleOut[0] != null ? titleOut[0] : "Active Window";
        }
        showSnapZonesOverlay(true);
    }

    private boolean checkIfWindowMovedFromStart() {
        Rect rect = new Rect();
        int preferredId = (draggedWindowId != -1) ? draggedWindowId : -1;
        int winId = findTargetApplicationWindow(preferredId, rect, null);
        if (winId != -1 && !rect.isEmpty() && !dragStartWindowRect.isEmpty()) {
            int dx = Math.abs(rect.left - dragStartWindowRect.left);
            int dy = Math.abs(rect.top - dragStartWindowRect.top);
            if (dx >= 4 || dy >= 4) {
                draggedWindowId = winId;
                draggedWindowRect.set(rect);
                windowMovedWhileZonesShown = true;
                return true;
            }
        }
        return false;
    }

    private void inspectWindowsForDrag(boolean fromPoll) {
        if (colorPickerShown || historyShown || popupShown) {
            return;
        }
        Rect rect = new Rect();
        CharSequence[] titleOut = new CharSequence[1];
        int preferredId = (snapZonesShown && draggedWindowId != -1) ? draggedWindowId : -1;
        int winId = findTargetApplicationWindow(preferredId, rect, titleOut);
        if (winId == -1 || rect.isEmpty()) {
            return;
        }

        long now = System.currentTimeMillis();
        if (winId == trackedWindowId) {
            int dx = Math.abs(rect.left - trackedWindowRect.left);
            int dy = Math.abs(rect.top - trackedWindowRect.top);
            int dw = Math.abs(rect.width() - trackedWindowRect.width());
            int dh = Math.abs(rect.height() - trackedWindowRect.height());

            if ((dx >= 2 || dy >= 2) && dw <= dpToPx(36f) && dh <= dpToPx(36f)) {
                trackedWindowRect.set(rect);
                draggedWindowId = winId;
                draggedWindowRect.set(rect);
                draggedWindowTitle = titleOut[0] != null ? titleOut[0] : "Window";
                lastWindowDragTimeMs = now;
                lastSnapZonesMoveMs = now;

                if (shiftKeyHeld && !snapZonesShown) {
                    showSnapZonesOverlay(false);
                }
                if (snapZonesShown && !snapZonesDismissing) {
                    windowMovedWhileZonesShown = true;
                    updateHoveredZoneFromWindowRect(rect);
                }
            } else if (snapZonesShown && !snapZonesDismissing) {
                // If the window was dragged while zones were shown and has now settled stationary
                // for >= 210ms (meaning the user released Left-Click!), automatically snap & resize!
                if (windowMovedWhileZonesShown && (now - lastSnapZonesMoveMs >= 210L)) {
                    commitHoveredSnapZoneAndDismiss();
                    return;
                }
                if (!snapZonesInteractive && (now - lastSnapZonesMoveMs >= 150L)) {
                    setSnapZonesTouchable(true);
                }
            }
        } else {
            trackedWindowId = winId;
            trackedWindowRect.set(rect);
            if (draggedWindowId == -1) {
                draggedWindowId = winId;
                draggedWindowRect.set(rect);
                if (dragStartWindowRect.isEmpty()) {
                    dragStartWindowRect.set(rect);
                }
                draggedWindowTitle = titleOut[0] != null ? titleOut[0] : "Window";
            }
        }
    }

    private void updateHoveredZoneFromWindowRect(Rect rect) {
        if (currentSnapZones == null || currentSnapZones.isEmpty()) return;
        Rect workArea = getDesktopWorkArea();

        int dragDx = !dragStartWindowRect.isEmpty() ? (rect.left - dragStartWindowRect.left) : 0;
        int dragDy = !dragStartWindowRect.isEmpty() ? (rect.top - dragStartWindowRect.top) : 0;

        float probeX = rect.exactCenterX() + dragDx * 0.45f;
        if (rect.left <= workArea.left + dpToPx(120f)) {
            probeX = workArea.left + dpToPx(40f);
        } else if (rect.right >= workArea.right - dpToPx(120f)) {
            probeX = workArea.right - dpToPx(40f);
        }
        probeX = clamp(Math.round(probeX), workArea.left + 8, workArea.right - 8);

        float probeY = rect.top + dpToPx(32f) + Math.max(0, dragDy * 0.65f);
        if (rect.top + dpToPx(48f) >= workArea.centerY()
                || (dragDy > dpToPx(55f) && rect.exactCenterY() + dragDy * 0.5f >= workArea.centerY())
                || (rect.bottom >= workArea.bottom - dpToPx(48f) && dragDy > dpToPx(24f))) {
            probeY = Math.max(probeY, workArea.centerY() + dpToPx(60f));
        } else if (rect.top <= workArea.top + dpToPx(90f) || dragDy < -dpToPx(35f)) {
            probeY = Math.min(probeY, workArea.centerY() - dpToPx(60f));
        }
        probeY = clamp(Math.round(probeY), workArea.top + 8, workArea.bottom - 8);

        int newIdx = SnapZonesManager.findZoneIndexAt(currentSnapZones, probeX, probeY);
        if (newIdx != hoveredSnapZoneIndex) {
            hoveredSnapZoneIndex = newIdx;
            if (snapZonesCanvas != null) {
                snapZonesCanvas.invalidate();
            }
        }
    }

    private Rect getDesktopWorkArea() {
        DisplayMetrics dm = getResources().getDisplayMetrics();
        int screenW = dm.widthPixels;
        int screenH = dm.heightPixels;
        Rect work = new Rect(0, dpToPx(28f), screenW, screenH - dpToPx(52f));
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && windowManager != null) {
                WindowMetrics wm = windowManager.getCurrentWindowMetrics();
                Rect b = wm.getBounds();
                Insets insets = wm.getWindowInsets().getInsetsIgnoringVisibility(
                        WindowInsets.Type.systemBars() | WindowInsets.Type.displayCutout()
                );
                work.set(
                        b.left + insets.left,
                        b.top + insets.top,
                        b.right - insets.right,
                        b.bottom - insets.bottom
                );
            }
        } catch (Exception ignored) {
        }
        return work;
    }

    private void rebuildCurrentSnapZones() {
        int mode = SnapZonesManager.getLayoutMode(this);
        Rect workArea = getDesktopWorkArea();
        currentSnapZones = SnapZonesManager.buildZones(mode, workArea, dpToPx(10f));
        if (hoveredSnapZoneIndex >= currentSnapZones.size()) {
            hoveredSnapZoneIndex = currentSnapZones.isEmpty() ? -1 : 0;
        }
    }

    private void cycleSnapZonesLayoutOnTheFly() {
        int mode = SnapZonesManager.getLayoutMode(this);
        int nextMode = (mode + 1) % 3;
        SnapZonesManager.setLayoutMode(this, nextMode);
        rebuildCurrentSnapZones();
        if (!draggedWindowRect.isEmpty()) {
            updateHoveredZoneFromWindowRect(draggedWindowRect);
        }
        if (snapZonesCanvas != null) {
            snapZonesCanvas.invalidate();
        }
        notifyActivityStatus();
    }

    private void showSnapZonesOverlay(boolean startInteractive) {
        if (snapZonesShown && snapZonesRoot != null) {
            return;
        }
        removeSnapZonesImmediate();
        rebuildCurrentSnapZones();

        windowMovedWhileZonesShown = false;
        Rect cur = new Rect();
        CharSequence[] titleOut = new CharSequence[1];
        int winId = findTargetApplicationWindow(draggedWindowId, cur, titleOut);
        if (winId != -1 && !cur.isEmpty()) {
            draggedWindowId = winId;
            draggedWindowRect.set(cur);
            if (dragStartWindowRect.isEmpty()) {
                dragStartWindowRect.set(cur);
            }
            if (titleOut[0] != null) {
                draggedWindowTitle = titleOut[0];
            }
            updateHoveredZoneFromWindowRect(cur);
        } else if (!currentSnapZones.isEmpty() && hoveredSnapZoneIndex < 0) {
            hoveredSnapZoneIndex = 0;
        }

        snapZonesInteractive = startInteractive;
        lastSnapZonesMoveMs = System.currentTimeMillis();

        snapZonesRoot = new SnapZonesRootView(this);
        snapZonesCanvas = new SnapZonesCanvasView(this);
        snapZonesRoot.addView(snapZonesCanvas, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
        ));

        int flags = WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
                | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS
                | WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL;
        if (!startInteractive) {
            flags |= WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                    | WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE;
        }

        snapZonesParams = new WindowManager.LayoutParams(
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                flags,
                PixelFormat.TRANSLUCENT
        );
        snapZonesParams.gravity = Gravity.TOP | Gravity.START;

        try {
            windowManager.addView(snapZonesRoot, snapZonesParams);
            snapZonesShown = true;
            snapZonesDismissing = false;
            snapZonesRoot.setAlpha(0f);
            snapZonesRoot.animate().alpha(1f).setDuration(120L).start();

            if (startInteractive) {
                snapZonesRoot.requestFocus();
            } else {
                // Enable hover/drop detection 150ms after appearing (while active pointer drag stays with SystemUI)
                handler.removeCallbacks(makeZonesTouchableRunnable);
                handler.postDelayed(makeZonesTouchableRunnable, 150L);
            }

            handler.removeCallbacks(shiftDragPollRunnable);
            handler.postDelayed(shiftDragPollRunnable, 20L);
        } catch (Exception e) {
            snapZonesShown = false;
            snapZonesRoot = null;
            snapZonesCanvas = null;
        }
    }

    private void setSnapZonesTouchable(boolean touchable) {
        if (!snapZonesShown || snapZonesRoot == null || snapZonesParams == null) {
            return;
        }
        if (snapZonesInteractive == touchable) {
            return;
        }
        snapZonesInteractive = touchable;
        try {
            if (touchable) {
                snapZonesParams.flags &= ~WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE;
                snapZonesParams.flags &= ~WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE;
            } else {
                snapZonesParams.flags |= WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
                        | WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE;
            }
            windowManager.updateViewLayout(snapZonesRoot, snapZonesParams);
            if (touchable) {
                snapZonesRoot.requestFocus();
            }
        } catch (Exception ignored) {
        }
    }

    private void dismissSnapZonesOverlay(boolean animate) {
        if (!snapZonesShown || snapZonesRoot == null) {
            return;
        }
        if (!animate) {
            removeSnapZonesImmediate();
            return;
        }
        if (snapZonesDismissing) {
            return;
        }
        snapZonesDismissing = true;
        final View rootToRemove = snapZonesRoot;
        snapZonesRoot.animate()
                .alpha(0f)
                .setDuration(110L)
                .withEndAction(new Runnable() {
                    @Override
                    public void run() {
                        if (snapZonesRoot == rootToRemove) {
                            removeSnapZonesImmediate();
                        }
                    }
                })
                .start();
    }

    private void removeSnapZonesImmediate() {
        handler.removeCallbacks(shiftDragPollRunnable);
        handler.removeCallbacks(shiftShowZonesRunnable);
        handler.removeCallbacks(makeZonesTouchableRunnable);
        if (snapZonesRoot != null) {
            try {
                windowManager.removeViewImmediate(snapZonesRoot);
            } catch (Exception ignored) {
            }
            snapZonesRoot = null;
        }
        snapZonesCanvas = null;
        snapZonesParams = null;
        snapZonesShown = false;
        snapZonesDismissing = false;
        snapZonesInteractive = false;
        windowMovedWhileZonesShown = false;
        dragStartWindowRect.setEmpty();
    }

    private void commitHoveredSnapZoneAndDismiss() {
        if (hoveredSnapZoneIndex < 0 || hoveredSnapZoneIndex >= currentSnapZones.size()) {
            dismissSnapZonesOverlay(true);
            return;
        }
        final SnapZonesManager.SnapZone chosenZone = currentSnapZones.get(hoveredSnapZoneIndex);
        final int targetWinId = draggedWindowId;
        removeSnapZonesImmediate();

        lastActionMessage = "Snapping window to " + chosenZone.title + "...";
        notifyActivityStatus();

        // Wait 95ms for overlay removal and mouse button release before dispatching window snap gestures
        handler.postDelayed(new Runnable() {
            @Override
            public void run() {
                snapWindowToZone(targetWinId, chosenZone);
            }
        }, 95L);
    }

    private int findTargetApplicationWindow(int preferredWindowId, Rect outBounds, CharSequence[] outTitle) {
        try {
            List<AccessibilityWindowInfo> windows = getWindows();
            if (windows == null || windows.isEmpty()) {
                return -1;
            }
            DisplayMetrics dm = getResources().getDisplayMetrics();
            int screenW = dm.widthPixels;
            int screenH = dm.heightPixels;
            Rect tmp = new Rect();

            AccessibilityWindowInfo chosen = null;
            if (preferredWindowId != -1) {
                for (AccessibilityWindowInfo w : windows) {
                    if (w != null && w.getId() == preferredWindowId && w.getType() == AccessibilityWindowInfo.TYPE_APPLICATION) {
                        chosen = w;
                        break;
                    }
                }
            }
            if (chosen == null) {
                // Prefer focused/active freeform application window
                for (AccessibilityWindowInfo w : windows) {
                    if (w != null && w.getType() == AccessibilityWindowInfo.TYPE_APPLICATION && (w.isFocused() || w.isActive())) {
                        w.getBoundsInScreen(tmp);
                        if (!tmp.isEmpty()) {
                            chosen = w;
                            break;
                        }
                    }
                }
            }
            if (chosen == null) {
                // Next prefer any non-fullscreen (freeform) application window
                for (AccessibilityWindowInfo w : windows) {
                    if (w != null && w.getType() == AccessibilityWindowInfo.TYPE_APPLICATION) {
                        w.getBoundsInScreen(tmp);
                        if (!tmp.isEmpty() && (tmp.width() < screenW - dpToPx(8f) || tmp.height() < screenH - dpToPx(8f))) {
                            chosen = w;
                            break;
                        }
                    }
                }
            }
            if (chosen == null) {
                for (AccessibilityWindowInfo w : windows) {
                    if (w != null && w.getType() == AccessibilityWindowInfo.TYPE_APPLICATION) {
                        chosen = w;
                        break;
                    }
                }
            }
            if (chosen != null) {
                chosen.getBoundsInScreen(outBounds);
                if (outTitle != null && outTitle.length > 0) {
                    outTitle[0] = chosen.getTitle();
                }
                return chosen.getId();
            }
        } catch (Exception ignored) {
        }
        return -1;
    }

    private boolean tryAccessibilityMoveWindow(int windowId, int targetLeft, int targetTop) {
        try {
            List<AccessibilityWindowInfo> windows = getWindows();
            if (windows == null) return false;
            for (AccessibilityWindowInfo w : windows) {
                if (w != null && w.getId() == windowId) {
                    AccessibilityNodeInfo root = w.getRoot();
                    if (root != null) {
                        Bundle args = new Bundle();
                        args.putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_MOVE_WINDOW_X, targetLeft);
                        args.putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_MOVE_WINDOW_Y, targetTop);
                        boolean ok = root.performAction(
                                AccessibilityNodeInfo.AccessibilityAction.ACTION_MOVE_WINDOW.getId(),
                                args
                        );
                        root.recycle();
                        return ok;
                    }
                }
            }
        } catch (Exception ignored) {
        }
        return false;
    }

    /**
     * Moves and resizes the target freeform application window to fit chosenZone.bounds
     * using AccessibilityAction.ACTION_MOVE_WINDOW and self-verifying multi-stage gesture strokes
     * on the window's title bar and freeform resize corners (outer + inner fallback).
     */
    private void snapWindowToZone(final int preferredWinId, final SnapZonesManager.SnapZone zone) {
        if (!hasWindowAndGestureCapabilities()) {
            String msg = "Toggle 'BookTools Shortcuts' OFF & ON once in Accessibility Settings to grant Window Snap permission!";
            lastActionMessage = msg;
            notifyActivityStatus();
            Toast.makeText(this, msg, Toast.LENGTH_LONG).show();
            return;
        }

        final Rect target = new Rect(zone.bounds);
        final Rect curRect = new Rect();
        final int winId = findTargetApplicationWindow(preferredWinId, curRect, null);
        if (winId == -1 || curRect.isEmpty()) {
            Toast.makeText(this, "No active freeform window found — click a window first", Toast.LENGTH_SHORT).show();
            return;
        }

        DisplayMetrics dm = getResources().getDisplayMetrics();
        final int screenW = dm.widthPixels;
        final int screenH = dm.heightPixels;

        if (target.left + curRect.width() <= screenW - dpToPx(6f)
                && target.top + curRect.height() <= screenH - dpToPx(6f)) {
            tryAccessibilityMoveWindow(winId, target.left, target.top);
        }

        handler.postDelayed(new Runnable() {
            @Override
            public void run() {
                runSnapStage1MoveOrPreShrink(winId, target, screenW, screenH, zone.title);
            }
        }, 55L);
    }

    private void runSnapStage1MoveOrPreShrink(
            final int winId,
            final Rect target,
            final int screenW,
            final int screenH,
            final String zoneTitle
    ) {
        Rect cur = new Rect();
        if (findTargetApplicationWindow(winId, cur, null) == -1 || cur.isEmpty()) {
            return;
        }

        // If the window is currently wider/taller than target such that moving it to target.left/top
        // would push its bottom-right corner off-screen, shrink its bottom-right corner first!
        if (target.left + cur.width() > screenW - dpToPx(8f)
                || target.top + cur.height() > screenH - dpToPx(8f)) {
            int startX = clamp(cur.right + dpToPx(1f), 4, screenW - 3);
            int startY = clamp(cur.bottom + dpToPx(1f), 4, screenH - 3);
            int endX = clamp(cur.left + target.width(), cur.left + dpToPx(220f), screenW - 3);
            int endY = clamp(cur.top + target.height(), cur.top + dpToPx(180f), screenH - 3);

            dispatchHoldDragHoldGesture(startX, startY, endX, endY, 190L, new Runnable() {
                @Override
                public void run() {
                    runSnapStage2MoveToTargetTopLeft(winId, target, screenW, screenH, zoneTitle);
                }
            });
        } else {
            runSnapStage2MoveToTargetTopLeft(winId, target, screenW, screenH, zoneTitle);
        }
    }

    private void runSnapStage2MoveToTargetTopLeft(
            final int winId,
            final Rect target,
            final int screenW,
            final int screenH,
            final String zoneTitle
    ) {
        Rect cur = new Rect();
        if (findTargetApplicationWindow(winId, cur, null) == -1 || cur.isEmpty()) {
            return;
        }

        int dx = target.left - cur.left;
        int dy = target.top - cur.top;
        if (Math.abs(dx) <= dpToPx(8f) && Math.abs(dy) <= dpToPx(8f)) {
            runSnapStage3CornerTopLeftVerify(winId, target, screenW, screenH, zoneTitle);
            return;
        }

        // Grab the window's title bar at 40% width (avoiding left app icon menu and right window buttons)
        int grabOffsetX = clamp((int) (cur.width() * 0.40f), dpToPx(95f), dpToPx(240f));
        int grabOffsetY = dpToPx(18f);
        int startX = clamp(cur.left + grabOffsetX, 8, screenW - 8);
        int startY = clamp(cur.top + grabOffsetY, 8, screenH - 8);
        int endX = clamp(startX + dx, 8, screenW - 8);
        int endY = clamp(startY + dy, 8, screenH - 8);

        dispatchHoldDragHoldGesture(startX, startY, endX, endY, 200L, new Runnable() {
            @Override
            public void run() {
                runSnapStage3CornerTopLeftVerify(winId, target, screenW, screenH, zoneTitle);
            }
        });
    }

    private void runSnapStage3CornerTopLeftVerify(
            final int winId,
            final Rect target,
            final int screenW,
            final int screenH,
            final String zoneTitle
    ) {
        Rect cur = new Rect();
        if (findTargetApplicationWindow(winId, cur, null) == -1 || cur.isEmpty()) {
            return;
        }

        // If title-bar drag didn't align top-left, adjust via Top-Left resize corner
        if (Math.abs(cur.left - target.left) > dpToPx(16f) || Math.abs(cur.top - target.top) > dpToPx(16f)) {
            int startX = clamp(cur.left, 2, screenW - 4);
            int startY = clamp(cur.top, 2, screenH - 4);
            int endX = clamp(target.left, 2, screenW - 4);
            int endY = clamp(target.top, 2, screenH - 4);
            dispatchHoldDragHoldGesture(startX, startY, endX, endY, 185L, new Runnable() {
                @Override
                public void run() {
                    runSnapStage4ResizeBottomRight(winId, target, screenW, screenH, zoneTitle, false);
                }
            });
        } else {
            runSnapStage4ResizeBottomRight(winId, target, screenW, screenH, zoneTitle, false);
        }
    }

    private void runSnapStage4ResizeBottomRight(
            final int winId,
            final Rect target,
            final int screenW,
            final int screenH,
            final String zoneTitle,
            final boolean isInnerFallback
    ) {
        Rect cur = new Rect();
        if (findTargetApplicationWindow(winId, cur, null) == -1 || cur.isEmpty()) {
            return;
        }

        if (Math.abs(cur.right - target.right) <= dpToPx(10f)
                && Math.abs(cur.bottom - target.bottom) <= dpToPx(10f)) {
            finishWindowSnapStatus(zoneTitle, target);
            return;
        }

        // First try outer resize corner (cur.right + 1dp, cur.bottom + 1dp) where DragResizeWindowGeometry
        // places the freeform resize handle outside the client area; if unchanged, fallback to inner corner (-3dp).
        int offset = isInnerFallback ? -dpToPx(3f) : dpToPx(1f);
        int startX = clamp(cur.right + offset, 4, screenW - 2);
        int startY = clamp(cur.bottom + offset, 4, screenH - 2);
        int endX = clamp(target.right + offset, 4, screenW - 2);
        int endY = clamp(target.bottom + offset, 4, screenH - 2);

        dispatchHoldDragHoldGesture(startX, startY, endX, endY, 210L, new Runnable() {
            @Override
            public void run() {
                if (!isInnerFallback) {
                    Rect check = new Rect();
                    if (findTargetApplicationWindow(winId, check, null) != -1 && !check.isEmpty()) {
                        if (Math.abs(check.right - target.right) > dpToPx(14f)
                                || Math.abs(check.bottom - target.bottom) > dpToPx(14f)) {
                            runSnapStage4ResizeBottomRight(winId, target, screenW, screenH, zoneTitle, true);
                            return;
                        }
                    }
                }
                finishWindowSnapStatus(zoneTitle, target);
            }
        });
    }

    private void finishWindowSnapStatus(String zoneTitle, Rect target) {
        String msg = "Snapped to " + zoneTitle + " (" + target.width() + "×" + target.height() + ")";
        lastActionMessage = msg;
        notifyActivityStatus();
        Toast.makeText(TopBarDropService.this, msg, Toast.LENGTH_SHORT).show();
    }

    /**
     * Dispatches a 3-phase gesture:
     *  1. 45ms stationary hold at (startX, startY) so SystemUI's DragResizeInputListener / caption
     *     bar locks onto the pointer down target,
     *  2. Smooth linear drag from (startX, startY) to (endX, endY),
     *  3. 60ms stationary hold at (endX, endY) so VelocityTracker velocity is 0 px/s before ACTION_UP.
     */
    private void dispatchHoldDragHoldGesture(
            final int startX,
            final int startY,
            final int endX,
            final int endY,
            final long dragDurationMs,
            final Runnable onComplete
    ) {
        if (Math.abs(endX - startX) < 3 && Math.abs(endY - startY) < 3) {
            if (onComplete != null) {
                handler.postDelayed(onComplete, 20L);
            }
            return;
        }
        try {
            Path holdStartPath = new Path();
            holdStartPath.moveTo(startX, startY);
            holdStartPath.lineTo(startX, startY);

            final GestureDescription.StrokeDescription holdStartStroke =
                    new GestureDescription.StrokeDescription(holdStartPath, 0L, 45L, true);
            GestureDescription startGesture = new GestureDescription.Builder()
                    .addStroke(holdStartStroke)
                    .build();

            boolean dispatched = dispatchGesture(startGesture, new GestureResultCallback() {
                @Override
                public void onCompleted(GestureDescription gestureDescription) {
                    try {
                        Path movePath = new Path();
                        movePath.moveTo(startX, startY);
                        movePath.lineTo(endX, endY);
                        final GestureDescription.StrokeDescription moveStroke =
                                holdStartStroke.continueStroke(movePath, 0L, dragDurationMs, true);
                        GestureDescription moveGesture = new GestureDescription.Builder()
                                .addStroke(moveStroke)
                                .build();

                        dispatchGesture(moveGesture, new GestureResultCallback() {
                            @Override
                            public void onCompleted(GestureDescription gestureDescription) {
                                try {
                                    Path holdEndPath = new Path();
                                    holdEndPath.moveTo(endX, endY);
                                    holdEndPath.lineTo(endX, endY);
                                    GestureDescription.StrokeDescription holdEndStroke =
                                            moveStroke.continueStroke(holdEndPath, 0L, 60L, false);
                                    GestureDescription endGesture = new GestureDescription.Builder()
                                            .addStroke(holdEndStroke)
                                            .build();

                                    dispatchGesture(endGesture, new GestureResultCallback() {
                                        @Override
                                        public void onCompleted(GestureDescription gestureDescription) {
                                            if (onComplete != null) {
                                                handler.postDelayed(onComplete, 45L);
                                            }
                                        }

                                        @Override
                                        public void onCancelled(GestureDescription gestureDescription) {
                                            if (onComplete != null) {
                                                handler.postDelayed(onComplete, 45L);
                                            }
                                        }
                                    }, handler);
                                } catch (Exception e) {
                                    if (onComplete != null) {
                                        handler.postDelayed(onComplete, 45L);
                                    }
                                }
                            }

                            @Override
                            public void onCancelled(GestureDescription gestureDescription) {
                                if (onComplete != null) {
                                    handler.postDelayed(onComplete, 45L);
                                }
                            }
                        }, handler);
                    } catch (Exception e) {
                        if (onComplete != null) {
                            handler.postDelayed(onComplete, 45L);
                        }
                    }
                }

                @Override
                public void onCancelled(GestureDescription gestureDescription) {
                    if (onComplete != null) {
                        handler.postDelayed(onComplete, 45L);
                    }
                }
            }, handler);

            if (!dispatched && onComplete != null) {
                handler.postDelayed(onComplete, 45L);
            }
        } catch (Exception e) {
            if (onComplete != null) {
                handler.postDelayed(onComplete, 45L);
            }
        }
    }

    private class SnapZonesRootView extends FrameLayout {
        private boolean releaseSnapScheduled = false;

        public SnapZonesRootView(android.content.Context context) {
            super(context);
            setFocusable(true);
            setFocusableInTouchMode(true);
        }

        @Override
        public boolean dispatchGenericMotionEvent(MotionEvent event) {
            if (event != null) {
                int action = event.getActionMasked();
                if (action == MotionEvent.ACTION_HOVER_MOVE
                        || action == MotionEvent.ACTION_HOVER_ENTER
                        || action == MotionEvent.ACTION_MOVE) {
                    int idx = SnapZonesManager.findZoneIndexAt(currentSnapZones, event.getRawX(), event.getRawY());
                    if (idx != hoveredSnapZoneIndex) {
                        hoveredSnapZoneIndex = idx;
                        if (snapZonesCanvas != null) {
                            snapZonesCanvas.invalidate();
                        }
                    }
                    // When Left-Click is released after dragging a window, InputDispatcher immediately
                    // begins sending ACTION_HOVER_ENTER / ACTION_HOVER_MOVE with BUTTON_PRIMARY == 0!
                    if ((event.getButtonState() & MotionEvent.BUTTON_PRIMARY) == 0 && !releaseSnapScheduled) {
                        if (checkIfWindowMovedFromStart()) {
                            releaseSnapScheduled = true;
                            commitHoveredSnapZoneAndDismiss();
                            return true;
                        } else {
                            // Also re-check 60ms later in case SystemUI is committing the dropped window bounds right now
                            handler.postDelayed(new Runnable() {
                                @Override
                                public void run() {
                                    if (snapZonesShown && !snapZonesDismissing && !releaseSnapScheduled
                                            && checkIfWindowMovedFromStart()) {
                                        releaseSnapScheduled = true;
                                        commitHoveredSnapZoneAndDismiss();
                                    }
                                }
                            }, 60L);
                        }
                    }
                }
                if (action == MotionEvent.ACTION_BUTTON_PRESS
                        && (event.getActionButton() == MotionEvent.BUTTON_SECONDARY
                        || event.getActionButton() == MotionEvent.BUTTON_PRIMARY)) {
                    int idx = SnapZonesManager.findZoneIndexAt(currentSnapZones, event.getRawX(), event.getRawY());
                    if (idx >= 0) {
                        hoveredSnapZoneIndex = idx;
                        commitHoveredSnapZoneAndDismiss();
                        return true;
                    }
                }
            }
            return super.dispatchGenericMotionEvent(event);
        }

        @Override
        public boolean dispatchTouchEvent(MotionEvent event) {
            if (event != null) {
                int action = event.getActionMasked();
                int idx = SnapZonesManager.findZoneIndexAt(currentSnapZones, event.getRawX(), event.getRawY());
                if (idx >= 0 && idx != hoveredSnapZoneIndex) {
                    hoveredSnapZoneIndex = idx;
                    if (snapZonesCanvas != null) {
                        snapZonesCanvas.invalidate();
                    }
                }
                if (action == MotionEvent.ACTION_DOWN || action == MotionEvent.ACTION_UP) {
                    if ((event.getButtonState() & MotionEvent.BUTTON_SECONDARY) != 0 || action == MotionEvent.ACTION_UP) {
                        if (hoveredSnapZoneIndex >= 0) {
                            commitHoveredSnapZoneAndDismiss();
                            return true;
                        }
                    }
                }
            }
            return super.dispatchTouchEvent(event);
        }

        @Override
        public boolean dispatchKeyEvent(KeyEvent event) {
            if (event != null && event.getAction() == KeyEvent.ACTION_DOWN) {
                int code = event.getKeyCode();
                if (code == KeyEvent.KEYCODE_ESCAPE) {
                    dismissSnapZonesOverlay(true);
                    return true;
                }
                if (code == KeyEvent.KEYCODE_TAB) {
                    cycleSnapZonesLayoutOnTheFly();
                    return true;
                }
                if (code == KeyEvent.KEYCODE_ENTER || code == KeyEvent.KEYCODE_SPACE) {
                    if (hoveredSnapZoneIndex >= 0) {
                        commitHoveredSnapZoneAndDismiss();
                        return true;
                    }
                }
                int zoneKeyIdx = -1;
                if (code >= KeyEvent.KEYCODE_1 && code <= KeyEvent.KEYCODE_4) {
                    zoneKeyIdx = code - KeyEvent.KEYCODE_1;
                } else if (code >= KeyEvent.KEYCODE_NUMPAD_1 && code <= KeyEvent.KEYCODE_NUMPAD_4) {
                    zoneKeyIdx = code - KeyEvent.KEYCODE_NUMPAD_1;
                }
                if (zoneKeyIdx >= 0 && zoneKeyIdx < currentSnapZones.size()) {
                    hoveredSnapZoneIndex = zoneKeyIdx;
                    commitHoveredSnapZoneAndDismiss();
                    return true;
                }
            }
            return super.dispatchKeyEvent(event);
        }
    }

    private class SnapZonesCanvasView extends View {
        private final Paint zoneFillPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint zoneHoverFillPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint zoneBorderPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint zoneHoverBorderPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint pillBgPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint pillBorderPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint titleTextPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint subTextPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final RectF tmpRectF = new RectF();
        private final RectF pillRectF = new RectF();

        public SnapZonesCanvasView(android.content.Context context) {
            super(context);
            zoneBorderPaint.setStyle(Paint.Style.STROKE);
            zoneBorderPaint.setStrokeWidth(dpToPx(2f));
            zoneBorderPaint.setPathEffect(new DashPathEffect(new float[]{dpToPx(10f), dpToPx(6f)}, 0f));

            zoneHoverBorderPaint.setStyle(Paint.Style.STROKE);
            zoneHoverBorderPaint.setStrokeWidth(dpToPx(3.5f));

            pillBorderPaint.setStyle(Paint.Style.STROKE);
            pillBorderPaint.setStrokeWidth(dpToPx(1.2f));

            titleTextPaint.setTypeface(Typeface.DEFAULT_BOLD);
            titleTextPaint.setTextAlign(Paint.Align.CENTER);

            subTextPaint.setTypeface(Typeface.create(Typeface.MONOSPACE, Typeface.BOLD));
            subTextPaint.setTextAlign(Paint.Align.CENTER);
        }

        @Override
        protected void onDraw(Canvas canvas) {
            super.onDraw(canvas);
            if (currentSnapZones == null || currentSnapZones.isEmpty()) return;

            boolean dark = isDarkMode();
            float cornerRadius = dpToPx(18f);

            for (int i = 0; i < currentSnapZones.size(); i++) {
                SnapZonesManager.SnapZone z = currentSnapZones.get(i);
                boolean hovered = (i == hoveredSnapZoneIndex);
                tmpRectF.set(z.bounds);

                if (hovered) {
                    LinearGradient hoverFillGrad = new LinearGradient(
                            tmpRectF.left, tmpRectF.top, tmpRectF.right, tmpRectF.bottom,
                            new int[]{
                                    Color.argb(dark ? 95 : 80, 163, 230, 53),
                                    Color.argb(dark ? 110 : 90, 45, 212, 191),
                                    Color.argb(dark ? 120 : 100, 13, 148, 136)
                            },
                            new float[]{0f, 0.5f, 1f},
                            Shader.TileMode.CLAMP
                    );
                    zoneHoverFillPaint.setShader(hoverFillGrad);
                    canvas.drawRoundRect(tmpRectF, cornerRadius, cornerRadius, zoneHoverFillPaint);

                    LinearGradient hoverBorderGrad = new LinearGradient(
                            tmpRectF.left, tmpRectF.top, tmpRectF.right, tmpRectF.bottom,
                            new int[]{
                                    Color.parseColor("#A3E635"),
                                    Color.parseColor("#2DD4BF"),
                                    Color.parseColor("#0D9488")
                            },
                            new float[]{0f, 0.5f, 1f},
                            Shader.TileMode.CLAMP
                    );
                    zoneHoverBorderPaint.setShader(hoverBorderGrad);
                    canvas.drawRoundRect(tmpRectF, cornerRadius, cornerRadius, zoneHoverBorderPaint);
                } else {
                    zoneFillPaint.setColor(dark ? Color.argb(95, 15, 23, 42) : Color.argb(105, 241, 245, 249));
                    canvas.drawRoundRect(tmpRectF, cornerRadius, cornerRadius, zoneFillPaint);

                    zoneBorderPaint.setColor(dark ? Color.argb(170, 45, 212, 191) : Color.argb(180, 13, 148, 136));
                    canvas.drawRoundRect(tmpRectF, cornerRadius, cornerRadius, zoneBorderPaint);
                }

                // Draw center badge inside each zone
                float cx = tmpRectF.centerX();
                float cy = tmpRectF.centerY();
                float badgeW = Math.min(tmpRectF.width() - dpToPx(24f), dpToPx(265f));
                float badgeH = dpToPx(64f);
                pillRectF.set(cx - badgeW / 2f, cy - badgeH / 2f, cx + badgeW / 2f, cy + badgeH / 2f);

                pillBgPaint.setColor(hovered
                        ? (dark ? Color.argb(235, 16, 38, 36) : Color.argb(245, 240, 253, 250))
                        : (dark ? Color.argb(215, 24, 27, 32) : Color.argb(225, 255, 255, 255)));
                canvas.drawRoundRect(pillRectF, dpToPx(16f), dpToPx(16f), pillBgPaint);

                pillBorderPaint.setColor(hovered
                        ? Color.parseColor("#2DD4BF")
                        : (dark ? Color.parseColor("#334155") : Color.parseColor("#CBD5E1")));
                canvas.drawRoundRect(pillRectF, dpToPx(16f), dpToPx(16f), pillBorderPaint);

                titleTextPaint.setTextSize(dpToPx(14.5f));
                titleTextPaint.setColor(hovered
                        ? (dark ? Color.parseColor("#5EEAD4") : Color.parseColor("#0F766E"))
                        : (dark ? Color.parseColor("#F1F5F9") : Color.parseColor("#1E293B")));
                String label = "[" + (i + 1) + "] " + z.title;
                canvas.drawText(label, cx, cy - dpToPx(5f), titleTextPaint);

                subTextPaint.setTextSize(dpToPx(11.5f));
                subTextPaint.setColor(hovered
                        ? (dark ? Color.parseColor("#A3E635") : Color.parseColor("#0D9488"))
                        : (dark ? Color.parseColor("#94A3B8") : Color.parseColor("#64748B")));
                String subLabel = hovered
                        ? "✓ Release Left-Click / Shift or Click to Snap (" + z.bounds.width() + "×" + z.bounds.height() + ")"
                        : z.subtitle + " (" + z.bounds.width() + "×" + z.bounds.height() + ")";
                canvas.drawText(subLabel, cx, cy + dpToPx(15f), subTextPaint);
            }

            // Draw Top-Center Floating HUD Pill
            int mode = SnapZonesManager.getLayoutMode(TopBarDropService.this);
            String modeName = SnapZonesManager.getLayoutDisplayName(mode);
            float hudW = Math.min(getWidth() - dpToPx(32f), dpToPx(660f));
            float hudH = dpToPx(44f);
            float hudCx = getWidth() / 2f;
            float hudTop = dpToPx(18f);
            pillRectF.set(hudCx - hudW / 2f, hudTop, hudCx + hudW / 2f, hudTop + hudH);

            pillBgPaint.setColor(dark ? Color.argb(240, 18, 21, 26) : Color.argb(245, 255, 255, 255));
            canvas.drawRoundRect(pillRectF, dpToPx(22f), dpToPx(22f), pillBgPaint);
            pillBorderPaint.setColor(Color.parseColor("#2DD4BF"));
            canvas.drawRoundRect(pillRectF, dpToPx(22f), dpToPx(22f), pillBorderPaint);

            titleTextPaint.setTextSize(dpToPx(12.5f));
            titleTextPaint.setColor(dark ? Color.parseColor("#E8EAED") : Color.parseColor("#1F2937"));
            String hudText = "⊞ Snap Zones (" + modeName + ") — Drag & release Left-Click, Click/Right-Click zone, [1]-[4], or [Tab]";
            canvas.drawText(hudText, hudCx, hudTop + dpToPx(27f), titleTextPaint);
        }
    }

    private static int clamp(int val, int min, int max) {
        return Math.max(min, Math.min(max, val));
    }

    private void notifyActivityStatus() {
        MainActivity act = MainActivity.current;
        if (act != null) {
            act.runOnUiThread(new Runnable() {
                @Override
                public void run() {
                    if (MainActivity.current != null) {
                        MainActivity.current.refreshUiState();
                    }
                }
            });
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
