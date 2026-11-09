package com.example.topbardrop;

import android.accessibilityservice.AccessibilityService;
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
import android.graphics.ImageDecoder;
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
public class TopBarDropService extends AccessibilityService {

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
        int nightMask = getResources().getConfiguration().uiMode & Configuration.UI_MODE_NIGHT_MASK;
        return nightMask == Configuration.UI_MODE_NIGHT_YES;
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
     * coordinates + translucent glass sheen), leaving the rest of the screen 100% sharp and
     * unblurred with no outline border.
     */
    private class FrostedGlassCardLayout extends LinearLayout {
        private final boolean dark;
        private final float cornerRadius;
        private final Path clipPath = new Path();
        private final RectF cardBounds = new RectF();
        private final Rect screenDestRect = new Rect();
        private final int[] locOnScreen = new int[2];

        private final Paint bmpPaint = new Paint(Paint.FILTER_BITMAP_FLAG);
        private final Paint tintPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint sheenPaint = new Paint(Paint.ANTI_ALIAS_FLAG);

        public FrostedGlassCardLayout(android.content.Context context, boolean dark, float cornerRadius) {
            super(context);
            this.dark = dark;
            this.cornerRadius = cornerRadius;
            setWillNotDraw(false);

            tintPaint.setStyle(Paint.Style.FILL);
            tintPaint.setColor(dark ? Color.parseColor("#A81A1D26") : Color.parseColor("#B8F2F6FC"));
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
