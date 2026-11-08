package com.example.topbardrop;

import android.accessibilityservice.AccessibilityService;
import android.content.ClipData;
import android.content.ClipDescription;
import android.content.ClipboardManager;
import android.content.ContentResolver;
import android.content.ContentValues;
import android.database.Cursor;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.ColorSpace;
import android.graphics.ImageDecoder;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PixelFormat;
import android.graphics.RectF;
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
import android.view.animation.DecelerateInterpolator;
import android.view.animation.OvershootInterpolator;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/**
 * Global keyboard shortcut service for Googlebook OS (Android 17 desktop):
 *  - NO top status bar icon (zero conflict with BentoBar).
 *  - Ctrl + Shift + D: Reads clipboard image (WebP, JPEG, PNG, GIF, BMP, data URI),
 *    saves it as a lossless .png in Downloads, and opens an animated popup card where
 *    you can click-and-drag the PNG into any app, or dismiss by swiping or right-clicking.
 *  - Ctrl + Shift + C: Captures the screen and opens an interactive Crosshair + Zoom
 *    Magnifier Loupe (which flips automatically between top and bottom if near screen edges)
 *    so clicking copies the pointed pixel's #RRGGBB HEX code to the clipboard.
 */
public class TopBarDropService extends AccessibilityService {

    public static volatile TopBarDropService instance;
    public static volatile String lastActionMessage =
            "Ready — Press Ctrl+Shift+D (Clipboard PNG) or Ctrl+Shift+C (Color Picker)";

    private WindowManager windowManager;
    private Handler handler;

    // --- Ctrl + Shift + D (Clipboard PNG Popup) state ---
    private PopupRootView popupRoot;
    private LinearLayout popupCard;
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
    private boolean clipboardProcessedForCurrentPopup = false;
    private long lastShortcutTriggerMs = 0L;

    // --- Ctrl + Shift + C (Screen Color Picker) state ---
    private ColorPickerOverlayView colorPickerView;
    private WindowManager.LayoutParams colorPickerParams;
    private boolean colorPickerShown = false;
    private boolean colorPickerDismissing = false;
    private Bitmap lastCapturedScreenBitmap;
    private long lastColorPickerTriggerMs = 0L;

    @Override
    public void onCreate() {
        super.onCreate();
        instance = this;
        handler = new Handler(Looper.getMainLooper());
        windowManager = (WindowManager) getSystemService(WINDOW_SERVICE);
    }

    @Override
    protected void onServiceConnected() {
        super.onServiceConnected();
        instance = this;
        lastActionMessage = "Active — Ctrl+Shift+D (Clipboard PNG) · Ctrl+Shift+C (Color Picker)";
        notifyActivityStatus();
    }

    @Override
    public void onAccessibilityEvent(AccessibilityEvent event) {
        // No persistent overlay needed; we react strictly to keyboard shortcuts.
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
    public boolean onUnbind( android.content.Intent intent) {
        dismissPopup(false);
        dismissColorPicker(false);
        instance = null;
        notifyActivityStatus();
        return super.onUnbind(intent);
    }

    @Override
    public void onDestroy() {
        dismissPopup(false);
        dismissColorPicker(false);
        if (lastCapturedScreenBitmap != null && !lastCapturedScreenBitmap.isRecycled()) {
            lastCapturedScreenBitmap.recycle();
            lastCapturedScreenBitmap = null;
        }
        instance = null;
        notifyActivityStatus();
        super.onDestroy();
    }

    // =========================================================================
    // 1. CTRL + SHIFT + D: CLIPBOARD IMAGE TO PNG POPUP (WITH GROW/SHRINK ANIM)
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

        // Close color picker if open
        if (colorPickerShown) {
            dismissColorPicker(false);
        }

        // Remove any previous popup before animating the new one in
        dismissPopup(false);
        clipboardProcessedForCurrentPopup = false;
        currentPngUri = null;
        currentPngFileName = null;

        buildAndShowPopupWindow();

        // Try immediately and retry at 60ms / 150ms / 300ms once WindowManager grants focus
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
        popupRoot = new PopupRootView(this);
        int outerPad = dpToPx(10f);
        popupRoot.setPadding(outerPad, outerPad, outerPad, outerPad);
        popupRoot.setClipChildren(false);
        popupRoot.setClipToPadding(false);

        popupCard = new LinearLayout(this);
        popupCard.setOrientation(LinearLayout.VERTICAL);
        int cardPad = dpToPx(16f);
        popupCard.setPadding(cardPad, dpToPx(14f), cardPad, cardPad);

        GradientDrawable cardBg = new GradientDrawable();
        cardBg.setShape(GradientDrawable.RECTANGLE);
        cardBg.setCornerRadius(dpToPx(20f));
        cardBg.setColor(Color.parseColor("#202124"));
        cardBg.setStroke(dpToPx(1.5f), Color.parseColor("#5F6368"));
        popupCard.setBackground(cardBg);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            popupCard.setElevation(dpToPx(8f));
        }

