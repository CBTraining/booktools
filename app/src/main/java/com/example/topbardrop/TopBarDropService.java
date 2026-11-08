package com.example.topbardrop;

import android.accessibilityservice.AccessibilityService;
import android.content.ClipData;
import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.Intent;
import android.database.Cursor;
import android.graphics.Color;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.provider.MediaStore;
import android.provider.OpenableColumns;
import android.util.DisplayMetrics;
import android.util.TypedValue;
import android.view.DragAndDropPermissions;
import android.view.DragEvent;
import android.view.Gravity;
import android.view.View;
import android.view.WindowInsets;
import android.view.WindowManager;
import android.view.WindowMetrics;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;
import android.view.accessibility.AccessibilityWindowInfo;
import android.webkit.MimeTypeMap;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

/**
 * Places the TopBarDrop icon directly inside the Googlebook OS top status bar (y = 0)
 * using the exact same mechanism as BentoBar:
 * - TYPE_ACCESSIBILITY_OVERLAY (the only window type allowed on top of the status bar).
 * - Scans SystemUI's status bar window for `DesktopStatusBarSpacer` so the icon sits
 *   at the right end of the status bar's free space (just to the left of the system/Wi-Fi/battery icons).
 * - Expands when any drag starts (DragEvent.ACTION_DRAG_STARTED) and saves dropped files
 *   to Downloads as "<original_name>_dl.<ext>".
 */
public class TopBarDropService extends AccessibilityService {

    public static volatile TopBarDropService instance;
    public static volatile String lastActionMessage = "Waiting for file drag...";

    private WindowManager windowManager;
    private LinearLayout overlayContainer;
    private LinearLayout innerPill;
    private TextView labelView;
    private WindowManager.LayoutParams layoutParams;
    private final Handler handler = new Handler(Looper.getMainLooper());

    private boolean isAdded = false;
    private boolean isDragging = false;
    private int currentBarHeight = 41;
    private int currentRightOffsetPx = 240;

    private final Runnable periodicScan = new Runnable() {
        @Override
        public void run() {
            if (!isDragging) {
                scanStatusBarAndPlace();
            }
            handler.postDelayed(this, 3000);
        }
    };

    @Override
    protected void onServiceConnected() {
        super.onServiceConnected();
        instance = this;
        setupOverlayWindow();
        scanStatusBarAndPlace();
        handler.postDelayed(periodicScan, 2000);
        if (MainActivity.current != null) {
            MainActivity.current.runOnUiThread(new Runnable() {
                @Override
                public void run() {
                    MainActivity.current.refreshUiState();
                }
            });
        }
    }

    @Override
    public void onAccessibilityEvent(AccessibilityEvent event) {
        if (event != null && event.getEventType() == AccessibilityEvent.TYPE_WINDOWS_CHANGED) {
            if (!isDragging) {
                handler.postDelayed(new Runnable() {
                    @Override
                    public void run() {
                        scanStatusBarAndPlace();
                    }
                }, 150);
            }
        }
    }

    @Override
    public void onInterrupt() {
    }

    private void setupOverlayWindow() {
        windowManager = getSystemService(WindowManager.class);
        if (windowManager == null) {
            windowManager = (WindowManager) getSystemService(WINDOW_SERVICE);
        }

        overlayContainer = new LinearLayout(this);
        overlayContainer.setOrientation(LinearLayout.HORIZONTAL);
        overlayContainer.setGravity(Gravity.CENTER);

        innerPill = new LinearLayout(this);
        innerPill.setOrientation(LinearLayout.HORIZONTAL);
        innerPill.setGravity(Gravity.CENTER);

        GradientDrawable pillBg = new GradientDrawable();
        pillBg.setShape(GradientDrawable.RECTANGLE);
        pillBg.setCornerRadius(dpToPx(12f));
        pillBg.setColor(Color.parseColor("#CC1A73E8"));
        pillBg.setStroke(dpToPx(1f), Color.parseColor("#99FFFFFF"));
        innerPill.setBackground(pillBg);

        int padH = dpToPx(8f);
        int padV = dpToPx(2f);
        innerPill.setPadding(padH, padV, padH, padV);

        labelView = new TextView(this);
        labelView.setText("⬇ dl");
        labelView.setTextColor(Color.WHITE);
        labelView.setTypeface(Typeface.create("sans-serif-medium", Typeface.BOLD));
        labelView.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f);
        labelView.setGravity(Gravity.CENTER);
        innerPill.addView(labelView);

