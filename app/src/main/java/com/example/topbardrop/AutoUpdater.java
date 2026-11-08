package com.example.topbardrop;

import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageInfo;
import android.content.pm.PackageInstaller;
import android.net.Uri;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.util.Base64;
import android.widget.Toast;

import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;

/**
 * Checks https://github.com/CBTraining/booktools for a newer version.json,
 * downloads the updated BookTools.apk automatically, and triggers Android's
 * PackageInstaller session to self-update in place.
 */
public class AutoUpdater {

    private static final String GITHUB_API_VERSION_URL =
            "https://api.github.com/repos/CBTraining/booktools/contents/version.json?ref=main";
    private static final String GITHUB_API_COMMIT_URL =
            "https://api.github.com/repos/CBTraining/booktools/commits/main";
    private static final String RAW_VERSION_URL =
            "https://raw.githubusercontent.com/CBTraining/booktools/main/version.json";
    private static final String RAW_APK_DEFAULT_URL =
            "https://raw.githubusercontent.com/CBTraining/booktools/main/BookTools.apk";

    public static volatile String updateStatusText = "Up to date";
    public static volatile boolean updateAvailable = false;
    public static volatile String latestRemoteVersion = null;
    public static volatile int latestRemoteVersionCode = -1;
    public static volatile File pendingInstallApkFile = null;

    private static volatile boolean checkInProgress = false;
    private static volatile long lastCheckTimeMs = 0L;

    public interface StatusCallback {
        void onStatusChanged(String status, boolean hasUpdate);
    }

    public static String getInstalledVersionName(Context context) {
        try {
            PackageInfo pi = context.getPackageManager().getPackageInfo(context.getPackageName(), 0);
            return pi.versionName != null ? pi.versionName : "1.000";
        } catch (Exception e) {
            return "1.000";
        }
    }

