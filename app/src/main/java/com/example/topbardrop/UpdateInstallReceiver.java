package com.example.topbardrop;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageInstaller;
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
            Intent confirmIntent = intent.getParcelableExtra(Intent.EXTRA_INTENT);
            if (confirmIntent != null) {
                confirmIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                try {
                    context.startActivity(confirmIntent);
                } catch (Exception e) {
                    Toast.makeText(context, "Could not launch update prompt: " + e.getMessage(), Toast.LENGTH_LONG).show();
                }
            }
        } else if (status == PackageInstaller.STATUS_SUCCESS) {
            Toast.makeText(context, "BookTools updated successfully!", Toast.LENGTH_LONG).show();
        } else if (status != PackageInstaller.STATUS_FAILURE_ABORTED) {
            Toast.makeText(context, "Update status: " + (message != null ? message : status), Toast.LENGTH_LONG).show();
        }
    }
}
