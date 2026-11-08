package com.example.topbardrop;

import android.content.ClipData;
import android.content.ClipDescription;
import android.content.ClipboardManager;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Color;
import android.net.Uri;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * 100% Local On-Device Clipboard History Manager for BookTools.
 * Stores up to 30 recent clipboard items (Text snippets, HEX Colors, and PNG Images)
 * strictly inside the app's private local storage (context.getFilesDir()).
 * Zero network calls or external transmissions are ever made.
 */
public class ClipboardHistoryManager {

    public static final int MAX_HISTORY_ITEMS = 30;

    public static final String TYPE_TEXT = "TEXT";
    public static final String TYPE_COLOR = "COLOR";
    public static final String TYPE_IMAGE = "IMAGE";

    private static final String HISTORY_DIR_NAME = "clipboard_history";
    private static final String THUMBS_DIR_NAME = "thumbs";
    private static final String HISTORY_JSON_NAME = "history.json";

    private static final Pattern HEX_COLOR_PATTERN =
            Pattern.compile("^#([0-9A-Fa-f]{6}|[0-9A-Fa-f]{8})$");

    public static class HistoryItem {
        public String id;
        public String type; // TYPE_TEXT, TYPE_COLOR, TYPE_IMAGE
        public String textContent; // Full text or #RRGGBB hex
        public String title; // Display title or filename
        public String subtitle; // Dimensions, RGB info, or char count
        public String imageUriString; // MediaStore/local URI for dragging PNG
        public String thumbFileName; // Local thumbnail filename in getFilesDir()
        public int width;
        public int height;
        public long timestampMs;

        public String getFormattedTime() {
            long diffSec = Math.max(0L, (System.currentTimeMillis() - timestampMs) / 1000L);
            if (diffSec < 10L) return "Just now";
            if (diffSec < 60L) return diffSec + "s ago";
            long diffMin = diffSec / 60L;
            if (diffMin < 60L) return diffMin + "m ago";
            long diffHr = diffMin / 60L;
            if (diffHr < 24L) return diffHr + "h ago";
            return new SimpleDateFormat("MMM d, h:mm a", Locale.US).format(new Date(timestampMs));
        }
    }

    private static final Object LOCK = new Object();
    private static List<HistoryItem> cachedItems = null;
    private static volatile long suppressNextClipUntilMs = 0L;

    public static void markInternalClipboardWrite() {
        suppressNextClipUntilMs = System.currentTimeMillis() + 650L;
    }

    public static boolean isInternalClipboardWriteSuppressed() {
        return System.currentTimeMillis() < suppressNextClipUntilMs;
    }

    public static List<HistoryItem> getItems(Context context) {
        synchronized (LOCK) {
            ensureLoadedLocked(context);
            return new ArrayList<>(cachedItems);
        }
    }

    public static int getItemCount(Context context) {
        synchronized (LOCK) {
            ensureLoadedLocked(context);
            return cachedItems.size();
        }
    }

    /**
     * Records a plain text or HEX color string into local clipboard history.
     */
    public static void addTextOrColorItem(Context context, String rawText) {
        if (context == null || rawText == null) return;
        String trimmed = rawText.trim();
        if (trimmed.isEmpty()) return;
        // Skip data:image base64 strings from being stored as giant text blobs
        if (trimmed.startsWith("data:image/")) return;

        // Cap individual text entries at 64 KB to keep local JSON fast and lightweight
        String storedText = rawText.length() > 65536 ? rawText.substring(0, 65536) : rawText;

        boolean isColor = HEX_COLOR_PATTERN.matcher(trimmed).matches();
        String type = isColor ? TYPE_COLOR : TYPE_TEXT;
        String title;
        String subtitle;

        if (isColor) {
            title = trimmed.toUpperCase(Locale.US);
            try {
                int parsedColor = Color.parseColor(trimmed);
                int r = Color.red(parsedColor);
                int g = Color.green(parsedColor);
                int b = Color.blue(parsedColor);
                subtitle = "RGB(" + r + ", " + g + ", " + b + ") · Local Color";
                storedText = title;
            } catch (Exception e) {
                subtitle = "HEX Color";
            }
        } else {
            String singleLine = storedText.replace('\n', ' ').replace('\r', ' ').trim();
            title = singleLine.length() > 80 ? singleLine.substring(0, 80) + "…" : singleLine;
            int lines = 1;
            for (int i = 0; i < storedText.length(); i++) {
                if (storedText.charAt(i) == '\n') lines++;
            }
            subtitle = storedText.length() + (storedText.length() == 1 ? " char" : " chars")
                    + (lines > 1 ? " · " + lines + " lines" : "");
        }

        synchronized (LOCK) {
            ensureLoadedLocked(context);

            // Deduplicate if the same text/color already exists: move to top and update timestamp
            for (int i = 0; i < cachedItems.size(); i++) {
                HistoryItem existing = cachedItems.get(i);
                if ((TYPE_TEXT.equals(existing.type) || TYPE_COLOR.equals(existing.type))
                        && storedText.equals(existing.textContent)) {
                    cachedItems.remove(i);
                    existing.timestampMs = System.currentTimeMillis();
                    cachedItems.add(0, existing);
                    saveLocked(context);
                    return;
                }
            }

            HistoryItem item = new HistoryItem();
            item.id = UUID.randomUUID().toString();
            item.type = type;
            item.textContent = storedText;
            item.title = title;
            item.subtitle = subtitle;
            item.timestampMs = System.currentTimeMillis();

            cachedItems.add(0, item);
            trimToMaxAndCleanThumbsLocked(context);
            saveLocked(context);
        }
    }

