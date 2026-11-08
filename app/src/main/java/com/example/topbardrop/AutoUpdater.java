package com.example.topbardrop;

import android.app.DownloadManager;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageInfo;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.util.Base64;
import android.widget.Toast;

import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.lang.ref.WeakReference;
import java.net.HttpURLConnection;
import java.net.URL;

import javax.net.ssl.HttpsURLConnection;

/**
 * Play-Protect-friendly GitHub update detector & downloader for BookTools:
 *  - Does NOT require REQUEST_INSTALL_PACKAGES (which triggers Play Protect's "harmful dropper"
 *    heuristic when combined with an AccessibilityService).
 *  - Instead, uses Android's built-in system DownloadManager to download BookTools.apk into
 *    Downloads and opens the system package installer once complete.
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
    public static volatile String latestApkDownloadUrl = RAW_APK_DEFAULT_URL;

    private static volatile boolean checkInProgress = false;
    private static volatile long lastCheckTimeMs = 0L;
    private static volatile long activeDownloadId = -1L;

    public interface StatusCallback {
        void onStatusChanged(String status, boolean hasUpdate);
    }

    public static String getInstalledVersionName(Context context) {
        try {
            PackageInfo pi = context.getPackageManager().getPackageInfo(context.getPackageName(), 0);
            return pi.versionName != null ? pi.versionName : "1.004";
        } catch (Exception e) {
            return "1.004";
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
            return 1004;
        }
    }

    public static void checkForUpdates(
            Context context,
            final boolean manualTrigger,
            final boolean autoDownloadIfFound,
            StatusCallback callback
    ) {
        final Context appContext = context.getApplicationContext();
        final WeakReference<StatusCallback> callbackRef = new WeakReference<>(callback);

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
            updateStatus(mainHandler, callbackRef, "Checking GitHub for updates...", updateAvailable);
        }

        new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    int localCode = getInstalledVersionCode(appContext);
                    String localName = getInstalledVersionName(appContext);

                    String commitSha = fetchLatestCommitSha();
                    JSONObject versionJson = fetchVersionJson(commitSha);
                    if (versionJson == null) {
                        checkInProgress = false;
                        updateStatus(mainHandler, callbackRef, "v" + localName + " (Up to date)", false);
                        return;
                    }

                    final String remoteVer = versionJson.optString("version", localName);
                    final int remoteCode = versionJson.optInt("versionCode", localCode);
                    String rawApkUrl;
                    if (commitSha != null && commitSha.matches("^[0-9a-fA-F]{7,40}$")) {
                        rawApkUrl = "https://raw.githubusercontent.com/CBTraining/booktools/"
                                + commitSha + "/BookTools.apk";
                    } else {
                        rawApkUrl = RAW_APK_DEFAULT_URL + "?t=" + System.currentTimeMillis();
                    }

                    latestRemoteVersion = remoteVer;
                    latestRemoteVersionCode = remoteCode;
                    latestApkDownloadUrl = rawApkUrl;
                    checkInProgress = false;

                    if (remoteCode > localCode) {
                        updateAvailable = true;
                        updateStatus(
                                mainHandler,
                                callbackRef,
                                "Update available: v" + remoteVer,
                                true
                        );
                        if (autoDownloadIfFound && manualTrigger) {
                            mainHandler.post(new Runnable() {
                                @Override
                                public void run() {
                                    downloadUpdateViaSystemManager(appContext, callbackRef.get());
                                }
                            });
                        }
                    } else {
                        updateAvailable = false;
                        updateStatus(
                                mainHandler,
                                callbackRef,
                                "v" + localName + " is up to date",
                                false
                        );
                    }
                } catch (Exception e) {
                    checkInProgress = false;
                    updateStatus(
                            mainHandler,
                            callbackRef,
                            "Update check offline (" + getInstalledVersionName(appContext) + ")",
                            updateAvailable
                    );
                }
            }
        }).start();
    }

    /**
     * Uses Android's trusted system DownloadManager to download BookTools.apk into Downloads
     * and launches the system installer notification without needing REQUEST_INSTALL_PACKAGES.
     */
    public static void downloadUpdateViaSystemManager(Context context, final StatusCallback callback) {
        final Context appContext = context.getApplicationContext();
        String ver = latestRemoteVersion != null ? latestRemoteVersion : "latest";
        String url = latestApkDownloadUrl != null ? latestApkDownloadUrl : RAW_APK_DEFAULT_URL;

        try {
            final DownloadManager dm = (DownloadManager) appContext.getSystemService(Context.DOWNLOAD_SERVICE);
            if (dm == null) {
                Intent browserIntent = new Intent(Intent.ACTION_VIEW, Uri.parse(url));
                browserIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                appContext.startActivity(browserIntent);
                return;
            }

            String fileName = "BookTools_v" + ver + ".apk";
            DownloadManager.Request req = new DownloadManager.Request(Uri.parse(url));
            req.setTitle("BookTools v" + ver + " Update");
            req.setDescription("Downloading update from GitHub...");
            req.setMimeType("application/vnd.android.package-archive");
            req.setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED);
            req.setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, fileName);

            activeDownloadId = dm.enqueue(req);
            String msg = "Downloading BookTools v" + ver + " to Downloads...";
            updateStatusText = msg;
            if (callback != null) {
                callback.onStatusChanged(msg, true);
            }
            Toast.makeText(appContext, msg + " Tap the download notification when ready.", Toast.LENGTH_LONG).show();

            BroadcastReceiver onComplete = new BroadcastReceiver() {
                @Override
                public void onReceive(Context c, Intent intent) {
                    long id = intent.getLongExtra(DownloadManager.EXTRA_DOWNLOAD_ID, -1L);
                    if (id == activeDownloadId) {
                        try {
                            appContext.unregisterReceiver(this);
                        } catch (Exception ignored) {
                        }
                        String doneMsg = "Downloaded v" + ver + " to Downloads (tap notification or Files to install)";
                        updateStatusText = doneMsg;
                        if (callback != null) {
                            callback.onStatusChanged(doneMsg, true);
                        }
                        Toast.makeText(appContext, doneMsg, Toast.LENGTH_LONG).show();
                    }
                }
            };

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                appContext.registerReceiver(
                        onComplete,
                        new IntentFilter(DownloadManager.ACTION_DOWNLOAD_COMPLETE),
                        Context.RECEIVER_EXPORTED
                );
            } else {
                appContext.registerReceiver(
                        onComplete,
                        new IntentFilter(DownloadManager.ACTION_DOWNLOAD_COMPLETE)
                );
            }
        } catch (Exception e) {
            try {
                Intent browserIntent = new Intent(Intent.ACTION_VIEW, Uri.parse(url));
                browserIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                appContext.startActivity(browserIntent);
            } catch (Exception ignored) {
            }
        }
    }

    private static String fetchLatestCommitSha() {
        HttpsURLConnection conn = null;
        try {
            conn = openSecureGitHubConnection(
                    GITHUB_API_COMMIT_URL + "?t=" + System.currentTimeMillis(),
                    "application/vnd.github.v3+json"
            );
            if (conn.getResponseCode() == 200) {
                String body = readStreamToString(conn.getInputStream());
                JSONObject obj = new JSONObject(body);
                return obj.optString("sha", null);
            }
        } catch (Exception ignored) {
        } finally {
            if (conn != null) conn.disconnect();
        }
        return null;
    }

    private static JSONObject fetchVersionJson(String commitSha) {
        HttpsURLConnection conn = null;
        try {
            conn = openSecureGitHubConnection(
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
        } finally {
            if (conn != null) conn.disconnect();
        }

        HttpsURLConnection rawConn = null;
        try {
            String url = (commitSha != null && commitSha.matches("^[0-9a-fA-F]{7,40}$"))
                    ? "https://raw.githubusercontent.com/CBTraining/booktools/" + commitSha + "/version.json"
                    : RAW_VERSION_URL + "?t=" + System.currentTimeMillis();
            rawConn = openSecureGitHubConnection(url, "application/json");
            if (rawConn.getResponseCode() == 200) {
                String body = readStreamToString(rawConn.getInputStream());
                return new JSONObject(body);
            }
        } catch (Exception ignored) {
        } finally {
            if (rawConn != null) rawConn.disconnect();
        }
        return null;
    }

    private static boolean isAllowedGitHubHost(String host) {
        if (host == null) return false;
        String h = host.toLowerCase();
        return h.equals("api.github.com")
                || h.equals("raw.githubusercontent.com")
                || h.equals("github.com")
                || h.endsWith(".githubusercontent.com");
    }

    private static HttpsURLConnection openSecureGitHubConnection(String urlStr, String accept) throws Exception {
        String currentUrl = urlStr;
        for (int redirects = 0; redirects < 5; redirects++) {
            URL url = new URL(currentUrl);
            if (!"https".equalsIgnoreCase(url.getProtocol()) || !isAllowedGitHubHost(url.getHost())) {
                throw new SecurityException("Blocked non-GitHub or non-HTTPS URL: " + currentUrl);
            }
            HttpsURLConnection conn = (HttpsURLConnection) url.openConnection();
            conn.setInstanceFollowRedirects(false);
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
            final WeakReference<StatusCallback> callbackRef,
            final String text,
            final boolean hasUpdate
    ) {
        updateStatusText = text;
        updateAvailable = hasUpdate;
        mainHandler.post(new Runnable() {
            @Override
            public void run() {
                StatusCallback cb = callbackRef.get();
                if (cb != null) {
                    cb.onStatusChanged(text, hasUpdate);
                }
            }
        });
    }
}