        // Header bar (supports horizontal swipe-to-dismiss)
        LinearLayout headerRow = new LinearLayout(this);
        headerRow.setOrientation(LinearLayout.HORIZONTAL);
        headerRow.setGravity(Gravity.CENTER_VERTICAL);

        ImageView headerIcon = new ImageView(this);
        headerIcon.setImageResource(R.drawable.ic_drop_arrow);
        LinearLayout.LayoutParams iconLp = new LinearLayout.LayoutParams(dpToPx(20f), dpToPx(20f));
        iconLp.rightMargin = dpToPx(8f);
        headerRow.addView(headerIcon, iconLp);

        headerTitleView = new TextView(this);
        headerTitleView.setText("Reading clipboard image...");
        headerTitleView.setTextColor(Color.WHITE);
        headerTitleView.setTypeface(Typeface.DEFAULT_BOLD);
        headerTitleView.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f);
        headerRow.addView(headerTitleView, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));

        TextView closeBtn = new TextView(this);
        closeBtn.setText("✕");
        closeBtn.setTextColor(Color.parseColor("#9AA0A6"));
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
        subtitleView.setTextColor(Color.parseColor("#BDC1C6"));
        subtitleView.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f);
        subtitleView.setPadding(0, dpToPx(4f), 0, dpToPx(10f));
        popupCard.addView(subtitleView);

        // Draggable PNG Preview Box
        dragBox = new LinearLayout(this);
        dragBox.setOrientation(LinearLayout.VERTICAL);
        dragBox.setGravity(Gravity.CENTER);
        int boxPad = dpToPx(12f);
        dragBox.setPadding(boxPad, boxPad, boxPad, boxPad);

        GradientDrawable boxBg = new GradientDrawable();
        boxBg.setCornerRadius(dpToPx(14f));
        boxBg.setColor(Color.parseColor("#303134"));
        boxBg.setStroke(dpToPx(1.5f), Color.parseColor("#8AB4F8"));
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
        dragHintView.setTextColor(Color.parseColor("#8AB4F8"));
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
        footerHint.setTextColor(Color.parseColor("#9AA0A6"));
        footerHint.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f);
        footerHint.setGravity(Gravity.CENTER);
        footerHint.setPadding(0, dpToPx(10f), 0, 0);
        popupCard.addView(footerHint);

        // Attach swipe-to-dismiss on the header, subtitle, and footer
        SwipeDismissTouchListener swipeListener = new SwipeDismissTouchListener();
        headerRow.setOnTouchListener(swipeListener);
        subtitleView.setOnTouchListener(swipeListener);
        footerHint.setOnTouchListener(swipeListener);

        popupRoot.addView(popupCard, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.WRAP_CONTENT
        ));

        // Listen for drag completion so the popup restores opacity or closes after drop
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
        popupParams.width = dpToPx(324f);
        popupParams.height = WindowManager.LayoutParams.WRAP_CONTENT;
        popupParams.x = dpToPx(18f);
        popupParams.y = dpToPx(46f);
        popupParams.flags = WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
                | WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL
                | WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED;
        popupParams.setTitle("TopBarDrop PNG Popup");

        // Prepare initial state for Grow + Fade-In animation
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
        if (text == null) return null;
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

    private void saveBitmapAsPngAndPopulatePopup(Bitmap bitmap, String baseName) {
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

            currentPngUri = savedUri;
            currentPngFileName = pngFileName;

            headerTitleView.setText("Saved PNG: " + pngFileName);
            subtitleView.setText(bitmap.getWidth() + " × " + bitmap.getHeight() + " px · Downloaded to Downloads");
            previewImageView.setImageBitmap(bitmap);
            dragHintView.setText("⬇ Click & Drag this PNG into any app");

            // Subtle pulse on the preview box when the image finishes converting
            dragBox.setScaleX(0.95f);
            dragBox.setScaleY(0.95f);
            dragBox.animate()
                    .scaleX(1.0f)
                    .scaleY(1.0f)
                    .setDuration(160)
                    .setInterpolator(new OvershootInterpolator(1.2f))
                    .start();

            lastActionMessage = "Saved " + pngFileName + " (" + bitmap.getWidth() + "×" + bitmap.getHeight() + ") to Downloads";
            notifyActivityStatus();
        } catch (Exception e) {
            showPopupError("Failed to save PNG", e.getMessage());
        }
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
    // 2. CTRL + SHIFT + C: SCREEN COLOR PICKER (CROSSHAIR + ZOOM LOUPE -> HEX)
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
        if (colorPickerShown) {
            dismissColorPicker(false);
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            captureScreenAndOpenColorPicker(0);
        } else {
            Toast.makeText(this, "Color picker requires Android 11+", Toast.LENGTH_SHORT).show();
        }
    }

    private void captureScreenAndOpenColorPicker(final int attempt) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
            return;
        }
        try {
            takeScreenshot(
                    Display.DEFAULT_DISPLAY,
                    getMainExecutor(),
                    new TakeScreenshotCallback() {
                        @Override
                        public void onSuccess(ScreenshotResult screenshot) {
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
                                if (lastCapturedScreenBitmap != null && !lastCapturedScreenBitmap.isRecycled()) {
                                    lastCapturedScreenBitmap.recycle();
                                }
                                lastCapturedScreenBitmap = swBitmap;
                                showColorPickerWithBitmap(swBitmap);
                            } else if (lastCapturedScreenBitmap != null && !lastCapturedScreenBitmap.isRecycled()) {
                                showColorPickerWithBitmap(lastCapturedScreenBitmap);
                            } else {
                                Toast.makeText(TopBarDropService.this, "Could not read screen pixels", Toast.LENGTH_SHORT).show();
                            }
                        }

                        @Override
                        public void onFailure(int errorCode) {
                            // If triggered rapidly within Android's screenshot interval window, retry or use cached screenshot
                            if (attempt == 0) {
                                handler.postDelayed(new Runnable() {
                                    @Override
                                    public void run() {
                                        captureScreenAndOpenColorPicker(1);
                                    }
                                }, 320);
                            } else if (lastCapturedScreenBitmap != null && !lastCapturedScreenBitmap.isRecycled()) {
                                showColorPickerWithBitmap(lastCapturedScreenBitmap);
                            } else {
                                Toast.makeText(TopBarDropService.this,
                                        "Screenshot busy (code " + errorCode + "). Try Ctrl+Shift+C again.",
                                        Toast.LENGTH_SHORT).show();
                            }
                        }
                    }
            );
        } catch (Exception e) {
            Toast.makeText(this, "Color picker error: " + e.getMessage(), Toast.LENGTH_SHORT).show();
        }
    }

    private void showColorPickerWithBitmap(Bitmap bitmap) {
        dismissColorPicker(false);

        colorPickerView = new ColorPickerOverlayView(this, bitmap);

        colorPickerParams = new WindowManager.LayoutParams();
        colorPickerParams.type = WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY;
        colorPickerParams.format = PixelFormat.TRANSLUCENT;
        colorPickerParams.gravity = Gravity.TOP | Gravity.LEFT;
        colorPickerParams.width = WindowManager.LayoutParams.MATCH_PARENT;
        colorPickerParams.height = WindowManager.LayoutParams.MATCH_PARENT;
        colorPickerParams.flags = WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
                | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS
                | WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED;
        colorPickerParams.setTitle("TopBarDrop Color Picker");

        try {
            windowManager.addView(colorPickerView, colorPickerParams);
            colorPickerShown = true;
            colorPickerDismissing = false;
            colorPickerView.requestFocus();
            colorPickerView.animateIn();
        } catch (Exception e) {
            colorPickerShown = false;
            colorPickerDismissing = false;
        }
    }

    public void dismissColorPicker(boolean animate) {
        if (!colorPickerShown || colorPickerView == null) {
            return;
        }
        if (!animate) {
            removeColorPickerImmediate();
            return;
        }
        if (colorPickerDismissing) {
            return;
        }
        colorPickerDismissing = true;
        final ColorPickerOverlayView viewToRemove = colorPickerView;
        colorPickerView.animateOut(new Runnable() {
            @Override
            public void run() {
                if (colorPickerView == viewToRemove) {
                    removeColorPickerImmediate();
                }
            }
        });
    }

    private void removeColorPickerImmediate() {
        if (colorPickerView != null && windowManager != null) {
            try {
                windowManager.removeViewImmediate(colorPickerView);
            } catch (Exception ignored) {
                try {
                    windowManager.removeView(colorPickerView);
                } catch (Exception ignored2) {
                }
            }
        }
        colorPickerView = null;
        colorPickerShown = false;
        colorPickerDismissing = false;
    }

    /**
     * Full-screen interactive Crosshair + Magnifier Loupe view.
     * - Displays the frozen screen snapshot so nothing moves while picking a pixel.
     * - Tracks mouse hover (ACTION_HOVER_MOVE) and touch/drag (ACTION_MOVE).
     * - Renders a magnified 11x11 pixel grid + #RRGGBB HEX pill above the crosshair,
     *   and automatically flips it BELOW the crosshair if the top zoom preview would go out of view!
     * - Clicking copies the pointed #RRGGBB HEX code to the clipboard and shows a sleek confirmation badge.
     */
    private class ColorPickerOverlayView extends View {
        private final Bitmap screenBmp;
        private float pointerX = -1f;
        private float pointerY = -1f;
        private float loupeScale = 0f;

        private final int gridRadius = 5; // 11 x 11 pixel grid
        private final int gridCount = gridRadius * 2 + 1;

        private final Paint bmpPaint = new Paint();
        private final Paint pixelPaint = new Paint();
        private final Paint gridLinePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint cardPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint cardStrokePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint textPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint subTextPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint crosshairDarkPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint crosshairLightPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final RectF tempRect = new RectF();
        private final Path clipPath = new Path();

        private float touchDownX;
        private float touchDownY;
        private boolean touchDragged;

        public ColorPickerOverlayView(android.content.Context context, Bitmap bitmap) {
            super(context);
            this.screenBmp = bitmap;
            setFocusable(true);
            setFocusableInTouchMode(true);

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                setPointerIcon(PointerIcon.getSystemIcon(context, PointerIcon.TYPE_CROSSHAIR));
            }

            gridLinePaint.setStyle(Paint.Style.STROKE);
            gridLinePaint.setStrokeWidth(1f);
            gridLinePaint.setColor(0x28FFFFFF);

            cardPaint.setStyle(Paint.Style.FILL);
            cardPaint.setColor(Color.parseColor("#202124"));
            cardPaint.setShadowLayer(dpToPx(8f), 0f, dpToPx(3f), 0x88000000);

            cardStrokePaint.setStyle(Paint.Style.STROKE);
            cardStrokePaint.setStrokeWidth(dpToPx(1.5f));
            cardStrokePaint.setColor(Color.parseColor("#8AB4F8"));

            textPaint.setColor(Color.WHITE);
            textPaint.setTypeface(Typeface.create(Typeface.MONOSPACE, Typeface.BOLD));
            textPaint.setTextSize(TypedValue.applyDimension(
                    TypedValue.COMPLEX_UNIT_SP, 14f, getResources().getDisplayMetrics()));

            subTextPaint.setColor(Color.parseColor("#BDC1C6"));
            subTextPaint.setTypeface(Typeface.DEFAULT);
            subTextPaint.setTextSize(TypedValue.applyDimension(
                    TypedValue.COMPLEX_UNIT_SP, 11f, getResources().getDisplayMetrics()));

            crosshairDarkPaint.setStyle(Paint.Style.STROKE);
            crosshairDarkPaint.setStrokeWidth(dpToPx(2.5f));
            crosshairDarkPaint.setColor(0xCC000000);
            crosshairDarkPaint.setStrokeCap(Paint.Cap.ROUND);

            crosshairLightPaint.setStyle(Paint.Style.STROKE);
            crosshairLightPaint.setStrokeWidth(dpToPx(1.5f));
            crosshairLightPaint.setColor(0xFFFFFFFF);
            crosshairLightPaint.setStrokeCap(Paint.Cap.ROUND);
        }

        public void animateIn() {
            setAlpha(0f);
            loupeScale = 0.72f;
            animate()
                    .alpha(1f)
                    .setDuration(160)
                    .setInterpolator(new DecelerateInterpolator())
                    .setUpdateListener(new android.animation.ValueAnimator.AnimatorUpdateListener() {
                        @Override
                        public void onAnimationUpdate(android.animation.ValueAnimator animation) {
                            float f = animation.getAnimatedFraction();
                            loupeScale = 0.72f + 0.28f * f;
                            invalidate();
                        }
                    })
                    .start();
        }

        public void animateOut(final Runnable onEnd) {
            animate()
                    .alpha(0f)
                    .setDuration(140)
                    .setInterpolator(new AccelerateInterpolator())
                    .setUpdateListener(new android.animation.ValueAnimator.AnimatorUpdateListener() {
                        @Override
                        public void onAnimationUpdate(android.animation.ValueAnimator animation) {
                            float f = animation.getAnimatedFraction();
                            loupeScale = 1.0f - 0.25f * f;
                            invalidate();
                        }
                    })
                    .withEndAction(onEnd)
                    .start();
        }

        @Override
        protected void onSizeChanged(int w, int h, int oldw, int oldh) {
            super.onSizeChanged(w, h, oldw, oldh);
            if (pointerX < 0f || pointerY < 0f) {
                pointerX = w / 2f;
                pointerY = h / 2f;
            }
        }

        @Override
        protected void onDraw(Canvas canvas) {
            super.onDraw(canvas);
            int viewW = getWidth();
            int viewH = getHeight();
            if (viewW <= 0 || viewH <= 0 || screenBmp == null || screenBmp.isRecycled()) {
                return;
            }

            // Draw the frozen screenshot underneath so every pixel stays still while inspecting
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

            // 1. Draw high-contrast precision Crosshair at (pointerX, pointerY)
            float chGap = dpToPx(5f);
            float chLen = dpToPx(16f);

            // Outer dark strokes for contrast on white backgrounds
            canvas.drawLine(pointerX - chLen, pointerY, pointerX - chGap, pointerY, crosshairDarkPaint);
            canvas.drawLine(pointerX + chGap, pointerY, pointerX + chLen, pointerY, crosshairDarkPaint);
            canvas.drawLine(pointerX, pointerY - chLen, pointerX, pointerY - chGap, crosshairDarkPaint);
            canvas.drawLine(pointerX, pointerY + chGap, pointerX, pointerY + chLen, crosshairDarkPaint);
            canvas.drawCircle(pointerX, pointerY, chGap, crosshairDarkPaint);

            // Inner white strokes for contrast on dark backgrounds
            canvas.drawLine(pointerX - chLen, pointerY, pointerX - chGap, pointerY, crosshairLightPaint);
            canvas.drawLine(pointerX + chGap, pointerY, pointerX + chLen, pointerY, crosshairLightPaint);
            canvas.drawLine(pointerX, pointerY - chLen, pointerX, pointerY - chGap, crosshairLightPaint);
            canvas.drawLine(pointerX, pointerY + chGap, pointerX, pointerY + chLen, crosshairLightPaint);
            canvas.drawCircle(pointerX, pointerY, chGap, crosshairLightPaint);

            // 2. Compute Zoom Loupe Card bounds (auto-flips below crosshair if near top edge!)
            float cellPx = dpToPx(11f);
            float gridSize = cellPx * gridCount; // ~121dp
            float pad = dpToPx(10f);
            float infoHeight = dpToPx(46f);
            float cardW = gridSize + pad * 2f;
            float cardH = gridSize + pad * 2f + infoHeight;
            float cursorOffset = dpToPx(26f);
            float screenEdgeMargin = dpToPx(12f);

            // Default: ON TOP OF the crosshair
            float cardLeft = pointerX - cardW / 2f;
            float cardTop = pointerY - cursorOffset - cardH;

            // Flip to BOTTOM of the crosshair if the top zoom preview would be out of view
            if (cardTop < screenEdgeMargin) {
                cardTop = pointerY + cursorOffset;
            }
            // Also clamp bottom if screen is very short
            if (cardTop + cardH > viewH - screenEdgeMargin) {
                cardTop = viewH - screenEdgeMargin - cardH;
            }
            // Clamp left/right so the zoom loupe never goes off-screen horizontally
            if (cardLeft < screenEdgeMargin) {
                cardLeft = screenEdgeMargin;
            } else if (cardLeft + cardW > viewW - screenEdgeMargin) {
                cardLeft = viewW - screenEdgeMargin - cardW;
            }

            float cardCenterX = cardLeft + cardW / 2f;
            float cardCenterY = cardTop + cardH / 2f;

            canvas.save();
            canvas.scale(loupeScale, loupeScale, pointerX, pointerY);

            // Draw Loupe Card background
            tempRect.set(cardLeft, cardTop, cardLeft + cardW, cardTop + cardH);
            float cornerRadius = dpToPx(16f);
            canvas.drawRoundRect(tempRect, cornerRadius, cornerRadius, cardPaint);
            canvas.drawRoundRect(tempRect, cornerRadius, cornerRadius, cardStrokePaint);

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
            Paint centerOuter = new Paint(Paint.ANTI_ALIAS_FLAG);
            centerOuter.setStyle(Paint.Style.STROKE);
            centerOuter.setStrokeWidth(dpToPx(2f));
            centerOuter.setColor(Color.WHITE);
            canvas.drawRect(tempRect, centerOuter);

            tempRect.inset(dpToPx(1.5f), dpToPx(1.5f));
            Paint centerInner = new Paint(Paint.ANTI_ALIAS_FLAG);
            centerInner.setStyle(Paint.Style.STROKE);
            centerInner.setStrokeWidth(dpToPx(1f));
            centerInner.setColor(Color.BLACK);
            canvas.drawRect(tempRect, centerInner);

            // Draw Color Swatch + #RRGGBB HEX + RGB(r,g,b) below the magnified grid
            float infoTop = gridTop + gridSize + dpToPx(8f);
            float swatchSize = dpToPx(24f);
            float swatchLeft = gridLeft + dpToPx(2f);
            float swatchTop = infoTop + dpToPx(4f);

            tempRect.set(swatchLeft, swatchTop, swatchLeft + swatchSize, swatchTop + swatchSize);
            pixelPaint.setColor(centerColor);
            pixelPaint.setAntiAlias(true);
            canvas.drawRoundRect(tempRect, dpToPx(6f), dpToPx(6f), pixelPaint);
            canvas.drawRoundRect(tempRect, dpToPx(6f), dpToPx(6f), crosshairLightPaint);

            float textLeft = swatchLeft + swatchSize + dpToPx(8f);
            canvas.drawText(hex, textLeft, swatchTop + dpToPx(13f), textPaint);
            canvas.drawText("RGB(" + r + "," + g + "," + b + ")", textLeft, swatchTop + dpToPx(26f), subTextPaint);

            canvas.restore();
        }

        @Override
        public boolean onHoverEvent(MotionEvent event) {
            if (event != null) {
                pointerX = event.getX();
                pointerY = event.getY();
                invalidate();
                return true;
            }
            return super.onHoverEvent(event);
        }

        @Override
        public boolean dispatchGenericMotionEvent(MotionEvent event) {
            if (event != null) {
                if ((event.getButtonState() & MotionEvent.BUTTON_SECONDARY) != 0) {
                    dismissColorPicker(true);
                    return true;
                }
                int action = event.getActionMasked();
                if (action == MotionEvent.ACTION_HOVER_MOVE
                        || action == MotionEvent.ACTION_HOVER_ENTER
                        || action == MotionEvent.ACTION_SCROLL) {
                    pointerX = event.getX();
                    pointerY = event.getY();
                    invalidate();
                    return true;
                }
            }
            return super.dispatchGenericMotionEvent(event);
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
                    pointerX = event.getX();
                    pointerY = event.getY();
                    touchDownX = pointerX;
                    touchDownY = pointerY;
                    touchDragged = false;
                    invalidate();
                    if (isMouse) {
                        pickCurrentColorAndClose();
                    }
                    return true;

                case MotionEvent.ACTION_MOVE:
                    pointerX = event.getX();
                    pointerY = event.getY();
                    if (Math.hypot(pointerX - touchDownX, pointerY - touchDownY) > dpToPx(4f)) {
                        touchDragged = true;
                    }
                    invalidate();
                    return true;

                case MotionEvent.ACTION_UP:
                    pointerX = event.getX();
                    pointerY = event.getY();
                    invalidate();
                    if (!isMouse) {
                        pickCurrentColorAndClose();
                    }
                    return true;
            }
            return super.onTouchEvent(event);
        }

        @Override
        public boolean dispatchKeyEvent(KeyEvent event) {
            if (event != null && event.getAction() == KeyEvent.ACTION_DOWN) {
                if (event.getKeyCode() == KeyEvent.KEYCODE_ESCAPE) {
                    dismissColorPicker(true);
                    return true;
                }
                if (event.getKeyCode() == KeyEvent.KEYCODE_ENTER || event.getKeyCode() == KeyEvent.KEYCODE_SPACE) {
                    pickCurrentColorAndClose();
                    return true;
                }
            }
            return super.dispatchKeyEvent(event);
        }

        private void pickCurrentColorAndClose() {
            if (screenBmp == null || screenBmp.isRecycled()) {
                dismissColorPicker(true);
                return;
            }
            int bmpX = clamp(Math.round(pointerX), 0, screenBmp.getWidth() - 1);
            int bmpY = clamp(Math.round(pointerY), 0, screenBmp.getHeight() - 1);
            int pickedColor = screenBmp.getPixel(bmpX, bmpY);
            String hex = String.format(Locale.US, "#%06X", (0xFFFFFF & pickedColor));

            ClipboardManager cm = getSystemService(ClipboardManager.class);
            if (cm == null) {
                cm = (ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
            }
            if (cm != null) {
                cm.setPrimaryClip(ClipData.newPlainText("Color HEX", hex));
            }

            lastActionMessage = "Copied color " + hex + " to clipboard";
            notifyActivityStatus();
            showCopiedColorBadge(pickedColor, hex);
            dismissColorPicker(true);
        }
    }

    /**
     * Briefly displays a sleek animated pill in the top-right confirming the copied #RRGGBB HEX color.
     */
    private void showCopiedColorBadge(int color, String hex) {
        final FrameLayout badgeRoot = new FrameLayout(this);
        int outerPad = dpToPx(8f);
        badgeRoot.setPadding(outerPad, outerPad, outerPad, outerPad);

        final LinearLayout pill = new LinearLayout(this);
        pill.setOrientation(LinearLayout.HORIZONTAL);
        pill.setGravity(Gravity.CENTER_VERTICAL);
        pill.setPadding(dpToPx(14f), dpToPx(10f), dpToPx(16f), dpToPx(10f));

        GradientDrawable pillBg = new GradientDrawable();
        pillBg.setCornerRadius(dpToPx(24f));
        pillBg.setColor(Color.parseColor("#202124"));
        pillBg.setStroke(dpToPx(1.5f), Color.parseColor("#5F6368"));
        pill.setBackground(pillBg);

        View swatch = new View(this);
        GradientDrawable swatchBg = new GradientDrawable();
        swatchBg.setShape(GradientDrawable.OVAL);
        swatchBg.setColor(color);
        swatchBg.setStroke(dpToPx(1.5f), Color.WHITE);
        swatch.setBackground(swatchBg);
        LinearLayout.LayoutParams swatchLp = new LinearLayout.LayoutParams(dpToPx(20f), dpToPx(20f));
        swatchLp.rightMargin = dpToPx(10f);
        pill.addView(swatch, swatchLp);

        TextView label = new TextView(this);
        label.setText("Copied " + hex + " to clipboard");
        label.setTextColor(Color.WHITE);
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