    /**
     * Records a converted/copied PNG image into local clipboard history with a local thumbnail.
     */
    public static void addImageItem(
            Context context,
            Uri imageUri,
            String fileName,
            Bitmap sourceBitmap,
            int fullWidth,
            int fullHeight
    ) {
        if (context == null || imageUri == null) return;
        String uriStr = imageUri.toString();

        synchronized (LOCK) {
            ensureLoadedLocked(context);

            // If this exact image URI is already in history, bump it to the top
            for (int i = 0; i < cachedItems.size(); i++) {
                HistoryItem existing = cachedItems.get(i);
                if (TYPE_IMAGE.equals(existing.type) && uriStr.equals(existing.imageUriString)) {
                    cachedItems.remove(i);
                    existing.timestampMs = System.currentTimeMillis();
                    cachedItems.add(0, existing);
                    saveLocked(context);
                    return;
                }
            }

            String itemId = UUID.randomUUID().toString();
            String thumbName = null;
            if (sourceBitmap != null && !sourceBitmap.isRecycled()) {
                thumbName = saveThumbnailLocked(context, itemId, sourceBitmap);
            }

            HistoryItem item = new HistoryItem();
            item.id = itemId;
            item.type = TYPE_IMAGE;
            item.title = (fileName != null && !fileName.isEmpty()) ? fileName : "clipboard_image.png";
            item.subtitle = fullWidth + " × " + fullHeight + " px · Local PNG";
            item.imageUriString = uriStr;
            item.thumbFileName = thumbName;
            item.width = fullWidth;
            item.height = fullHeight;
            item.timestampMs = System.currentTimeMillis();

            cachedItems.add(0, item);
            trimToMaxAndCleanThumbsLocked(context);
            saveLocked(context);
        }
    }

    public static void removeItem(Context context, String itemId) {
        if (context == null || itemId == null) return;
        synchronized (LOCK) {
            ensureLoadedLocked(context);
            for (int i = 0; i < cachedItems.size(); i++) {
                HistoryItem item = cachedItems.get(i);
                if (itemId.equals(item.id)) {
                    deleteThumbnailFileLocked(context, item.thumbFileName);
                    cachedItems.remove(i);
                    saveLocked(context);
                    return;
                }
            }
        }
    }

    public static void clearAll(Context context) {
        if (context == null) return;
        synchronized (LOCK) {
            ensureLoadedLocked(context);
            for (HistoryItem item : cachedItems) {
                deleteThumbnailFileLocked(context, item.thumbFileName);
            }
            cachedItems.clear();
            saveLocked(context);
        }
    }

    public static Bitmap loadThumbnailBitmap(Context context, HistoryItem item) {
        if (context == null || item == null || item.thumbFileName == null) return null;
        File thumbFile = new File(getThumbsDir(context), item.thumbFileName);
        if (!thumbFile.exists()) return null;
        try {
            return BitmapFactory.decodeFile(thumbFile.getAbsolutePath());
        } catch (Exception ignored) {
            return null;
        }
    }

