package com.example.topbardrop;

import android.net.Uri;
import android.widget.Toast;

import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Floating desktop dialog activity launched from the Files app right-click menu:
 * Right-Click video -> Open with (or Share) -> "Compress video (~50mb)".
 * Runs 100% offline hardware-accelerated video compression via VideoCompressor.
 */
public class VideoCompressActivity extends BaseProgressToolActivity {

    private AtomicBoolean cancelFlag;
    private boolean compressionCompleted = false;

    @Override
    protected String getInitialTitle() {
        return "Compress Video (~50 MB)";
    }

    @Override
    protected String getInitialSubtitle() {
        return "100% Offline Hardware Video Compressor · BookTools";
    }

    @Override
    protected String getInitialFilePlaceholder() {
        return "Preparing video...";
    }

    @Override
    protected String getInitialStatusText() {
        return "Starting hardware encoder...";
    }

    @Override
    protected String getMissingUriErrorMessage() {
        return "No video file URI received from Files.";
    }

    @Override
    protected boolean isToolCompletedOrFailed() {
        return compressionCompleted;
    }

    @Override
    protected void onCancelActiveTool() {
        if (cancelFlag != null) {
            cancelFlag.set(true);
            cancelFlag = null;
        }
    }

    @Override
    protected void onStartToolForUri(Uri videoUri) {
        compressionCompleted = false;
        cancelFlag = new AtomicBoolean(false);

        titleTextView.setText(getInitialTitle());
        subtitleTextView.setText(getInitialSubtitle());
        progressBar.setProgress(0);
        cancelOrCloseBtn.setText("Cancel");
        MediaToolUtils.updateButtonAppearance(this, cancelOrCloseBtn, dark, false);

        VideoCompressor.compressVideoAsync(this, videoUri, cancelFlag, new VideoCompressor.CompressCallback() {
            @Override
            public void onMetadataReady(
                    VideoCompressor.VideoInfo info,
                    int targetWidth,
                    int targetHeight,
                    int targetBitrateKbps
            ) {
                if (isFinishing() || isDestroyed()) return;
                String origSize = MediaToolUtils.formatBytes(info.originalBytes);
                fileNameTextView.setText(info.displayName + "  (" + origSize + ")");
                statusTextView.setText("Transcoding to " + targetWidth + "×" + targetHeight
                        + " @ " + targetBitrateKbps + " kbps...");
            }

            @Override
            public void onProgress(int percent, String statusText) {
                if (isFinishing() || isDestroyed()) return;
                progressBar.setProgress(percent);
                statusTextView.setText(statusText);
            }

            @Override
            public void onSuccess(VideoCompressor.CompressResult result) {
                if (isFinishing() || isDestroyed()) return;
                compressionCompleted = true;
                progressBar.setProgress(100);
                titleTextView.setText("✓ File done!");
                subtitleTextView.setText("Saved to Downloads: " + result.savedFileName);
                fileNameTextView.setText(result.savedFileName
                        + "  (" + result.outputWidth + "×" + result.outputHeight + ")");

                String origStr = MediaToolUtils.formatBytes(result.originalBytes);
                String newStr = MediaToolUtils.formatBytes(result.compressedBytes);
                if (result.originalBytes > 0) {
                    statusTextView.setText("Reduced from " + origStr + " → " + newStr);
                } else {
                    statusTextView.setText("Final size: " + newStr);
                }

                showDoneButton();
                Toast.makeText(
                        VideoCompressActivity.this,
                        "✓ File done: " + result.savedFileName,
                        Toast.LENGTH_SHORT
                ).show();
            }

            @Override
            public void onError(String errorMessage) {
                if (isFinishing() || isDestroyed()) return;
                compressionCompleted = true;
                showErrorState(errorMessage);
            }
        });
    }
}
