package com.example.topbardrop;

import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.Context;
import android.content.Intent;
import android.content.res.Configuration;
import android.database.Cursor;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.ClipDrawable;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.LayerDrawable;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.provider.MediaStore;
import android.provider.OpenableColumns;
import android.util.TypedValue;
import android.view.Gravity;
import android.widget.Button;

import java.io.BufferedOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.FilterOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.Locale;

/**
 * Shared utility methods for BookTools' offline video, GIF, and image processing tools.
 * Eliminates duplicate URI extraction, MediaStore Downloads saving, file metadata queries,
 * and borderless UI widget builders across activities and processors.
 */
public final class MediaToolUtils {

    private MediaToolUtils() {
    }

    public static final class FileMeta {
        public final String displayName;
        public final long sizeBytes;

        public FileMeta(String displayName, long sizeBytes) {
            this.displayName = displayName;
            this.sizeBytes = sizeBytes;
        }
    }

    public static final class StreamSaveResult {
        public final Uri savedUri;
        public final long bytesWritten;

        public StreamSaveResult(Uri savedUri, long bytesWritten) {
            this.savedUri = savedUri;
            this.bytesWritten = bytesWritten;
        }
    }

    public interface StreamWriter {
        void writeTo(OutputStream out) throws IOException;
    }

    public static boolean isDarkMode(Context context) {
        int nightModeFlags = context.getResources().getConfiguration().uiMode & Configuration.UI_MODE_NIGHT_MASK;
        return nightModeFlags == Configuration.UI_MODE_NIGHT_YES;
    }

    public static int dpToPx(Context context, float dp) {
        return Math.round(TypedValue.applyDimension(
                TypedValue.COMPLEX_UNIT_DIP,
                dp,
                context.getResources().getDisplayMetrics()
        ));
    }

