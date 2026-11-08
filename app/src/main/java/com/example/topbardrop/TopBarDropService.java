package com.example.topbardrop;

import android.accessibilityservice.AccessibilityService;
import android.accessibilityservice.AccessibilityServiceInfo;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.Intent;
import android.database.Cursor;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Color;
import android.graphics.ImageDecoder;
import android.graphics.PixelFormat;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.provider.MediaStore;
import android.provider.OpenableColumns;
import android.util.Base64;
import android.util.TypedValue;
import android.view.DragEvent;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewTreeObserver;
import android.view.WindowManager;
import android.view.accessibility.AccessibilityEvent;
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
 * Listens globally for Ctrl + Shift + D (via AccessibilityService.onKeyEvent),
 * without placing any icon on the top status bar (so BentoBar has zero conflicts).
 *
 * When Ctrl + Shift + D is pressed:
 * 1. Opens a focusable floating notification card at the top-right (receiving window focus
 *    allows reading ClipboardManager on Android 10-17).
 * 2. Reads the clipboard's contents (WebP, JPEG, PNG, GIF, BMP, or data:image base64),
 *    converts it to a lossless PNG, and saves it into Downloads.
 * 3. Displays the PNG inside the popup window so you can immediately click & drag the PNG
 *    into any app, or dismiss the popup by swiping it away or right-clicking it.
 */
public class TopBarDropService extends AccessibilityService {

    public static volatile TopBarDropService instance;
    public static volatile String lastActionMessage = "Press Ctrl + Shift + D after copying any image.";

    private WindowManager windowManager;
    private final Handler handler = new Handler(Looper.getMainLooper());

    private PopupRootView popupRoot;
    private WindowManager.LayoutParams popupParams;
    private boolean popupShown = false;
    private boolean clipboardProcessedForCurrentPopup = false;

    private TextView headerTitleView;
    private TextView subtitleView;
    private ImageView previewImageView;
    private LinearLayout dragBox;
    private TextView dragHintView;

    private Uri currentPngUri;
    private String currentPngFileName;

    @Override
    protected void onServiceConnected() {
        super.onServiceConnected();
        instance = this;
        windowManager = getSystemService(WindowManager.class);
        if (windowManager == null) {
            windowManager = (WindowManager) getSystemService(WINDOW_SERVICE);
        }

        // Ensure key event filtering is active for Ctrl + Shift + D
        try {
            AccessibilityServiceInfo info = getServiceInfo();
            if (info != null) {
                info.flags |= AccessibilityServiceInfo.FLAG_REQUEST_FILTER_KEY_EVENTS;
                setServiceInfo(info);
            }
        } catch (Exception ignored) {
        }

        notifyActivityStatus();
    }

    @Override
    protected boolean onKeyEvent(KeyEvent event) {
        if (event != null
                && event.getAction() == KeyEvent.ACTION_DOWN
                && event.getKeyCode() == KeyEvent.KEYCODE_D
                && event.isCtrlPressed()
                && event.isShiftPressed()) {
            handler.post(new Runnable() {
                @Override
                public void run() {
                    triggerClipboardToPngPopup();
                }
            });
            return true; // Consume Ctrl + Shift + D
        }
        return false;
    }

    @Override
    public void onAccessibilityEvent(AccessibilityEvent event) {
    }

    @Override
    public void onInterrupt() {
    }

    /**
     * Shows the focusable popup window and converts the clipboard image to PNG as soon as
     * Android grants window focus to our overlay.
     */
    public void triggerClipboardToPngPopup() {
        dismissPopup();
        clipboardProcessedForCurrentPopup = false;
        currentPngUri = null;
        currentPngFileName = null;

        buildAndShowPopupWindow();

        // Try immediately (works if MainActivity already had focus) and retry at 50ms / 150ms / 300ms
        // once WindowManager transfers focus to our TYPE_ACCESSIBILITY_OVERLAY popup.
        int[] delays = new int[]{0, 60, 150, 300};
        for (int delay : delays) {
            handler.postDelayed(new Runnable() {
                @Override
                public void run() {
                    if (popupShown && !clipboardProcessedForCurrentPopup) {
                        tryProcessClipboardImage(false);
                    }
                }
            }, delay);
        }
        handler.postDelayed(new Runnable() {
            @Override
            public void run() {
                if (popupShown && !clipboardProcessedForCurrentPopup) {
                    tryProcessClipboardImage(true);
                }
            }
        }, 450);
    }

