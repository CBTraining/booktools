package com.example.topbardrop;

import android.accessibilityservice.AccessibilityService;
import android.content.ClipData;
import android.content.ClipDescription;
import android.content.ClipboardManager;
import android.content.ContentResolver;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.ColorSpace;
import android.graphics.ImageDecoder;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.PixelFormat;
import android.graphics.Shader;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.hardware.HardwareBuffer;
import android.net.Uri;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.util.Base64;
import android.util.DisplayMetrics;
import android.util.TypedValue;
import android.view.Display;
import android.view.DragEvent;
import android.view.Gravity;
import android.view.InputDevice;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewConfiguration;
import android.view.ViewTreeObserver;
import android.view.WindowManager;
import android.view.accessibility.AccessibilityEvent;
import android.view.animation.AccelerateInterpolator;
import android.view.animation.OvershootInterpolator;
import android.text.TextUtils;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.io.InputStream;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Global keyboard shortcut service for BookTools:
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
 *  - Automatically adapts overlays to Device Dark Mode / Light Mode settings.
 */
public class TopBarDropService extends AccessibilityService implements FrostedGlassCardLayout.BackdropProvider {

    public static volatile TopBarDropService instance;
    public static volatile String lastActionMessage =
            "Ready — Ctrl+Shift+D (PNG) · Ctrl+Shift+C (Color) · Ctrl+Shift+G (History)";

    private WindowManager windowManager;
    private Handler handler;
    private final ExecutorService ioExecutor = Executors.newSingleThreadExecutor();
    private ClipboardManager clipboardManager;
    private ClipboardManager.OnPrimaryClipChangedListener clipChangedListener;
    private View focusProbeView;

