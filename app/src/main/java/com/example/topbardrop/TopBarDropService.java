package com.example.topbardrop;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.ClipData;
import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.Intent;
import android.database.Cursor;
import android.graphics.Color;
import android.graphics.PixelFormat;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.os.IBinder;
import android.provider.MediaStore;
import android.provider.OpenableColumns;
import android.util.TypedValue;
import android.view.DragEvent;
import android.view.Gravity;
import android.view.View;
import android.view.WindowManager;
import android.webkit.MimeTypeMap;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;

public class TopBarDropService extends Service {

    private WindowManager windowManager;
    private LinearLayout overlayContainer;
    private TextView labelView;
    private WindowManager.LayoutParams layoutParams;

    @Override
    public void onCreate() {
        super.onCreate();
        startAsForeground();
        setupTopBarOverlay();
    }

    private void startAsForeground() {
        String channelId = "topbar_drop_channel";
        NotificationManager nm = getSystemService(NotificationManager.class);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && nm != null) {
            NotificationChannel channel = new NotificationChannel(
                    channelId,
                    "Top Bar Drop Zone",
                    NotificationManager.IMPORTANCE_LOW
            );
            nm.createNotificationChannel(channel);
        }

        Notification.Builder builder = (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
                ? new Notification.Builder(this, channelId)
                : new Notification.Builder(this);

        Notification notification = builder
                .setContentTitle("TopBarDrop Active")
                .setContentText("Drag any file/image to the top icon to save as *_dl")
                .setSmallIcon(android.R.drawable.stat_sys_download_done)
                .build();

        startForeground(1001, notification);
    }

    private void setupTopBarOverlay() {
        windowManager = (WindowManager) getSystemService(WINDOW_SERVICE);

        overlayContainer = new LinearLayout(this);
        overlayContainer.setOrientation(LinearLayout.HORIZONTAL);
        overlayContainer.setGravity(Gravity.CENTER);

        GradientDrawable bg = new GradientDrawable();
        bg.setShape(GradientDrawable.RECTANGLE);
        bg.setCornerRadius(dpToPx(16f));
        bg.setColor(Color.parseColor("#1A73E8")); // Google Material Blue
        bg.setStroke(dpToPx(1f), Color.parseColor("#80FFFFFF"));
        overlayContainer.setBackground(bg);

        int padH = dpToPx(8f);
        int padV = dpToPx(2f);
        overlayContainer.setPadding(padH, padV, padH, padV);

        labelView = new TextView(this);
        labelView.setText("⬇");
        labelView.setTextColor(Color.WHITE);
        labelView.setTypeface(Typeface.DEFAULT_BOLD);
        labelView.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f);
        labelView.setGravity(Gravity.CENTER);
        overlayContainer.addView(labelView);

        int overlayType = (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
                ? WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
                : WindowManager.LayoutParams.TYPE_PHONE;

        layoutParams = new WindowManager.LayoutParams(
                dpToPx(34f),
                dpToPx(24f),
                overlayType,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                        | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
                        | WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
                PixelFormat.TRANSLUCENT
        );

        // Place at the top bar, offset to the left of the battery/Wi-Fi icons
        layoutParams.gravity = Gravity.TOP | Gravity.END;
        layoutParams.x = dpToPx(100f);
        layoutParams.y = dpToPx(2f);

        overlayContainer.setOnDragListener(new View.OnDragListener() {
            @Override
            public boolean onDrag(View v, DragEvent event) {
                return handleDragEvent(event);
            }
        });

        overlayContainer.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                Toast.makeText(
                        TopBarDropService.this,
                        "TopBarDrop ready: drag any file or image here to save as *_dl",
                        Toast.LENGTH_SHORT
                ).show();
            }
        });

        windowManager.addView(overlayContainer, layoutParams);
    }

    private boolean handleDragEvent(DragEvent event) {
        int action = event.getAction();
        switch (action) {
            case DragEvent.ACTION_DRAG_STARTED:
                expandOverlay(false);
                return true;

            case DragEvent.ACTION_DRAG_ENTERED:
                expandOverlay(true);
                return true;

            case DragEvent.ACTION_DRAG_EXITED:
                expandOverlay(false);
                return true;

            case DragEvent.ACTION_DROP:
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
                collapseOverlay();
                return true;

            case DragEvent.ACTION_DRAG_ENDED:
                collapseOverlay();
                return true;

            default:
                return true;
        }
    }

    private void expandOverlay(boolean isHovering) {
        labelView.setText(isHovering
                ? "⬇ Release to download (*_dl)"
                : "⬇ Drop file/image here (*_dl)");
        GradientDrawable bg = (GradientDrawable) overlayContainer.getBackground();
        bg.setColor(isHovering ? Color.parseColor("#1E8E3E") : Color.parseColor("#1967D2"));
        bg.setCornerRadius(dpToPx(20f));
        layoutParams.width = dpToPx(240f);
        layoutParams.height = dpToPx(64f);
        windowManager.updateViewLayout(overlayContainer, layoutParams);
    }

    private void collapseOverlay() {
        labelView.setText("⬇");
        GradientDrawable bg = (GradientDrawable) overlayContainer.getBackground();
        bg.setColor(Color.parseColor("#1A73E8"));
        bg.setCornerRadius(dpToPx(16f));
        layoutParams.width = dpToPx(34f);
        layoutParams.height = dpToPx(24f);
        windowManager.updateViewLayout(overlayContainer, layoutParams);
    }

    private void saveDroppedFileWithDlSuffix(Uri sourceUri) {
        try {
            ContentResolver resolver = getContentResolver();
            String mimeType = resolver.getType(sourceUri);
            if (mimeType == null || mimeType.isEmpty()) {
                mimeType = "application/octet-stream";
            }

            String originalName = queryDisplayName(resolver, sourceUri);
            if (originalName == null || originalName.isEmpty()) {
                originalName = "dropped_file";
            }

            String newFileName = buildDlFileName(originalName, mimeType);
            writeStreamToDownloads(resolver.openInputStream(sourceUri), newFileName, mimeType);

            Toast.makeText(
                    this,
                    "Detected " + mimeType + "\nSaved: " + newFileName,
                    Toast.LENGTH_LONG
            ).show();
        } catch (Exception e) {
            Toast.makeText(
                    this,
                    "Error saving dropped file: " + e.getMessage(),
                    Toast.LENGTH_LONG
            ).show();
        }
    }

    private void saveDroppedTextWithDlSuffix(String content) {
        try {
            String newFileName = "dropped_note_dl.txt";
            byte[] bytes = content.getBytes("UTF-8");
            writeStreamToDownloads(new java.io.ByteArrayInputStream(bytes), newFileName, "text/plain");
            Toast.makeText(
                    this,
                    "Detected text/plain\nSaved: " + newFileName,
                    Toast.LENGTH_LONG
            ).show();
        } catch (Exception e) {
            Toast.makeText(this, "Error saving text: " + e.getMessage(), Toast.LENGTH_LONG).show();
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
                    throw new IllegalStateException("Failed to create MediaStore Downloads entry");
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

    private int dpToPx(float dp) {
        return Math.round(TypedValue.applyDimension(
                TypedValue.COMPLEX_UNIT_DIP,
                dp,
                getResources().getDisplayMetrics()
        ));
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        if (overlayContainer != null && windowManager != null) {
            windowManager.removeView(overlayContainer);
        }
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }
}
