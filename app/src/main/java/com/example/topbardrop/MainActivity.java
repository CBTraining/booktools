package com.example.topbardrop;

import android.Manifest;
import android.app.Activity;
import android.content.ClipData;
import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.database.Cursor;
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
import android.provider.OpenableColumns;
import android.util.TypedValue;
import android.view.DragAndDropPermissions;
import android.view.DragEvent;
import android.view.Gravity;
import android.view.View;
import android.view.WindowManager;
import android.webkit.MimeTypeMap;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;

/**
 * Permission-safe TopBarDrop Activity:
 * - Uses zero restricted permissions (no SYSTEM_ALERT_WINDOW).
 * - Docks near the top-right on Android Desktop / Freeform window mode.
 * - Starts TopBarDropService to place an icon in the top status bar.
 * - Expands its drop zone automatically when any file/image drag starts
 *   (DragEvent.ACTION_DRAG_STARTED) and uses Activity.requestDragAndDropPermissions
 *   to safely read files dragged from any external app (Files, Chrome, Gallery),
 *   saving them immediately into Downloads as "<original_name>_dl.<ext>".
 */
public class MainActivity extends Activity {

    private LinearLayout rootLayout;
    private LinearLayout dropZoneCard;
    private TextView dropIconText;
    private TextView dropZoneTitle;
    private TextView dropZoneSubtitle;
    private TextView lastSavedLog;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        // Position window near the top-right on desktop/freeform environments
        try {
            WindowManager.LayoutParams wlp = getWindow().getAttributes();
            wlp.gravity = Gravity.TOP | Gravity.END;
            getWindow().setAttributes(wlp);
        } catch (Exception ignored) {
        }

        requestNotificationPermissionIfNeeded();
        startTopBarStatusService();