    public static Uri extractMediaUri(Intent intent) {
        if (intent == null) return null;
        String action = intent.getAction();
        if (Intent.ACTION_VIEW.equals(action) && intent.getData() != null) {
            return intent.getData();
        }
        if (Intent.ACTION_SEND.equals(action)) {
            Uri streamUri;
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                streamUri = intent.getParcelableExtra(Intent.EXTRA_STREAM, Uri.class);
            } else {
                //noinspection deprecation
                streamUri = intent.getParcelableExtra(Intent.EXTRA_STREAM);
            }
            if (streamUri != null) {
                return streamUri;
            }
        }
        if (intent.getClipData() != null && intent.getClipData().getItemCount() > 0) {
            Uri clipUri = intent.getClipData().getItemAt(0).getUri();
            if (clipUri != null) {
                return clipUri;
            }
        }
        return intent.getData();
    }

    public static FileMeta queryFileMeta(Context context, Uri uri, String fallbackName) {
        String displayName = fallbackName;
        long sizeBytes = 0L;
        ContentResolver resolver = context.getContentResolver();

        try (Cursor cursor = resolver.query(
                uri,
                new String[]{OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE},
                null,
                null,
                null
        )) {
            if (cursor != null && cursor.moveToFirst()) {
                int nameIdx = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME);
                if (nameIdx >= 0 && !cursor.isNull(nameIdx)) {
                    String n = cursor.getString(nameIdx);
                    if (n != null && !n.trim().isEmpty()) {
                        displayName = n.trim();
                    }
                }
                int sizeIdx = cursor.getColumnIndex(OpenableColumns.SIZE);
                if (sizeIdx >= 0 && !cursor.isNull(sizeIdx)) {
                    sizeBytes = cursor.getLong(sizeIdx);
                }
            }
        } catch (Exception ignored) {
        }

        if (sizeBytes <= 0L) {
            try (InputStream in = resolver.openInputStream(uri)) {
                if (in != null) {
                    sizeBytes = in.available();
                }
            } catch (Exception ignored) {
            }
        }
        return new FileMeta(displayName, sizeBytes);
    }

    public static String stripExtension(String fileName, String defaultBase) {
        if (fileName == null || fileName.trim().isEmpty()) {
            return defaultBase;
        }
        String clean = fileName.trim().replaceAll("[^a-zA-Z0-9._-]", "_");
        int dot = clean.lastIndexOf('.');
        if (dot > 0) {
            clean = clean.substring(0, dot);
        }
        return clean.isEmpty() ? defaultBase : clean;
    }

    public static String formatBytes(long bytes) {
        if (bytes <= 0) return "0 MB";
        double mb = bytes / (1024.0 * 1024.0);
        if (mb >= 1.0) {
            return String.format(Locale.US, "%.1f MB", mb);
        }
        double kb = bytes / 1024.0;
        return String.format(Locale.US, "%.0f KB", kb);
    }

    /**
     * Streams output directly into MediaStore.Downloads (or legacy Downloads folder)
     * while counting the exact number of bytes written without extra memory buffers.
     */
    public static StreamSaveResult saveStreamToDownloads(
            Context context,
            String outFileName,
            String mimeType,
            StreamWriter writer
    ) throws IOException {
        ContentResolver resolver = context.getContentResolver();
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ContentValues values = new ContentValues();
            values.put(MediaStore.Downloads.DISPLAY_NAME, outFileName);
            values.put(MediaStore.Downloads.MIME_TYPE, mimeType);
            values.put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS);
            values.put(MediaStore.Downloads.IS_PENDING, 1);

            Uri uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values);
            if (uri == null) {
                throw new IOException("Failed to create MediaStore entry in Downloads.");
            }
            long written;
            try (OutputStream rawOut = resolver.openOutputStream(uri)) {
                if (rawOut == null) {
                    throw new IOException("Failed to open Downloads output stream.");
                }
                CountingOutputStream countingOut = new CountingOutputStream(new BufferedOutputStream(rawOut, 64 * 1024));
                writer.writeTo(countingOut);
                countingOut.flush();
                written = countingOut.getBytesWritten();
            }
            ContentValues done = new ContentValues();
            done.put(MediaStore.Downloads.IS_PENDING, 0);
            resolver.update(uri, done, null, null);
            return new StreamSaveResult(uri, written);
        } else {
            File downloadsDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS);
            if (!downloadsDir.exists()) {
                //noinspection ResultOfMethodCallIgnored
                downloadsDir.mkdirs();
            }
            File target = new File(downloadsDir, outFileName);
            long written;
            try (OutputStream rawOut = new FileOutputStream(target)) {
                CountingOutputStream countingOut = new CountingOutputStream(new BufferedOutputStream(rawOut, 64 * 1024));
                writer.writeTo(countingOut);
                countingOut.flush();
                written = countingOut.getBytesWritten();
            }
            return new StreamSaveResult(Uri.fromFile(target), written);
        }
    }

    public static Uri saveFileToDownloads(
            Context context,
            File tempFile,
            String outFileName,
            String mimeType
    ) throws IOException {
        StreamSaveResult res = saveStreamToDownloads(context, outFileName, mimeType, out -> {
            try (InputStream in = new FileInputStream(tempFile)) {
                byte[] buf = new byte[64 * 1024];
                int n;
                while ((n = in.read(buf)) != -1) {
                    out.write(buf, 0, n);
                }
            }
        });
        return res.savedUri;
    }

    public static Uri saveBytesToDownloads(
            Context context,
            byte[] data,
            String outFileName,
            String mimeType
    ) throws IOException {
        StreamSaveResult res = saveStreamToDownloads(context, outFileName, mimeType, out -> out.write(data));
        return res.savedUri;
    }

    public static Drawable buildSmoothProgressDrawable(Context context, boolean dark, int accentTeal) {
        float radius = dpToPx(context, 6f);
        GradientDrawable track = new GradientDrawable();
        track.setCornerRadius(radius);
        track.setColor(dark ? Color.parseColor("#262B36") : Color.parseColor("#E2E8F0"));

        GradientDrawable progressFill = new GradientDrawable(
                GradientDrawable.Orientation.LEFT_RIGHT,
                new int[]{
                        Color.parseColor("#A3E635"),
                        Color.parseColor("#2DD4BF"),
                        accentTeal
                }
        );
        progressFill.setCornerRadius(radius);
        ClipDrawable clip = new ClipDrawable(progressFill, Gravity.LEFT, ClipDrawable.HORIZONTAL);

        LayerDrawable layers = new LayerDrawable(new Drawable[]{track, clip});
        layers.setId(0, android.R.id.background);
        layers.setId(1, android.R.id.progress);
        return layers;
    }

    public static Button createFlatButton(Context context, boolean dark, String text, boolean primary) {
        Button btn = new Button(context);
        btn.setText(text);
        btn.setAllCaps(false);
        btn.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12.5f);
        btn.setTypeface(Typeface.DEFAULT_BOLD);
        btn.setGravity(Gravity.CENTER);
        int padH = dpToPx(context, 18f);
        btn.setPadding(padH, 0, padH, 0);
        btn.setMinHeight(0);
        btn.setMinimumHeight(0);
        btn.setMinWidth(0);
        btn.setMinimumWidth(0);
        btn.setStateListAnimator(null);
        btn.setElevation(0f);

        updateButtonAppearance(context, btn, dark, primary);
        return btn;
    }

    public static void updateButtonAppearance(Context context, Button btn, boolean dark, boolean primary) {
        GradientDrawable bg = new GradientDrawable();
        bg.setCornerRadius(dpToPx(context, 10f));
        if (primary) {
            bg.setColor(dark ? Color.parseColor("#2DD4BF") : Color.parseColor("#0D9488"));
            btn.setTextColor(dark ? Color.parseColor("#0B1312") : Color.WHITE);
        } else {
            bg.setColor(dark ? Color.parseColor("#252A34") : Color.parseColor("#E5E7EB"));
            btn.setTextColor(dark ? Color.parseColor("#E5E7EB") : Color.parseColor("#1F2937"));
        }
        btn.setBackground(bg);
    }

    private static final class CountingOutputStream extends FilterOutputStream {
        private long bytesWritten = 0L;

        CountingOutputStream(OutputStream out) {
            super(out);
        }

        @Override
        public void write(int b) throws IOException {
            out.write(b);
            bytesWritten++;
        }

        @Override
        public void write(byte[] b, int off, int len) throws IOException {
            out.write(b, off, len);
            bytesWritten += len;
        }

        long getBytesWritten() {
            return bytesWritten;
        }
    }
}