    // --- Ctrl + Shift + D (Clipboard PNG Popup) state ---
    private OverlayRootView popupRoot;
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
    private OverlayRootView historyRoot;
    private FrostedGlassCardLayout historyCard;
    private WindowManager.LayoutParams historyParams;
    private LinearLayout historyItemsContainer;
    private TextView historySubtitleView;
    private final List<Bitmap> activeHistoryThumbBitmaps = new ArrayList<>();
    private boolean historyShown = false;
    private boolean historyDismissing = false;
    private boolean historySyncedCurrentClip = false;
    private long lastHistoryTriggerMs = 0L;

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
        lastActionMessage = "Active — Ctrl+Shift+D (PNG) · Ctrl+Shift+C (Color) · Ctrl+Shift+G (History)";
        notifyActivityStatus();
        AutoUpdater.checkForUpdates(this, false, true, null);
    }

    @Override
    public void onAccessibilityEvent(AccessibilityEvent event) {
    }

    private boolean isDarkMode() {
        return MediaToolUtils.isDarkMode(this);
    }

    @Override
    public Bitmap getCapturedScreenBitmap() {
        return lastCapturedScreenBitmap;
    }

    @Override
    public Bitmap getBlurredScreenBitmap() {
        return lastBlurredScreenBitmap;
    }

    @Override
    protected boolean onKeyEvent(KeyEvent event) {
        if (event != null && event.getAction() == KeyEvent.ACTION_DOWN) {
            int keyCode = event.getKeyCode();

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
        captureBackdropAndRun(new Runnable() {
            @Override
            public void run() {
                openPopupAndProcessClipboard();
            }
        }, false);
    }

    private int getActiveDisplayId() {
        try {
            if (windowManager != null && windowManager.getDefaultDisplay() != null) {
                return windowManager.getDefaultDisplay().getDisplayId();
            }
        } catch (Exception ignored) {
        }
        return Display.DEFAULT_DISPLAY;
    }

    @android.annotation.TargetApi(Build.VERSION_CODES.R)
    private Bitmap extractSoftwareBitmap(ScreenshotResult screenshot) {
        if (screenshot == null) return null;
        try {
            HardwareBuffer hb = screenshot.getHardwareBuffer();
            ColorSpace cs = screenshot.getColorSpace();
            if (hb != null) {
                try {
                    Bitmap hw = Bitmap.wrapHardwareBuffer(hb, cs);
                    if (hw != null) {
                        Bitmap sw = hw.copy(Bitmap.Config.ARGB_8888, false);
                        hw.recycle();
                        return sw;
                    }
                } finally {
                    hb.close();
                }
            }
        } catch (Exception ignored) {
        }
        return null;
    }

    private void safeRemoveOverlayView(View view) {
        if (view != null && windowManager != null) {
            try {
                windowManager.removeViewImmediate(view);
            } catch (Exception ignored) {
                try {
                    windowManager.removeView(view);
                } catch (Exception ignored2) {
                }
            }
        }
    }

    private void captureBackdropAndRun(final Runnable openAction, final boolean isHistory) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            final boolean[] shown = new boolean[]{false};
            handler.postDelayed(new Runnable() {
                @Override
                public void run() {
                    if (!shown[0]) {
                        shown[0] = true;
                        openAction.run();
                    }
                }
            }, 85L);

            try {
                takeScreenshot(
                        getActiveDisplayId(),
                        getMainExecutor(),
                        new TakeScreenshotCallback() {
                            @Override
                            public void onSuccess(ScreenshotResult screenshot) {
                                Bitmap sw = extractSoftwareBitmap(screenshot);
                                if (sw != null) {
                                    updateCachedScreenBitmaps(sw);
                                }
                                if (!shown[0]) {
                                    shown[0] = true;
                                    openAction.run();
                                } else {
                                    View card = isHistory ? historyCard : popupCard;
                                    if (card != null) {
                                        card.invalidate();
                                    }
                                }
                            }

                            @Override
                            public void onFailure(int errorCode) {
                                if (!shown[0]) {
                                    shown[0] = true;
                                    openAction.run();
                                }
                            }
                        }
                );
                return;
            } catch (Throwable ignored) {
            }
        }
        openAction.run();
    }

    private void updateCachedScreenBitmaps(Bitmap swBitmap) {
        if (lastCapturedScreenBitmap != null && !lastCapturedScreenBitmap.isRecycled()) {
            lastCapturedScreenBitmap.recycle();
        }
        if (lastBlurredScreenBitmap != null && !lastBlurredScreenBitmap.isRecycled()) {
            lastBlurredScreenBitmap.recycle();
        }
        lastCapturedScreenBitmap = swBitmap;
        lastBlurredScreenBitmap = FrostedGlassCardLayout.createBlurredBackdropBitmap(swBitmap);
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

        popupRoot = new OverlayRootView(this, false);
        int outerPad = dpToPx(10f);
        popupRoot.setPadding(outerPad, outerPad, outerPad, outerPad);
        popupRoot.setClipChildren(false);
        popupRoot.setClipToPadding(false);

        popupCard = new FrostedGlassCardLayout(this, dark, dpToPx(22f), this);
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

        SwipeDismissTouchListener swipeListener = new SwipeDismissTouchListener(false);
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
        safeRemoveOverlayView(popupRoot);
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

    private static final class SavedPngResult {
        final Uri uri;
        final String fileName;

        SavedPngResult(Uri uri, String fileName) {
            this.uri = uri;
            this.fileName = fileName;
        }
    }

    private SavedPngResult writePngToDownloads(final Bitmap bitmap, String baseName) throws Exception {
        String timestamp = new SimpleDateFormat("HHmmss", Locale.US).format(new Date());
        String cleanBase = (baseName != null && !baseName.isEmpty())
                ? baseName + "_dl"
                : "clipboard_" + timestamp + "_dl";
        String pngFileName = cleanBase + ".png";

        MediaToolUtils.StreamSaveResult res = MediaToolUtils.saveStreamToDownloads(
                this,
                pngFileName,
                "image/png",
                out -> bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)
        );
        return new SavedPngResult(res.savedUri, pngFileName);
    }

    private void saveBitmapAsPngAndPopulatePopup(final Bitmap bitmap, final String baseName) {
        final int fullWidth = bitmap.getWidth();
        final int fullHeight = bitmap.getHeight();
        final int maxPreviewDim = dpToPx(280f);

        ioExecutor.execute(new Runnable() {
            @Override
            public void run() {
                try {
                    SavedPngResult saved = writePngToDownloads(bitmap, baseName);
                    final Uri savedUri = saved.uri;
                    final String pngFileName = saved.fileName;

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
        MediaToolUtils.FileMeta meta = MediaToolUtils.queryFileMeta(this, uri, uri.getLastPathSegment());
        return meta.displayName;
    }

    private String extractBaseName(String name) {
        if (name == null) return null;
        int slash = Math.max(name.lastIndexOf('/'), name.lastIndexOf(':'));
        if (slash >= 0 && slash + 1 < name.length()) {
            name = name.substring(slash + 1);
        }
        return MediaToolUtils.stripExtension(name, null);
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
        private final boolean isHistory;
        private float downRawX;
        private boolean swiping;
        private final int touchSlop = ViewConfiguration.get(TopBarDropService.this).getScaledTouchSlop();

        SwipeDismissTouchListener(boolean isHistory) {
            this.isHistory = isHistory;
        }

        @Override
        public boolean onTouch(View v, MotionEvent event) {
            if ((event.getButtonState() & MotionEvent.BUTTON_SECONDARY) != 0) {
                if (isHistory) {
                    dismissHistoryPopup(true);
                } else {
                    dismissPopup(true);
                }
                return true;
            }
            final View activeCard = isHistory ? historyCard : popupCard;
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
                    if (swiping && activeCard != null) {
                        activeCard.setTranslationX(dx);
                        activeCard.invalidate();
                        float progress = Math.min(1f, Math.abs(dx) / (float) dpToPx(180f));
                        activeCard.setAlpha(Math.max(0.15f, 1f - progress * 0.85f));
                        float scale = 1f - progress * 0.12f;
                        activeCard.setScaleX(scale);
                        activeCard.setScaleY(scale);
                    }
                    return true;

                case MotionEvent.ACTION_UP:
                case MotionEvent.ACTION_CANCEL:
                    if (swiping && activeCard != null) {
                        float totalDx = event.getRawX() - downRawX;
                        if (Math.abs(totalDx) > dpToPx(65f)) {
                            if (isHistory) {
                                historyDismissing = true;
                            } else {
                                popupDismissing = true;
                            }
                            final View rootToRemove = isHistory ? historyRoot : popupRoot;
                            float targetX = totalDx > 0 ? dpToPx(260f) : -dpToPx(260f);
                            activeCard.animate()
                                    .translationX(targetX)
                                    .scaleX(0.82f)
                                    .scaleY(0.82f)
                                    .alpha(0f)
                                    .setDuration(150)
                                    .withEndAction(new Runnable() {
                                        @Override
                                        public void run() {
                                            if (isHistory) {
                                                if (historyRoot == rootToRemove) {
                                                    removeHistoryPopupImmediate();
                                                }
                                            } else {
                                                if (popupRoot == rootToRemove) {
                                                    removePopupViewImmediate();
                                                }
                                            }
                                        }
                                    })
                                    .start();
                        } else {
                            activeCard.animate()
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

    private class OverlayRootView extends FrameLayout {
        private final boolean isHistory;

        public OverlayRootView(android.content.Context context, boolean isHistory) {
            super(context);
            this.isHistory = isHistory;
            setFocusable(true);
            setFocusableInTouchMode(true);
        }

        private void dismissSelf() {
            if (isHistory) {
                dismissHistoryPopup(true);
            } else {
                dismissPopup(true);
            }
        }

        @Override
        public boolean dispatchGenericMotionEvent(MotionEvent event) {
            if (event != null && (event.getButtonState() & MotionEvent.BUTTON_SECONDARY) != 0) {
                dismissSelf();
                return true;
            }
            return super.dispatchGenericMotionEvent(event);
        }

        @Override
        public boolean dispatchTouchEvent(MotionEvent event) {
            if (event != null && (event.getButtonState() & MotionEvent.BUTTON_SECONDARY) != 0) {
                dismissSelf();
                return true;
            }
            return super.dispatchTouchEvent(event);
        }

        @Override
        public boolean dispatchKeyEvent(KeyEvent event) {
            if (event != null && event.getAction() == KeyEvent.ACTION_DOWN) {
                int code = event.getKeyCode();
                if (code == KeyEvent.KEYCODE_ESCAPE) {
                    dismissSelf();
                    return true;
                }
                if (isHistory) {
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

        try {
            takeScreenshot(
                    getActiveDisplayId(),
                    getMainExecutor(),
                    new TakeScreenshotCallback() {
                        @Override
                        public void onSuccess(ScreenshotResult screenshot) {
                            if (!colorPickerCaptureInFlight) return;
                            colorPickerCaptureInFlight = false;

                            Bitmap swBitmap = extractSoftwareBitmap(screenshot);
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

    private void showColorPickerOverlay(Bitmap bitmap, String bannerHint) {
        dismissColorPicker(false);

        colorPickerRoot = new ColorPickerRootView(this);
        colorPickerCanvas = new ColorPickerCanvasView(
                this,
                bitmap,
                lastBlurredScreenBitmap,
                bannerHint,
                isDarkMode(),
                new ColorPickerCanvasView.Listener() {
                    @Override
                    public void onColorPicked(int pickedColor, String hex) {
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

                    @Override
                    public void onCancelRequested() {
                        dismissColorPicker(true);
                    }
                }
        );
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
        safeRemoveOverlayView(colorPickerRoot);
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

    private void showCopiedColorBadge(int color, String hex) {
        showFloatingStatusPill(color, true, "Copied " + hex + " to clipboard");
    }

    private void showFloatingStatusPill(int swatchColor, boolean showColorSwatch, String message) {
        boolean dark = isDarkMode();
        final FrameLayout badgeRoot = new FrameLayout(this);
        int outerPad = dpToPx(8f);
        badgeRoot.setPadding(outerPad, outerPad, outerPad, outerPad);

        final FrostedGlassCardLayout pill = new FrostedGlassCardLayout(this, dark, dpToPx(24f), this);
        pill.setOrientation(LinearLayout.HORIZONTAL);
        pill.setGravity(Gravity.CENTER_VERTICAL);
        pill.setPadding(dpToPx(14f), dpToPx(10f), dpToPx(16f), dpToPx(10f));

        if (showColorSwatch) {
            View swatch = new View(this);
            GradientDrawable swatchBg = new GradientDrawable();
            swatchBg.setShape(GradientDrawable.OVAL);
            swatchBg.setColor(swatchColor);
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
        safeRemoveOverlayView(focusProbeView);
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
            SavedPngResult saved = writePngToDownloads(bitmap, baseName);
            ClipboardHistoryManager.addImageItem(this, saved.uri, saved.fileName, bitmap, fullWidth, fullHeight);
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

        captureBackdropAndRun(new Runnable() {
            @Override
            public void run() {
                buildAndShowHistoryPopupWindow();
            }
        }, true);
    }

    private void buildAndShowHistoryPopupWindow() {
        boolean dark = isDarkMode();
        int textPrimary = dark ? Color.WHITE : Color.parseColor("#181A1F");
        int textSecondary = dark ? Color.parseColor("#D2D6DC") : Color.parseColor("#4A5058");
        int textMuted = dark ? Color.parseColor("#A8B0BA") : Color.parseColor("#68707A");
        int accentTeal = dark ? Color.parseColor("#2DD4BF") : Color.parseColor("#0D9488");

        historyRoot = new OverlayRootView(this, true);
        int outerPad = dpToPx(10f);
        historyRoot.setPadding(outerPad, outerPad, outerPad, outerPad);
        historyRoot.setClipChildren(false);
        historyRoot.setClipToPadding(false);

        historyCard = new FrostedGlassCardLayout(this, dark, dpToPx(22f), this);
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

        SwipeDismissTouchListener swipeListener = new SwipeDismissTouchListener(true);
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
            View rowView = createHistoryRowView(item, i, dark, textPrimary, textSecondary, textMuted, accentTeal, rowBgColor);
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
            int rowBgColor
    ) {
        final LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        int padH = dpToPx(10f);
        int padV = dpToPx(9f);
        row.setPadding(padH, padV, padH, padV);

        GradientDrawable bg = new GradientDrawable();
        bg.setCornerRadius(dpToPx(14f));
        bg.setColor(rowBgColor);
        row.setBackground(bg);

        // 1. Left Badge / Thumbnail / Color Swatch
        if (ClipboardHistoryManager.TYPE_IMAGE.equals(item.type)) {
            ImageView thumbView = new ImageView(this);
            thumbView.setScaleType(ImageView.ScaleType.CENTER_CROP);
            GradientDrawable thumbBg = new GradientDrawable();
            thumbBg.setCornerRadius(dpToPx(9f));
            thumbBg.setColor(dark ? Color.parseColor("#2A303C") : Color.parseColor("#E2E8F0"));
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
        safeRemoveOverlayView(historyRoot);
        historyRoot = null;
        historyCard = null;
        historyItemsContainer = null;
        historySubtitleView = null;
        historyShown = false;
        historyDismissing = false;
        releaseScreenBitmapsIfIdle();
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
        return MediaToolUtils.dpToPx(this, dp);
    }
}