    public static int getInstalledVersionCode(Context context) {
        try {
            PackageInfo pi = context.getPackageManager().getPackageInfo(context.getPackageName(), 0);
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                return (int) pi.getLongVersionCode();
            }
            return pi.versionCode;
        } catch (Exception e) {
            return 1000;
        }
    }

    public static void checkForUpdates(
            final Context context,
            final boolean manualTrigger,
            final boolean autoInstallIfFound,
            final StatusCallback callback
    ) {
        long now = System.currentTimeMillis();
        if (checkInProgress) {
            return;
        }
        if (!manualTrigger && (now - lastCheckTimeMs < 20_000L)) {
            if (callback != null) {
                callback.onStatusChanged(updateStatusText, updateAvailable);
            }
            return;
        }

        checkInProgress = true;
        lastCheckTimeMs = now;
        final Handler mainHandler = new Handler(Looper.getMainLooper());

        if (manualTrigger) {
            updateStatus(mainHandler, callback, "Checking GitHub for updates...", updateAvailable);
        }

        new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    int localCode = getInstalledVersionCode(context);
                    String localName = getInstalledVersionName(context);

                    // 1. Fetch latest commit SHA so raw.githubusercontent URLs bypass CDN cache
                    String commitSha = fetchLatestCommitSha();

                    // 2. Fetch version.json (via GitHub Contents API first, then raw URL with SHA)
                    JSONObject versionJson = fetchVersionJson(commitSha);
                    if (versionJson == null) {
                        checkInProgress = false;
                        updateStatus(mainHandler, callback, "v" + localName + " (Up to date)", false);
                        return;
                    }

                    final String remoteVer = versionJson.optString("version", localName);
                    final int remoteCode = versionJson.optInt("versionCode", localCode);
                    String rawApkUrl = versionJson.optString("apkUrl", RAW_APK_DEFAULT_URL);

                    // If we have the exact commit SHA, rewrite the raw URL to use the immutable commit SHA
                    // so Fastly CDN never serves a stale cached APK!
                    if (commitSha != null && !commitSha.isEmpty()) {
                        rawApkUrl = "https://raw.githubusercontent.com/CBTraining/booktools/"
                                + commitSha + "/BookTools.apk";
                    } else {
                        rawApkUrl = rawApkUrl + "?t=" + System.currentTimeMillis();
                    }
                    final String finalApkUrl = rawApkUrl;

                    latestRemoteVersion = remoteVer;
                    latestRemoteVersionCode = remoteCode;

                    if (remoteCode > localCode) {
                        updateAvailable = true;
                        updateStatus(
                                mainHandler,
                                callback,
                                "Update found: v" + remoteVer + " — Downloading...",
                                true
                        );
                        if (autoInstallIfFound) {
                            downloadAndInstallApk(context, finalApkUrl, remoteVer, mainHandler, callback);
                        } else {
                            checkInProgress = false;
                        }
                    } else {
                        updateAvailable = false;
                        checkInProgress = false;
                        updateStatus(
                                mainHandler,
                                callback,
                                "v" + localName + " is up to date",
                                false
                        );
                    }
                } catch (Exception e) {
                    checkInProgress = false;
                    updateStatus(
                            mainHandler,
                            callback,
                            "Update check offline (" + getInstalledVersionName(context) + ")",
                            updateAvailable
                    );
                }
            }
        }).start();
    }

    private static void downloadAndInstallApk(
            final Context context,
            final String apkUrl,
            final String remoteVer,
            final Handler mainHandler,
            final StatusCallback callback
    ) {
        try {
            File outFile = new File(context.getCacheDir(), "BookTools_update.apk");
            if (outFile.exists()) {
                outFile.delete();
            }

            HttpURLConnection conn = openConnectionWithRedirects(apkUrl, "application/octet-stream");
            int totalBytes = conn.getContentLength();

            try (InputStream in = conn.getInputStream();
                 FileOutputStream out = new FileOutputStream(outFile)) {
                byte[] buf = new byte[8192];
                int read;
                int downloaded = 0;
                while ((read = in.read(buf)) != -1) {
                    out.write(buf, 0, read);
                    downloaded += read;
                    if (totalBytes > 0) {
                        int pct = Math.min(100, (int) ((downloaded * 100L) / totalBytes));
                        updateStatus(
                                mainHandler,
                                callback,
                                "Downloading v" + remoteVer + "... " + pct + "%",
                                true
                        );
                    }
                }
                out.flush();
            }

            pendingInstallApkFile = outFile;
            checkInProgress = false;

            mainHandler.post(new Runnable() {
                @Override
                public void run() {
                    installDownloadedApkIfPermitted(context, callback);
                }
            });
        } catch (Exception e) {
            checkInProgress = false;
            updateStatus(
                    mainHandler,
                    callback,
                    "Download failed: " + e.getMessage(),
                    true
            );
        }
    }

    public static void installDownloadedApkIfPermitted(Context context, StatusCallback callback) {
        File apkFile = pendingInstallApkFile;
        if (apkFile == null || !apkFile.exists()) {
            return;
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            if (!context.getPackageManager().canRequestPackageInstalls()) {
                String msg = "Allow 'Install unknown apps' once to finish updating to v"
                        + (latestRemoteVersion != null ? latestRemoteVersion : "");
                updateStatusText = msg;
                if (callback != null) {
                    callback.onStatusChanged(msg, true);
                }
                Toast.makeText(context, msg, Toast.LENGTH_LONG).show();
                try {
                    Intent intent = new Intent(
                            Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                            Uri.parse("package:" + context.getPackageName())
                    );
                    intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                    context.startActivity(intent);
                } catch (Exception ignored) {
                }
                return;
            }
        }

        try {
            PackageInstaller packageInstaller = context.getPackageManager().getPackageInstaller();
            PackageInstaller.SessionParams params =
                    new PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL);
            params.setAppPackageName(context.getPackageName());
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                params.setRequireUserAction(PackageInstaller.SessionParams.USER_ACTION_NOT_REQUIRED);
            }

            int sessionId = packageInstaller.createSession(params);
            PackageInstaller.Session session = packageInstaller.openSession(sessionId);

            try (InputStream in = new FileInputStream(apkFile);
                 OutputStream out = session.openWrite("BookTools.apk", 0, apkFile.length())) {
                byte[] buffer = new byte[8192];
                int c;
                while ((c = in.read(buffer)) != -1) {
                    out.write(buffer, 0, c);
                }
                session.fsync(out);
            }

            Intent callbackIntent = new Intent(context, UpdateInstallReceiver.class);
            int flags = PendingIntent.FLAG_UPDATE_CURRENT;
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                flags |= PendingIntent.FLAG_MUTABLE;
            }
            PendingIntent pendingIntent = PendingIntent.getBroadcast(
                    context,
                    sessionId,
                    callbackIntent,
                    flags
            );

            pendingInstallApkFile = null;
            String msg = "Installing v" + (latestRemoteVersion != null ? latestRemoteVersion : "") + "...";
            updateStatusText = msg;
            if (callback != null) {
                callback.onStatusChanged(msg, true);
            }
            session.commit(pendingIntent.getIntentSender());
            session.close();
        } catch (Exception e) {
            String err = "Install error: " + e.getMessage();
            updateStatusText = err;
            if (callback != null) {
                callback.onStatusChanged(err, true);
            }
        }
    }

    private static String fetchLatestCommitSha() {
        try {
            HttpURLConnection conn = openConnectionWithRedirects(
                    GITHUB_API_COMMIT_URL + "?t=" + System.currentTimeMillis(),
                    "application/vnd.github.v3+json"
            );
            if (conn.getResponseCode() == 200) {
                String body = readStreamToString(conn.getInputStream());
                JSONObject obj = new JSONObject(body);
                return obj.optString("sha", null);
            }
        } catch (Exception ignored) {
        }
        return null;
    }

    private static JSONObject fetchVersionJson(String commitSha) {
        // Try GitHub Contents API first (instant update, 0 CDN delay)
        try {
            HttpURLConnection conn = openConnectionWithRedirects(
                    GITHUB_API_VERSION_URL + "&t=" + System.currentTimeMillis(),
                    "application/vnd.github.v3+json"
            );
            if (conn.getResponseCode() == 200) {
                String body = readStreamToString(conn.getInputStream());
                JSONObject wrapper = new JSONObject(body);
                String encoded = wrapper.optString("content", "");
                if (!encoded.isEmpty()) {
                    byte[] decoded = Base64.decode(encoded, Base64.DEFAULT);
                    return new JSONObject(new String(decoded, "UTF-8"));
                }
            }
        } catch (Exception ignored) {
        }

        // Fallback to raw.githubusercontent.com (using commitSha if available)
        try {
            String url = (commitSha != null && !commitSha.isEmpty())
                    ? "https://raw.githubusercontent.com/CBTraining/booktools/" + commitSha + "/version.json"
                    : RAW_VERSION_URL + "?t=" + System.currentTimeMillis();
            HttpURLConnection conn = openConnectionWithRedirects(url, "application/json");
            if (conn.getResponseCode() == 200) {
                String body = readStreamToString(conn.getInputStream());
                return new JSONObject(body);
            }
        } catch (Exception ignored) {
        }
        return null;
    }

    private static HttpURLConnection openConnectionWithRedirects(String urlStr, String accept) throws Exception {
        String currentUrl = urlStr;
        for (int redirects = 0; redirects < 5; redirects++) {
            HttpURLConnection conn = (HttpURLConnection) new URL(currentUrl).openConnection();
            conn.setInstanceFollowRedirects(true);
            conn.setConnectTimeout(8000);
            conn.setReadTimeout(12000);
            conn.setUseCaches(false);
            conn.setRequestProperty("Cache-Control", "no-cache, no-store, must-revalidate");
            conn.setRequestProperty("Pragma", "no-cache");
            conn.setRequestProperty("User-Agent", "BookTools-AutoUpdater");
            if (accept != null) {
                conn.setRequestProperty("Accept", accept);
            }
            int code = conn.getResponseCode();
            if (code == HttpURLConnection.HTTP_MOVED_PERM
                    || code == HttpURLConnection.HTTP_MOVED_TEMP
                    || code == HttpURLConnection.HTTP_SEE_OTHER
                    || code == 307
                    || code == 308) {
                String loc = conn.getHeaderField("Location");
                conn.disconnect();
                if (loc != null && !loc.isEmpty()) {
                    currentUrl = loc;
                    continue;
                }
            }
            return conn;
        }
        throw new IllegalStateException("Too many redirects");
    }

    private static String readStreamToString(InputStream in) throws Exception {
        StringBuilder sb = new StringBuilder();
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(in, "UTF-8"))) {
            String line;
            while ((line = reader.readLine()) != null) {
                sb.append(line).append('\n');
            }
        }
        return sb.toString();
    }

    private static void updateStatus(
            Handler mainHandler,
            final StatusCallback callback,
            final String text,
            final boolean hasUpdate
    ) {
        updateStatusText = text;
        updateAvailable = hasUpdate;
        if (callback != null) {
            mainHandler.post(new Runnable() {
                @Override
                public void run() {
                    callback.onStatusChanged(text, hasUpdate);
                }
            });
        }
    }
}