        buildMaterialUi();
        handleIncomingShareIntent(getIntent());
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        handleIncomingShareIntent(intent);
    }

    private void buildMaterialUi() {
        rootLayout = new LinearLayout(this);
        rootLayout.setOrientation(LinearLayout.VERTICAL);
        rootLayout.setBackgroundColor(Color.parseColor("#F8F9FA"));
        int pad = dpToPx(14f);
        rootLayout.setPadding(pad, pad, pad, pad);

        // Top Bar Compact Drop Target (expands when a file is dragged)
        dropZoneCard = new LinearLayout(this);
        dropZoneCard.setOrientation(LinearLayout.VERTICAL);
        dropZoneCard.setGravity(Gravity.CENTER);
        int zonePad = dpToPx(14f);
        dropZoneCard.setPadding(zonePad, zonePad, zonePad, zonePad);

        GradientDrawable zoneBg = new GradientDrawable();
        zoneBg.setShape(GradientDrawable.RECTANGLE);
        zoneBg.setCornerRadius(dpToPx(18f));
        zoneBg.setColor(Color.parseColor("#1A73E8")); // Material Primary Blue
        zoneBg.setStroke(dpToPx(2f), Color.parseColor("#8AB4F8"));
        dropZoneCard.setBackground(zoneBg);

        dropIconText = new TextView(this);
        dropIconText.setText("⬇  TopBarDrop (*_dl)");
        dropIconText.setTextColor(Color.WHITE);
        dropIconText.setTypeface(Typeface.DEFAULT_BOLD);
        dropIconText.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f);
        dropIconText.setGravity(Gravity.CENTER);
        dropZoneCard.addView(dropIconText);

        dropZoneTitle = new TextView(this);
        dropZoneTitle.setText("Drag any file or image here");
        dropZoneTitle.setTextColor(Color.parseColor("#E8F0FE"));
        dropZoneTitle.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f);
        dropZoneTitle.setGravity(Gravity.CENTER);
        dropZoneTitle.setPadding(0, dpToPx(4f), 0, 0);
        dropZoneCard.addView(dropZoneTitle);

        dropZoneSubtitle = new TextView(this);
        dropZoneSubtitle.setText("Release to auto-detect file type & download as <name>_dl.<ext>");
        dropZoneSubtitle.setTextColor(Color.WHITE);
        dropZoneSubtitle.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f);
        dropZoneSubtitle.setGravity(Gravity.CENTER);
        dropZoneSubtitle.setPadding(0, dpToPx(6f), 0, 0);
        dropZoneSubtitle.setVisibility(View.GONE); // Expands into view during drag!
        dropZoneCard.addView(dropZoneSubtitle);

        LinearLayout.LayoutParams zoneLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
        );
        rootLayout.addView(dropZoneCard, zoneLp);

        // Status log showing the most recently downloaded *_dl file
        lastSavedLog = new TextView(this);
        lastSavedLog.setText("✓ Safe Mode active (no restricted permissions). Top bar icon running.");
        lastSavedLog.setTextColor(Color.parseColor("#1E8E3E"));
        lastSavedLog.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f);
        lastSavedLog.setPadding(dpToPx(4f), dpToPx(10f), dpToPx(4f), dpToPx(10f));
        rootLayout.addView(lastSavedLog);

        // Built-in test drag source so the user can test inside or across windows immediately
        TextView sampleDragPill = new TextView(this);
        sampleDragPill.setText("🖼  Test: Long-press & drag me into the blue box above (sample_photo.png)");
        sampleDragPill.setTextColor(Color.parseColor("#174EA6"));
        sampleDragPill.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f);
        sampleDragPill.setGravity(Gravity.CENTER);
        int pillPad = dpToPx(10f);
        sampleDragPill.setPadding(pillPad, pillPad, pillPad, pillPad);

        GradientDrawable pillBg = new GradientDrawable();
        pillBg.setCornerRadius(dpToPx(12f));
        pillBg.setColor(Color.parseColor("#E8F0FE"));
        pillBg.setStroke(dpToPx(1f), Color.parseColor("#1A73E8"));
        sampleDragPill.setBackground(pillBg);

        sampleDragPill.setOnLongClickListener(new View.OnLongClickListener() {
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
        rootLayout.addView(sampleDragPill);

        // Listen for drag events on the entire window so dragging anywhere near it expands the target
        View.OnDragListener dragListener = new View.OnDragListener() {
            @Override
            public boolean onDrag(View v, DragEvent event) {
                return handleDragEvent(event);
            }
        };
        rootLayout.setOnDragListener(dragListener);
        dropZoneCard.setOnDragListener(dragListener);

        setContentView(rootLayout);
    }

    private boolean handleDragEvent(DragEvent event) {
        switch (event.getAction()) {
            case DragEvent.ACTION_DRAG_STARTED:
                setDropZoneExpanded(true, false);
                return true;

            case DragEvent.ACTION_DRAG_ENTERED:
                setDropZoneExpanded(true, true);
                return true;

            case DragEvent.ACTION_DRAG_EXITED:
                setDropZoneExpanded(true, false);
                return true;

            case DragEvent.ACTION_DROP:
                DragAndDropPermissions perms = null;
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                    try {
                        perms = requestDragAndDropPermissions(event);
                    } catch (Exception ignored) {
                    }
                }
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
                        perms.release();
                    }
                }
                setDropZoneExpanded(false, false);
                return true;

            case DragEvent.ACTION_DRAG_ENDED:
                setDropZoneExpanded(false, false);
                return true;

            default:
                return true;
        }
    }

    private void setDropZoneExpanded(boolean expanded, boolean hovering) {
        GradientDrawable bg = (GradientDrawable) dropZoneCard.getBackground();
        if (expanded) {
            dropZoneSubtitle.setVisibility(View.VISIBLE);
            int padV = dpToPx(26f);
            int padH = dpToPx(18f);
            dropZoneCard.setPadding(padH, padV, padH, padV);
            if (hovering) {
                bg.setColor(Color.parseColor("#1E8E3E")); // Material Green when hovering
                dropIconText.setText("⬇  Release to Download (*_dl)");
                dropZoneTitle.setText("File detected — release mouse/finger now!");
            } else {
                bg.setColor(Color.parseColor("#185ABC")); // Deeper Material Blue when expanded
                dropIconText.setText("⬇  Drop File Here (*_dl)");
                dropZoneTitle.setText("Drop Zone Expanded");
            }
        } else {
            dropZoneSubtitle.setVisibility(View.GONE);
            int pad = dpToPx(14f);
            dropZoneCard.setPadding(pad, pad, pad, pad);
            bg.setColor(Color.parseColor("#1A73E8"));
            dropIconText.setText("⬇  TopBarDrop (*_dl)");
            dropZoneTitle.setText("Drag any file or image here");
        }
    }

    private void handleIncomingShareIntent(Intent intent) {
        if (intent == null) return;
        if (Intent.ACTION_SEND.equals(intent.getAction())) {
            Uri sharedUri = intent.getParcelableExtra(Intent.EXTRA_STREAM);
            if (sharedUri != null) {
                saveDroppedFileWithDlSuffix(sharedUri);
            }
        }
    }

    private void saveDroppedFileWithDlSuffix(Uri sourceUri) {
        try {
            ContentResolver resolver = getContentResolver();
            String mimeType = resolver.getType(sourceUri);
            String originalName = queryDisplayName(resolver, sourceUri);
            if (originalName == null || originalName.isEmpty()) {
                originalName = "file";
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

            String summary = "Saved to Downloads: " + newFileName + " (" + mimeType + ")";
            lastSavedLog.setText("✓ " + summary);
            Toast.makeText(this, summary, Toast.LENGTH_LONG).show();
        } catch (Exception e) {
            String err = "Drop failed: " + e.getMessage();
            lastSavedLog.setText("⚠ " + err);
            Toast.makeText(this, err, Toast.LENGTH_LONG).show();
        }
    }

    private void saveDroppedTextWithDlSuffix(String content) {
        try {
            String newFileName = "dropped_text_dl.txt";
            byte[] bytes = content.getBytes("UTF-8");
            writeStreamToDownloads(new ByteArrayInputStream(bytes), newFileName, "text/plain");
            String summary = "Saved to Downloads: " + newFileName + " (text/plain)";
            lastSavedLog.setText("✓ " + summary);
            Toast.makeText(this, summary, Toast.LENGTH_LONG).show();
        } catch (Exception e) {
            Toast.makeText(this, "Error saving text: " + e.getMessage(), Toast.LENGTH_LONG).show();
        }
    }

    private void writeStreamToDownloads(InputStream input, String fileName, String mimeType) throws Exception {
        if (input == null) {
            throw new IllegalArgumentException("Cannot open dropped file stream");
        }
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                ContentValues values = new ContentValues();
                values.put(MediaStore.Downloads.DISPLAY_NAME, fileName);
                values.put(MediaStore.Downloads.MIME_TYPE, mimeType);
                values.put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS);

                Uri destUri = getContentResolver().insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values);
                if (destUri == null) {
                    throw new IllegalStateException("Could not create file in Downloads");
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

    private void requestNotificationPermissionIfNeeded() {
        if (Build.VERSION.SDK_INT >= 33) {
            if (checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, 101);
            }
        }
    }

    private void startTopBarStatusService() {
        try {
            Intent serviceIntent = new Intent(this, TopBarDropService.class);
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                startForegroundService(serviceIntent);
            } else {
                startService(serviceIntent);
            }
        } catch (Exception ignored) {
        }
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
