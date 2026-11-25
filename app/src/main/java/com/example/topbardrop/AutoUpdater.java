package com.example.topbardrop;

import android.app.Activity;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageInfo;
import android.content.pm.PackageInstaller;
import android.content.pm.PackageManager;
import android.content.pm.Signature;
import android.content.pm.SigningInfo;
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
import java.lang.ref.WeakReference;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.Arrays;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import javax.net.ssl.HttpsURLConnection;

/**
 * Direct in-app GitHub auto-updater for BookTools:
 *  - Downloads the latest APK directly into private app cache (never cluttering Downloads).
 *  - Verifies package name and signing certificate match before streaming into PackageInstaller.
 *  - Streams APK into PackageInstaller off the main thread and cleans up aborted sessions.
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

    private static final ExecutorService UPDATE_EXECUTOR = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "BookTools-AutoUpdater");
        t.setDaemon(true);
        return t;
    });

    public static volatile String updateStatusText = "Up to date";
    public static volatile boolean updateAvailable = false;
    public static volatile String latestRemoteVersion = null;
    public static volatile int latestRemoteVersionCode = -1;
    public static volatile File pendingInstallApkFile = null;

    private static volatile boolean checkInProgress = false;
    private static volatile boolean installInProgress = false;
    private static volatile long lastCheckTimeMs = 0L;

    public interface StatusCallback {
        void onStatusChanged(String status, boolean hasUpdate);
    }

    public static String getInstalledVersionName(Context context) {
        try {
            PackageInfo pi = context.getPackageManager().getPackageInfo(context.getPackageName(), 0);
            return pi.versionName != null ? pi.versionName : "1.022";
        } catch (Exception e) {
            return "1.022";
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
            return 1022;
        }
    }

    public static void checkForUpdates(
            Context context,
            final boolean manualTrigger,
            final boolean autoInstallIfFound,
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

        UPDATE_EXECUTOR.execute(new Runnable() {
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

                    final String remoteVer = versionJson.optString(
                            "versionName",
                            versionJson.optString("version", localName)
                    );
                    final int remoteCode = versionJson.optInt("versionCode", localCode);
                    String rawApkUrl = versionJson.optString("apkUrl", RAW_APK_DEFAULT_URL);

                    if (commitSha != null && commitSha.matches("^[0-9a-fA-F]{7,40}$")) {
                        rawApkUrl = "https://raw.githubusercontent.com/CBTraining/booktools/"
                                + commitSha + "/BookTools.apk";
                    } else if (rawApkUrl == null || rawApkUrl.isEmpty()) {
                        rawApkUrl = RAW_APK_DEFAULT_URL + "?t=" + System.currentTimeMillis();
                    }
                    final String finalApkUrl = rawApkUrl;

                    latestRemoteVersion = remoteVer;
                    latestRemoteVersionCode = remoteCode;

                    if (remoteCode > localCode) {
                        updateAvailable = true;
                        if (autoInstallIfFound) {
                            updateStatus(
                                    mainHandler,
                                    callbackRef,
                                    "Update found: v" + remoteVer + " — Auto-downloading...",
                                    true
                            );
                            downloadAndInstallApk(appContext, finalApkUrl, remoteVer, mainHandler, callbackRef);
                        } else {
                            checkInProgress = false;
                            updateStatus(
                                    mainHandler,
                                    callbackRef,
                                    "Update available: v" + remoteVer + " (Open BookTools to install)",
                                    true
                            );
                        }
                    } else {
                        updateAvailable = false;
                        checkInProgress = false;
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
        });
    }

    private static void downloadAndInstallApk(
            final Context appContext,
            final String apkUrl,
            final String remoteVer,
            final Handler mainHandler,
            final WeakReference<StatusCallback> callbackRef
    ) {
        File outFile = new File(appContext.getCacheDir(), "BookTools_update.apk");
        try {
            if (outFile.exists()) {
                //noinspection ResultOfMethodCallIgnored
                outFile.delete();
            }

            HttpsURLConnection conn = openSecureGitHubConnection(apkUrl, "application/octet-stream");
            int totalBytes = conn.getContentLength();

            try (InputStream in = conn.getInputStream();
                 FileOutputStream out = new FileOutputStream(outFile)) {
                byte[] buf = new byte[16 * 1024];
                int read;
                int downloaded = 0;
                int lastReportedPct = -1;
                while ((read = in.read(buf)) != -1) {
                    out.write(buf, 0, read);
                    downloaded += read;
                    if (totalBytes > 0) {
                        int pct = Math.min(100, (int) ((downloaded * 100L) / totalBytes));
                        if (pct != lastReportedPct) {
                            lastReportedPct = pct;
                            updateStatus(
                                    mainHandler,
                                    callbackRef,
                                    "Downloading v" + remoteVer + "... " + pct + "%",
                                    true
                            );
                        }
                    }
                }
                out.flush();
            } finally {
                conn.disconnect();
            }

            // Security Verification: Ensure downloaded APK matches our package name and signing certificate
            if (!verifyDownloadedApkSignature(appContext, outFile)) {
                //noinspection ResultOfMethodCallIgnored
                outFile.delete();
                checkInProgress = false;
                updateStatus(
                        mainHandler,
                        callbackRef,
                        "Security check failed: APK signature mismatch",
                        false
                );
                return;
            }

            pendingInstallApkFile = outFile;
            checkInProgress = false;

            mainHandler.post(new Runnable() {
                @Override
                public void run() {
                    MainActivity act = MainActivity.getCurrent();
                    Context installCtx = act != null ? act : appContext;
                    installDownloadedApkIfPermitted(installCtx, callbackRef.get());
                }
            });
        } catch (Exception e) {
            if (outFile.exists()) {
                //noinspection ResultOfMethodCallIgnored
                outFile.delete();
            }
            checkInProgress = false;
            updateStatus(
                    mainHandler,
                    callbackRef,
                    "Download failed: " + e.getMessage(),
                    true
            );
        }
    }

    @SuppressWarnings("deprecation")
    private static Signature[] extractSignatures(PackageInfo info) {
        if (info == null) return null;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P && info.signingInfo != null) {
            SigningInfo si = info.signingInfo;
            Signature[] sigs = si.hasMultipleSigners()
                    ? si.getApkContentsSigners()
                    : si.getSigningCertificateHistory();
            if (sigs != null && sigs.length > 0) {
                return sigs;
            }
        }
        return info.signatures;
    }

    @SuppressWarnings("deprecation")
    private static boolean verifyDownloadedApkSignature(Context context, File apkFile) {
        try {
            PackageManager pm = context.getPackageManager();
            int flags = Build.VERSION.SDK_INT >= Build.VERSION_CODES.P
                    ? PackageManager.GET_SIGNING_CERTIFICATES
                    : PackageManager.GET_SIGNATURES;

            PackageInfo archiveInfo = pm.getPackageArchiveInfo(apkFile.getAbsolutePath(), flags);
            if (archiveInfo == null || !context.getPackageName().equals(archiveInfo.packageName)) {
                return false;
            }
            long archiveCode = Build.VERSION.SDK_INT >= Build.VERSION_CODES.P
                    ? archiveInfo.getLongVersionCode()
                    : archiveInfo.versionCode;
            if (archiveCode <= getInstalledVersionCode(context)) {
                return false;
            }

            Signature[] archiveSigs = extractSignatures(archiveInfo);
            if (archiveSigs == null || archiveSigs.length == 0) {
                // Fallback for older devices where GET_SIGNING_CERTIFICATES on archive returns null
                PackageInfo fallback = pm.getPackageArchiveInfo(apkFile.getAbsolutePath(), PackageManager.GET_SIGNATURES);
                archiveSigs = extractSignatures(fallback);
            }
            if (archiveSigs == null || archiveSigs.length == 0) {
                return false;
            }

            PackageInfo installedInfo = pm.getPackageInfo(context.getPackageName(), flags);
            Signature[] installedSigs = extractSignatures(installedInfo);
            if (installedSigs == null || installedSigs.length == 0) {
                return true;
            }

            for (Signature installed : installedSigs) {
                if (installed == null) continue;
                byte[] installedBytes = installed.toByteArray();
                for (Signature candidate : archiveSigs) {
                    if (candidate != null && Arrays.equals(installedBytes, candidate.toByteArray())) {
                        return true;
                    }
                }
            }
            return false;
        } catch (Exception e) {
            return false;
        }
    }

    public static void installDownloadedApkIfPermitted(Context context, StatusCallback callback) {
        final File apkFile = pendingInstallApkFile;
        if (apkFile == null || !apkFile.exists() || installInProgress) {
            return;
        }

        final Context appContext = context.getApplicationContext();
        final Handler mainHandler = new Handler(Looper.getMainLooper());
        final WeakReference<StatusCallback> callbackRef = new WeakReference<>(callback);

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            if (!appContext.getPackageManager().canRequestPackageInstalls()) {
                String msg = "Allow 'Install unknown apps' once to finish updating to v"
                        + (latestRemoteVersion != null ? latestRemoteVersion : "");
                updateStatus(mainHandler, callbackRef, msg, true);
                if (context instanceof Activity || MainActivity.getCurrent() != null) {
                    Toast.makeText(appContext, msg, Toast.LENGTH_LONG).show();
                    try {
                        Intent intent = new Intent(
                                Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                                Uri.parse("package:" + appContext.getPackageName())
                        );
                        Activity act = (context instanceof Activity) ? (Activity) context : MainActivity.getCurrent();
                        if (act != null && !act.isFinishing() && !act.isDestroyed()) {
                            act.startActivity(intent);
                        }
                    } catch (Exception ignored) {
                    }
                }
                return;
            }
        }

        installInProgress = true;
        final String installingMsg = "Installing v" + (latestRemoteVersion != null ? latestRemoteVersion : "") + "...";
        updateStatus(mainHandler, callbackRef, installingMsg, true);

        UPDATE_EXECUTOR.execute(() -> {
            PackageInstaller packageInstaller = appContext.getPackageManager().getPackageInstaller();
            PackageInstaller.Session session = null;
            boolean committed = false;
            try {
                PackageInstaller.SessionParams params =
                        new PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL);
                params.setAppPackageName(appContext.getPackageName());
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    params.setRequireUserAction(PackageInstaller.SessionParams.USER_ACTION_NOT_REQUIRED);
                }

                int sessionId = packageInstaller.createSession(params);
                session = packageInstaller.openSession(sessionId);

                try (InputStream in = new FileInputStream(apkFile);
                     OutputStream out = session.openWrite("BookTools.apk", 0, apkFile.length())) {
                    byte[] buffer = new byte[16 * 1024];
                    int c;
                    while ((c = in.read(buffer)) != -1) {
                        out.write(buffer, 0, c);
                    }
                    session.fsync(out);
                }

                Intent callbackIntent = new Intent(appContext, UpdateInstallReceiver.class);
                callbackIntent.setAction("com.example.topbardrop.ACTION_INSTALL_COMMIT");
                callbackIntent.setPackage(appContext.getPackageName());
                int flags = PendingIntent.FLAG_UPDATE_CURRENT;
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    flags |= PendingIntent.FLAG_MUTABLE;
                }
                PendingIntent pendingIntent = PendingIntent.getBroadcast(
                        appContext,
                        sessionId,
                        callbackIntent,
                        flags
                );

                session.commit(pendingIntent.getIntentSender());
                committed = true;

                // Clean up temporary APK from cache only after commit succeeds
                //noinspection ResultOfMethodCallIgnored
                apkFile.delete();
                pendingInstallApkFile = null;
            } catch (Exception e) {
                if (session != null && !committed) {
                    try {
                        session.abandon();
                    } catch (Exception ignored) {
                    }
                }
                updateStatus(mainHandler, callbackRef, "Install error: " + e.getMessage(), true);
            } finally {
                if (session != null) {
                    try {
                        session.close();
                    } catch (Exception ignored) {
                    }
                }
                installInProgress = false;
            }
        });
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