    public static boolean copyItemToSystemClipboard(Context context, HistoryItem item) {
        if (context == null || item == null) return false;
        ClipboardManager cm = context.getSystemService(ClipboardManager.class);
        if (cm == null) {
            cm = (ClipboardManager) context.getSystemService(Context.CLIPBOARD_SERVICE);
        }
        if (cm == null) return false;

        try {
            markInternalClipboardWrite();
            if (TYPE_IMAGE.equals(item.type) && item.imageUriString != null) {
                Uri uri = Uri.parse(item.imageUriString);
                ClipDescription desc = new ClipDescription(
                        item.title != null ? item.title : "image.png",
                        new String[]{"image/png", "text/uri-list"}
                );
                ClipData clip = new ClipData(desc, new ClipData.Item(uri));
                cm.setPrimaryClip(clip);
            } else if (item.textContent != null) {
                ClipData clip = ClipData.newPlainText(
                        TYPE_COLOR.equals(item.type) ? "BookTools HEX Color" : "BookTools Clipboard",
                        item.textContent
                );
                cm.setPrimaryClip(clip);
            } else {
                return false;
            }

            // Bump item to top of history
            synchronized (LOCK) {
                ensureLoadedLocked(context);
                for (int i = 0; i < cachedItems.size(); i++) {
                    if (cachedItems.get(i).id.equals(item.id)) {
                        HistoryItem bumped = cachedItems.remove(i);
                        bumped.timestampMs = System.currentTimeMillis();
                        cachedItems.add(0, bumped);
                        saveLocked(context);
                        break;
                    }
                }
            }
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    private static String saveThumbnailLocked(Context context, String itemId, Bitmap source) {
        try {
            int maxDim = 160;
            int w = source.getWidth();
            int h = source.getHeight();
            if (w <= 0 || h <= 0) return null;
            float ratio = Math.min(1f, Math.min((float) maxDim / w, (float) maxDim / h));
            int tw = Math.max(1, Math.round(w * ratio));
            int th = Math.max(1, Math.round(h * ratio));
            Bitmap thumb = (tw == w && th == h) ? source : Bitmap.createScaledBitmap(source, tw, th, true);

            String fileName = "thumb_" + itemId + ".png";
            File outFile = new File(getThumbsDir(context), fileName);
            try (FileOutputStream fos = new FileOutputStream(outFile)) {
                thumb.compress(Bitmap.CompressFormat.PNG, 90, fos);
            }
            if (thumb != source && !thumb.isRecycled()) {
                thumb.recycle();
            }
            return fileName;
        } catch (Exception e) {
            return null;
        }
    }

    private static void deleteThumbnailFileLocked(Context context, String thumbFileName) {
        if (thumbFileName == null || thumbFileName.isEmpty()) return;
        try {
            File f = new File(getThumbsDir(context), thumbFileName);
            if (f.exists()) {
                f.delete();
            }
        } catch (Exception ignored) {
        }
    }

    private static void trimToMaxAndCleanThumbsLocked(Context context) {
        while (cachedItems.size() > MAX_HISTORY_ITEMS) {
            HistoryItem removed = cachedItems.remove(cachedItems.size() - 1);
            deleteThumbnailFileLocked(context, removed.thumbFileName);
        }
    }

    private static File getHistoryDir(Context context) {
        File dir = new File(context.getFilesDir(), HISTORY_DIR_NAME);
        if (!dir.exists()) {
            dir.mkdirs();
        }
        return dir;
    }

    private static File getThumbsDir(Context context) {
        File dir = new File(getHistoryDir(context), THUMBS_DIR_NAME);
        if (!dir.exists()) {
            dir.mkdirs();
        }
        return dir;
    }

    private static void ensureLoadedLocked(Context context) {
        if (cachedItems != null) return;
        cachedItems = new ArrayList<>();
        File jsonFile = new File(getHistoryDir(context), HISTORY_JSON_NAME);
        if (!jsonFile.exists()) return;

        try (FileInputStream fis = new FileInputStream(jsonFile);
             ByteArrayOutputStream bos = new ByteArrayOutputStream()) {
            byte[] buf = new byte[4096];
            int n;
            while ((n = fis.read(buf)) != -1) {
                bos.write(buf, 0, n);
            }
            String jsonStr = new String(bos.toByteArray(), StandardCharsets.UTF_8);
            JSONArray arr = new JSONArray(jsonStr);
            for (int i = 0; i < arr.length() && cachedItems.size() < MAX_HISTORY_ITEMS; i++) {
                JSONObject obj = arr.optJSONObject(i);
                if (obj == null) continue;
                HistoryItem item = new HistoryItem();
                item.id = obj.optString("id", UUID.randomUUID().toString());
                item.type = obj.optString("type", TYPE_TEXT);
                item.textContent = obj.optString("textContent", null);
                item.title = obj.optString("title", "");
                item.subtitle = obj.optString("subtitle", "");
                item.imageUriString = obj.optString("imageUriString", null);
                item.thumbFileName = obj.optString("thumbFileName", null);
                item.width = obj.optInt("width", 0);
                item.height = obj.optInt("height", 0);
                item.timestampMs = obj.optLong("timestampMs", System.currentTimeMillis());
                cachedItems.add(item);
            }
        } catch (Exception ignored) {
        }
    }

    private static void saveLocked(Context context) {
        if (cachedItems == null) return;
        try {
            JSONArray arr = new JSONArray();
            for (HistoryItem item : cachedItems) {
                JSONObject obj = new JSONObject();
                obj.put("id", item.id);
                obj.put("type", item.type);
                if (item.textContent != null) obj.put("textContent", item.textContent);
                if (item.title != null) obj.put("title", item.title);
                if (item.subtitle != null) obj.put("subtitle", item.subtitle);
                if (item.imageUriString != null) obj.put("imageUriString", item.imageUriString);
                if (item.thumbFileName != null) obj.put("thumbFileName", item.thumbFileName);
                obj.put("width", item.width);
                obj.put("height", item.height);
                obj.put("timestampMs", item.timestampMs);
                arr.put(obj);
            }
            File jsonFile = new File(getHistoryDir(context), HISTORY_JSON_NAME);
            byte[] bytes = arr.toString().getBytes(StandardCharsets.UTF_8);
            try (FileOutputStream fos = new FileOutputStream(jsonFile, false)) {
                fos.write(bytes);
                fos.flush();
            }
        } catch (Exception ignored) {
        }
    }
}