        overlayContainer.addView(innerPill, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.MATCH_PARENT
        ));

        layoutParams = new WindowManager.LayoutParams();
        layoutParams.type = WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY;
        layoutParams.format = PixelFormat.TRANSLUCENT;
        layoutParams.gravity = Gravity.TOP | Gravity.RIGHT;
        layoutParams.flags = WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
                | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS
                | WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                | WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            layoutParams.layoutInDisplayCutoutMode =
                    WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS;
        }
        layoutParams.setTitle("TopBarDrop");

        currentBarHeight = dpToPx(36f);
        currentRightOffsetPx = dpToPx(220f);
        layoutParams.width = dpToPx(54f);
        layoutParams.height = currentBarHeight;
        layoutParams.x = currentRightOffsetPx;
        layoutParams.y = 0;

        int padVContainer = dpToPx(4f);
        overlayContainer.setPadding(0, padVContainer, 0, padVContainer);

        View.OnDragListener dragListener = new View.OnDragListener() {
            @Override
            public boolean onDrag(View v, DragEvent event) {
                return handleDragEvent(event);
            }
        };
        overlayContainer.setOnDragListener(dragListener);
        innerPill.setOnDragListener(dragListener);

        overlayContainer.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                Intent open = new Intent(TopBarDropService.this, MainActivity.class);
                open.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_SINGLE_TOP);
                startActivity(open);
            }
        });

        try {
            windowManager.addView(overlayContainer, layoutParams);
            isAdded = true;
        } catch (Exception e) {
            isAdded = false;
        }
    }

    /**
     * Scans SystemUI's status bar window on Googlebook OS (Android 17) to find
     * DesktopStatusBarSpacer and places our icon right at its right edge
     * (immediately to the left of the IME/Bell/Wi-Fi/Battery icons).
     */
    private void scanStatusBarAndPlace() {
        if (!isAdded || windowManager == null) return;

        int screenW = getScreenWidth();
        int maxBarH = getStatusBarMaxHeight();

        List<AccessibilityWindowInfo> windows;
        try {
            windows = getWindows();
        } catch (Exception e) {
            windows = Collections.emptyList();
        }

        AccessibilityWindowInfo barWindow = null;
        Rect barRect = new Rect();
        for (AccessibilityWindowInfo w : windows) {
            Rect r = new Rect();
            w.getBoundsInScreen(r);
            if (w.getType() == AccessibilityWindowInfo.TYPE_SYSTEM
                    && r.top == 0
                    && r.height() >= 1
                    && r.height() <= maxBarH
                    && r.width() >= screenW / 2) {
                barWindow = w;
                barRect.set(r);
                break;
            }
        }

        if (barWindow != null) {
            currentBarHeight = barRect.height();
            Rect freeSpace = findDesktopSpacerOrGap(barWindow, barRect);
            if (freeSpace != null && freeSpace.width() > dpToPx(60f)) {
                int gap = dpToPx(8f);
                currentRightOffsetPx = Math.max(dpToPx(140f), (screenW - freeSpace.right) + gap);
            }
        }

        if (!isDragging) {
            applyCollapsedLayout();
        }
    }

    private Rect findDesktopSpacerOrGap(AccessibilityWindowInfo barWindow, Rect barRect) {
        AccessibilityNodeInfo root = null;
        try {
            root = barWindow.getRoot();
        } catch (Exception ignored) {
        }
        if (root == null) return null;

        Rect spacer = null;
        List<Rect> parts = new ArrayList<>();
        ArrayDeque<AccessibilityNodeInfo> stack = new ArrayDeque<>();
        stack.add(root);
        int visited = 0;

        while (!stack.isEmpty() && visited < 400) {
            AccessibilityNodeInfo n = stack.removeLast();
            visited++;
            Rect r = new Rect();
            n.getBoundsInScreen(r);
            CharSequence viewId = n.getViewIdResourceName();
            String id = (viewId != null) ? viewId.toString() : "";
            if (id.endsWith("DesktopStatusBarSpacer")) {
                spacer = new Rect(r);
            }
            boolean labelled = n.isClickable()
                    || (n.getContentDescription() != null && n.getContentDescription().length() > 0)
                    || (n.getText() != null && n.getText().length() > 0);
            if (labelled && r.width() >= 1 && r.width() < (barRect.width() * 6 / 10) && r.height() > 0) {
                parts.add(new Rect(r));
            }
            for (int i = n.getChildCount() - 1; i >= 0; i--) {
                AccessibilityNodeInfo child = n.getChild(i);
                if (child != null) stack.add(child);
            }
        }

        if (spacer != null && spacer.width() > 0) {
            return spacer;
        }
        return widestGap(barRect, parts);
    }

    private Rect widestGap(Rect bar, List<Rect> parts) {
        Collections.sort(parts, new Comparator<Rect>() {
            @Override
            public int compare(Rect a, Rect b) {
                return Integer.compare(a.left, b.left);
            }
        });
        Rect best = new Rect(bar.left, bar.top, bar.left, bar.bottom);
        int cursor = bar.left;
        for (Rect p : parts) {
            if (p.left > cursor && (p.left - cursor) > best.width()) {
                best.set(cursor, bar.top, p.left, bar.bottom);
            }
            cursor = Math.max(cursor, p.right);
        }
        if ((bar.right - cursor) > best.width()) {
            best.set(cursor, bar.top, bar.right, bar.bottom);
        }
        return best;
    }

    private boolean handleDragEvent(DragEvent event) {
        int action = event.getAction();
        switch (action) {
            case DragEvent.ACTION_DRAG_STARTED:
                isDragging = true;
                applyExpandedLayout(false);
                return true;

            case DragEvent.ACTION_DRAG_ENTERED:
                isDragging = true;
                applyExpandedLayout(true);
                return true;

            case DragEvent.ACTION_DRAG_EXITED:
                if (isDragging) {
                    applyExpandedLayout(false);
                }
                return true;

            case DragEvent.ACTION_DROP:
                isDragging = false;
                processDroppedEvent(event);
                applyCollapsedLayout();
                return true;

            case DragEvent.ACTION_DRAG_ENDED:
                isDragging = false;
                applyCollapsedLayout();
                return true;

            default:
                return true;
        }
    }

    private void applyExpandedLayout(boolean hovering) {
        if (!isAdded) return;
        labelView.setText(hovering
                ? "⬇  Release to save (*_dl)"
                : "⬇  Drop file/image here (*_dl)");
        labelView.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f);

        GradientDrawable pillBg = (GradientDrawable) innerPill.getBackground();
        pillBg.setColor(hovering ? Color.parseColor("#EE1E8E3E") : Color.parseColor("#EE1967D2"));
        pillBg.setCornerRadius(dpToPx(18f));
        pillBg.setStroke(dpToPx(2f), Color.WHITE);

        overlayContainer.setPadding(dpToPx(4f), dpToPx(4f), dpToPx(4f), dpToPx(4f));
        layoutParams.width = dpToPx(260f);
        layoutParams.height = dpToPx(84f);
        layoutParams.x = Math.max(dpToPx(80f), currentRightOffsetPx - dpToPx(40f));
        layoutParams.y = 0;

        try {
            windowManager.updateViewLayout(overlayContainer, layoutParams);
        } catch (Exception ignored) {
        }
    }

    private void applyCollapsedLayout() {
        if (!isAdded) return;
        labelView.setText("⬇ dl");
        labelView.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f);

        GradientDrawable pillBg = (GradientDrawable) innerPill.getBackground();
        pillBg.setColor(Color.parseColor("#CC1A73E8"));
        pillBg.setCornerRadius(dpToPx(12f));
        pillBg.setStroke(dpToPx(1f), Color.parseColor("#99FFFFFF"));

        int vPad = Math.max(2, (currentBarHeight - dpToPx(24f)) / 2);
        overlayContainer.setPadding(0, vPad, 0, vPad);
        layoutParams.width = dpToPx(54f);
        layoutParams.height = currentBarHeight;
        layoutParams.x = currentRightOffsetPx;
        layoutParams.y = 0;

        try {
            windowManager.updateViewLayout(overlayContainer, layoutParams);
        } catch (Exception ignored) {
        }
    }

    private void processDroppedEvent(DragEvent event) {
        DragAndDropPermissions perms = acquireDragPermissions(event);
        try {
            ClipData clipData = event.getClipData();
            if (clipData != null && clipData.getItemCount() > 0) {
                ClipData.Item item = clipData.getItemAt(0);
                Uri uri = item.getUri();
                if (uri != null) {
                    saveDroppedFileWithDlSuffix(uri);
                } else if (item.getText() != null) {
                    saveDroppedTextWithDlSuffix(item.getText().toString());
                }
            }
        } finally {
            if (perms != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                try {
                    perms.release();
                } catch (Exception ignored) {
                }
            }
        }
    }

    private DragAndDropPermissions acquireDragPermissions(DragEvent event) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            if (MainActivity.current != null) {
                try {
                    DragAndDropPermissions p = MainActivity.current.requestDragAndDropPermissions(event);
                    if (p != null) return p;
                } catch (Throwable ignored) {
                }
            }
            try {
                java.lang.reflect.Method obtain = DragAndDropPermissions.class.getDeclaredMethod("obtain", DragEvent.class);
                obtain.setAccessible(true);
                Object ddp = obtain.invoke(null, event);
                if (ddp != null) {
                    for (java.lang.reflect.Method m : ddp.getClass().getDeclaredMethods()) {
                        if (m.getName().equals("takeTransient")) {
                            m.setAccessible(true);
                            m.invoke(ddp);
                            return (DragAndDropPermissions) ddp;
                        }
                    }
                }
            } catch (Throwable ignored) {
            }
        }
        return null;
    }

    private void saveDroppedFileWithDlSuffix(Uri sourceUri) {
        try {
            ContentResolver resolver = getContentResolver();
            String mimeType = resolver.getType(sourceUri);
            String originalName = queryDisplayName(resolver, sourceUri);
            if (originalName == null || originalName.isEmpty()) {
                originalName = "dropped_file";
            }

            if (mimeType == null || mimeType.isEmpty()) {
                int dot = originalName.lastIndexOf('.');
                if (dot > 0 && dot < originalName.length() - 1) {
                    String ext = originalName.substring(dot + 1).toLowerCase();
                    mimeType = MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext);
                }
                if (mimeType == null || mimeType.isEmpty()) {
                    mimeType = "application/octet-stream";
                }
            }

            String newFileName = buildDlFileName(originalName, mimeType);
            writeStreamToDownloads(resolver.openInputStream(sourceUri), newFileName, mimeType);

            lastActionMessage = "Saved " + newFileName + " (" + mimeType + ") to Downloads";
            Toast.makeText(this, "✓ " + lastActionMessage, Toast.LENGTH_LONG).show();
            notifyActivityStatus();
        } catch (Exception e) {
            lastActionMessage = "Error saving file: " + e.getMessage();
            Toast.makeText(this, "⚠ " + lastActionMessage, Toast.LENGTH_LONG).show();
            notifyActivityStatus();
        }
    }

    private void saveDroppedTextWithDlSuffix(String content) {
        try {
            String newFileName = "dropped_text_dl.txt";
            byte[] bytes = content.getBytes("UTF-8");
            writeStreamToDownloads(new ByteArrayInputStream(bytes), newFileName, "text/plain");
            lastActionMessage = "Saved " + newFileName + " (text/plain) to Downloads";
            Toast.makeText(this, "✓ " + lastActionMessage, Toast.LENGTH_LONG).show();
            notifyActivityStatus();
        } catch (Exception e) {
            Toast.makeText(this, "Error saving text: " + e.getMessage(), Toast.LENGTH_LONG).show();
        }
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

    private void writeStreamToDownloads(InputStream input, String fileName, String mimeType) throws Exception {
        if (input == null) {
            throw new IllegalArgumentException("Cannot read dropped file stream");
        }
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                ContentValues values = new ContentValues();
                values.put(MediaStore.Downloads.DISPLAY_NAME, fileName);
                values.put(MediaStore.Downloads.MIME_TYPE, mimeType);
                values.put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS);

                Uri destUri = getContentResolver().insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values);
                if (destUri == null) {
                    throw new IllegalStateException("Failed to create Downloads entry");
                }
                try (OutputStream output = getContentResolver().openOutputStream(destUri)) {
                    copyStream(input, output);
                }
            } else {
                File downloadsDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS);
                if (!downloadsDir.exists()) {
                    downloadsDir.mkdirs();
                }
                File outFile = new File(downloadsDir, fileName);
                try (OutputStream output = new FileOutputStream(outFile)) {
                    copyStream(input, output);
                }
            }
        } finally {
            input.close();
        }
    }

    private static void copyStream(InputStream in, OutputStream out) throws Exception {
        byte[] buffer = new byte[8192];
        int read;
        while ((read = in.read(buffer)) != -1) {
            out.write(buffer, 0, read);
        }
        out.flush();
    }

    private static String buildDlFileName(String originalName, String mimeType) {
        int dotIndex = originalName.lastIndexOf('.');
        if (dotIndex > 0) {
            String base = originalName.substring(0, dotIndex);
            String ext = originalName.substring(dotIndex);
            return base + "_dl" + ext;
        }
        String ext = MimeTypeMap.getSingleton().getExtensionFromMimeType(mimeType);
        if (ext != null && !ext.isEmpty()) {
            return originalName + "_dl." + ext;
        }
        return originalName + "_dl";
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

    private int getScreenWidth() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && windowManager != null) {
            WindowMetrics wm = windowManager.getCurrentWindowMetrics();
            return wm.getBounds().width();
        }
        DisplayMetrics dm = getResources().getDisplayMetrics();
        return dm.widthPixels;
    }

    private int getStatusBarMaxHeight() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && windowManager != null) {
            WindowMetrics wm = windowManager.getCurrentWindowMetrics();
            int inset = wm.getWindowInsets().getInsetsIgnoringVisibility(WindowInsets.Type.statusBars()).top;
            if (inset > 0) return inset + 4;
        }
        return dpToPx(48f) + 4;
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
        cleanupOverlay();
        return super.onUnbind(intent);
    }

    @Override
    public void onDestroy() {
        cleanupOverlay();
        super.onDestroy();
    }

    private void cleanupOverlay() {
        instance = null;
        handler.removeCallbacksAndMessages(null);
        if (isAdded && overlayContainer != null && windowManager != null) {
            try {
                windowManager.removeViewImmediate(overlayContainer);
            } catch (Exception ignored) {
            }
            isAdded = false;
        }
    }
}
