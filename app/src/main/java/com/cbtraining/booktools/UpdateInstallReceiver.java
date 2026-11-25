package com.cbtraining.booktools;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageInstaller;
import android.os.Build;
import android.widget.Toast;

/**
 * Receives callbacks from Android's PackageInstaller when BookTools self-updates.
 * Launches the system confirmation prompt when STATUS_PENDING_USER_ACTION is returned.
 */
public class UpdateInstallReceiver extends BroadcastReceiver {

    @Override
    public void onReceive(Context context, Intent intent) {
        if (intent == null) return;
        int status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE);
        String message = intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE);

        if (status == PackageInstaller.STATUS_PENDING_USER_ACTION) {
            Intent confirmIntent = MediaToolUtils.safeGetParcelableExtra(intent, Intent.EXTRA_INTENT, Intent.class);
            if (confirmIntent != null) {
                try {
                    // On Android 16+ (API 36), Intent Redirection hardening blocks launching a sub-intent
                    // extracted from an extra unless explicitly allowed; this receiver is non-exported and
                    // only receives our own PackageInstaller PendingIntent from the OS.
                    if (Build.VERSION.SDK_INT >= 36) {
                        confirmIntent.removeLaunchSecurityProtection();
                    }
                    MainActivity act = MainActivity.getCurrent();
                    if (act != null) {
                        act.startActivity(confirmIntent);
                    } else {
                        confirmIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                        context.startActivity(confirmIntent);
                    }
                } catch (Exception e) {
                    Toast.makeText(context, "Open BookTools to finish installing the update.", Toast.LENGTH_LONG).show();
                }
            }
        } else if (status == PackageInstaller.STATUS_SUCCESS) {
            Toast.makeText(context, "BookTools updated successfully!", Toast.LENGTH_LONG).show();
        } else if (status != PackageInstaller.STATUS_FAILURE_ABORTED) {
            Toast.makeText(context, "Update status: " + (message != null ? message : status), Toast.LENGTH_LONG).show();
        }
    }
}