    private void buildAndShowPopupWindow() {
        popupRoot = new PopupRootView(this);
        int outerPad = dpToPx(8f);
        popupRoot.setPadding(outerPad, outerPad, outerPad, outerPad);

        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        int cardPad = dpToPx(16f);
        card.setPadding(cardPad, dpToPx(14f), cardPad, cardPad);

        GradientDrawable cardBg = new GradientDrawable();
        cardBg.setShape(GradientDrawable.RECTANGLE);
        cardBg.setCornerRadius(dpToPx(20f));
        cardBg.setColor(Color.parseColor("#202124")); // Sleek Dark Material 3 Surface
        cardBg.setStroke(dpToPx(1.5f), Color.parseColor("#5F6368"));
        card.setBackground(cardBg);

        // Header bar (supports horizontal swipe-to-dismiss)
        LinearLayout headerRow = new LinearLayout(this);
        headerRow.setOrientation(LinearLayout.HORIZONTAL);
        headerRow.setGravity(Gravity.CENTER_VERTICAL);

        headerTitleView = new TextView(this);
        headerTitleView.setText("⏳ Reading clipboard image...");
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
                dismissPopup();
            }
        });
        headerRow.addView(closeBtn);
        card.addView(headerRow);

        subtitleView = new TextView(this);
        subtitleView.setText("Converting clipboard image to PNG in Downloads...");
        subtitleView.setTextColor(Color.parseColor("#BDC1C6"));
        subtitleView.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f);
        subtitleView.setPadding(0, dpToPx(4f), 0, dpToPx(10f));
        card.addView(subtitleView);

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
        card.addView(dragBox, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
        ));

        TextView footerHint = new TextView(this);
        footerHint.setText("Swipe header/footer or Right-click anywhere to dismiss");
        footerHint.setTextColor(Color.parseColor("#9AA0A6"));
        footerHint.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f);
        footerHint.setGravity(Gravity.CENTER);
        footerHint.setPadding(0, dpToPx(10f), 0, 0);
        card.addView(footerHint);

        // Attach swipe-to-dismiss on the header, subtitle, and footer
        SwipeDismissTouchListener swipeListener = new SwipeDismissTouchListener();
        headerRow.setOnTouchListener(swipeListener);
        subtitleView.setOnTouchListener(swipeListener);
        footerHint.setOnTouchListener(swipeListener);

        popupRoot.addView(card, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.WRAP_CONTENT
        ));

        // Listen for drag completion so the popup restores opacity or closes after drop
        popupRoot.setOnDragListener(new View.OnDragListener() {
            @Override
            public boolean onDrag(View v, DragEvent event) {
                if (event.getAction() == DragEvent.ACTION_DRAG_ENDED) {
                    if (popupRoot != null) {
                        popupRoot.setAlpha(1.0f);
                    }
                    if (event.getResult()) {
                        dismissPopup();
                    }
                }
                return true;
            }
        });

        popupRoot.getViewTreeObserver().addOnWindowFocusChangeListener(
                new ViewTreeObserver.OnWindowFocusChangeListener() {
                    @Override
                    public void onWindowFocusChanged(boolean hasFocus) {
                        if (hasFocus && popupShown && !clipboardProcessedForCurrentPopup) {
                            tryProcessClipboardImage(false);
                        }
                    }
                }
        );

        popupParams = new WindowManager.LayoutParams();
        popupParams.type = WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY;
        popupParams.format = PixelFormat.TRANSLUCENT;
        popupParams.gravity = Gravity.TOP | Gravity.RIGHT;
        popupParams.width = dpToPx(320f);
        popupParams.height = WindowManager.LayoutParams.WRAP_CONTENT;
        popupParams.x = dpToPx(20f);
        popupParams.y = dpToPx(46f); // Just below the 41px status bar so it never overlaps BentoBar
        popupParams.flags = WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
                | WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL
                | WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED;
        popupParams.setTitle("TopBarDrop PNG Popup");

        try {
            windowManager.addView(popupRoot, popupParams);
            popupShown = true;
        } catch (Exception e) {
            popupShown = false;
        }
    }

    /**
     * Reads ClipboardManager and converts any image (WebP, JPEG, PNG, GIF, BMP, data URI) to PNG.
     */
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

            headerTitleView.setText("✓ Saved PNG: " + pngFileName);
            subtitleView.setText(bitmap.getWidth() + " × " + bitmap.getHeight() + " px · Downloaded to Downloads");
            previewImageView.setImageBitmap(bitmap);
            dragHintView.setText("⬇ Click & Drag this PNG into any app");

            lastActionMessage = "Saved " + pngFileName + " (" + bitmap.getWidth() + "×" + bitmap.getHeight() + ") to Downloads";
            notifyActivityStatus();
        } catch (Exception e) {
            showPopupError("Failed to save PNG", e.getMessage());
        }
    }

    private void showPopupError(String title, String detail) {
        clipboardProcessedForCurrentPopup = true;
        if (headerTitleView != null) headerTitleView.setText("⚠ " + title);
        if (subtitleView != null) subtitleView.setText(detail != null ? detail : "");
        if (dragHintView != null) dragHintView.setText("Right-click or swipe to dismiss");
        lastActionMessage = title + ": " + detail;
        notifyActivityStatus();
    }

    /**
     * Enables immediate click-and-drag (without needing a long-press) on the PNG preview box:
     * pressing the primary button/finger and moving > 6px starts global drag-and-drop of the saved PNG.
     */
    private void setupImmediateClickAndDragOnPreview(final View targetView) {
        targetView.setOnTouchListener(new View.OnTouchListener() {
            private float downX;
            private float downY;
            private boolean dragStarted;

            @Override
            public boolean onTouch(View v, MotionEvent event) {
                if ((event.getButtonState() & MotionEvent.BUTTON_SECONDARY) != 0) {
                    dismissPopup();
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
                            if (Math.hypot(dx, dy) > dpToPx(6f)) {
                                dragStarted = true;
                                startPngDrag(v);
                            }
                        }
                        return true;
                }
                return false;
            }
        });
    }

    private void startPngDrag(View v) {
        if (currentPngUri == null) return;
        try {
            ClipData clipData = ClipData.newUri(getContentResolver(), currentPngFileName, currentPngUri);
            View shadowTarget = (previewImageView != null && previewImageView.getDrawable() != null)
                    ? previewImageView
                    : v;
            View.DragShadowBuilder shadow = new View.DragShadowBuilder(shadowTarget);
            int flags = View.DRAG_FLAG_GLOBAL | View.DRAG_FLAG_GLOBAL_URI_READ;
            if (popupRoot != null) {
                popupRoot.setAlpha(0.35f);
            }
            v.startDragAndDrop(clipData, shadow, null, flags);
        } catch (Exception e) {
            Toast.makeText(this, "Drag error: " + e.getMessage(), Toast.LENGTH_SHORT).show();
        }
    }

    /**
     * Horizontal swipe-to-dismiss listener for the notification card header/footer.
     */
    private class SwipeDismissTouchListener implements View.OnTouchListener {
        private float startRawX;
        private int initialParamX;

        @Override
        public boolean onTouch(View v, MotionEvent event) {
            if ((event.getButtonState() & MotionEvent.BUTTON_SECONDARY) != 0) {
                dismissPopup();
                return true;
            }
            switch (event.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    startRawX = event.getRawX();
                    initialParamX = (popupParams != null) ? popupParams.x : dpToPx(20f);
                    return true;

                case MotionEvent.ACTION_MOVE:
                    if (popupShown && popupParams != null && popupRoot != null) {
                        float deltaX = event.getRawX() - startRawX;
                        // Gravity.RIGHT: moving right decreases x; moving left increases x
                        popupParams.x = Math.round(initialParamX - deltaX);
                        float fade = Math.max(0.15f, 1f - (Math.abs(deltaX) / dpToPx(180f)));
                        popupRoot.setAlpha(fade);
                        try {
                            windowManager.updateViewLayout(popupRoot, popupParams);
                        } catch (Exception ignored) {
                        }
                    }
                    return true;

                case MotionEvent.ACTION_UP:
                case MotionEvent.ACTION_CANCEL:
                    float totalDeltaX = event.getRawX() - startRawX;
                    if (Math.abs(totalDeltaX) > dpToPx(64f)) {
                        dismissPopup();
                    } else if (popupShown && popupParams != null && popupRoot != null) {
                        popupParams.x = initialParamX;
                        popupRoot.setAlpha(1.0f);
                        try {
                            windowManager.updateViewLayout(popupRoot, popupParams);
                        } catch (Exception ignored) {
                        }
                    }
                    return true;
            }
            return false;
        }
    }

    /**
     * Root view that intercepts Right-Click (BUTTON_SECONDARY) and Escape key anywhere in the popup
     * to immediately dismiss the notification window.
     */
    private class PopupRootView extends FrameLayout {
        public PopupRootView(AccessibilityService context) {
            super(context);
        }

        @Override
        public boolean dispatchGenericMotionEvent(MotionEvent ev) {
            if (ev != null) {
                if ((ev.getButtonState() & MotionEvent.BUTTON_SECONDARY) != 0
                        || (ev.getActionMasked() == MotionEvent.ACTION_BUTTON_PRESS
                            && ev.getActionButton() == MotionEvent.BUTTON_SECONDARY)) {
                    dismissPopup();
                    return true;
                }
            }
            return super.dispatchGenericMotionEvent(ev);
        }

        @Override
        public boolean dispatchTouchEvent(MotionEvent ev) {
            if (ev != null && (ev.getButtonState() & MotionEvent.BUTTON_SECONDARY) != 0) {
                dismissPopup();
                return true;
            }
            return super.dispatchTouchEvent(ev);
        }

        @Override
        public boolean dispatchKeyEvent(KeyEvent event) {
            if (event != null && event.getKeyCode() == KeyEvent.KEYCODE_ESCAPE && event.getAction() == KeyEvent.ACTION_UP) {
                dismissPopup();
                return true;
            }
            return super.dispatchKeyEvent(event);
        }
    }

    public void dismissPopup() {
        if (popupShown && popupRoot != null && windowManager != null) {
            try {
                windowManager.removeViewImmediate(popupRoot);
            } catch (Exception ignored) {
            }
        }
        popupShown = false;
        popupRoot = null;
    }

    private static String extractBaseName(String fileName) {
        if (fileName == null || fileName.isEmpty()) return null;
        int dot = fileName.lastIndexOf('.');
        String base = (dot > 0) ? fileName.substring(0, dot) : fileName;
        base = base.replaceAll("[^a-zA-Z0-9._-]", "_");
        return base.isEmpty() ? null : base;
    }

    private static String queryDisplayName(ContentResolver resolver, Uri uri) {
        try (Cursor cursor = resolver.query(uri, new String[]{OpenableColumns.DISPLAY_NAME}, null, null, null)) {
            if (cursor != null && cursor.moveToFirst()) {
                int idx = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME);
                if (idx >= 0) {
                    return cursor.getString(idx);
                }
            }
        } catch (Exception ignored) {
        }
        return uri.getLastPathSegment();
    }

    private void notifyActivityStatus() {
        if (MainActivity.current != null) {
            MainActivity.current.runOnUiThread(new Runnable() {
                @Override
                public void run() {
                    MainActivity.current.refreshUiState();
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

    @Override
    public boolean onUnbind(Intent intent) {
        dismissPopup();
        instance = null;
        return super.onUnbind(intent);
    }

    @Override
    public void onDestroy() {
        dismissPopup();
        instance = null;
        super.onDestroy();
    }
}
