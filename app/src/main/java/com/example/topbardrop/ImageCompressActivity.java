package com.example.topbardrop;

import android.net.Uri;
import android.widget.Toast;

import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Floating desktop dialog activity launched from the Files app right-click menu:
 * Right-Click image -> Open with (or Share) -> "Compress image" (or subclassed for "Upscale image (2x HD)").
 * Runs 100% offline image compression or 2x Contrast-Adaptive HD upscaling via ImageProcessor.
 */
public class ImageCompressActivity extends BaseProgressToolActivity {

    private AtomicBoolean cancelFlag;
    private boolean taskCompleted = false;

    protected boolean isUpscaleMode() {
        return false;
    }

    @Override
    protected String getInitialTitle() {
        return isUpscaleMode() ? "Upscale Image (2x HD)" : "Compress Image";
    }

    @Override
    protected String getInitialSubtitle() {
        return isUpscaleMode()
                ? "100% Offline 2× Contrast-Adaptive HD Upscaler · BookTools"
                : "100% Offline Smart Image Compressor · BookTools";
    }

    @Override
    protected String getInitialFilePlaceholder() {
        return "Preparing image...";
    }

    @Override
    protected String getInitialStatusText() {
        return isUpscaleMode() ? "Starting 2× HD upscaler..." : "Starting smart image compressor...";
    }

    @Override
    protected String getMissingUriErrorMessage() {
        return "No image file URI received from Files.";
    }

    @Override
    protected boolean isToolCompletedOrFailed() {
        return taskCompleted;
    }

    @Override
    protected void onCancelActiveTool() {
        if (cancelFlag != null) {
            cancelFlag.set(true);
            cancelFlag = null;
        }
    }

    @Override
    protected void onStartToolForUri(Uri imageUri) {
        taskCompleted = false;
        cancelFlag = new AtomicBoolean(false);
        final boolean upscale = isUpscaleMode();

        titleTextView.setText(getInitialTitle());
        subtitleTextView.setText(getInitialSubtitle());
        progressBar.setProgress(0);
        cancelOrCloseBtn.setText("Cancel");
        MediaToolUtils.updateButtonAppearance(this, cancelOrCloseBtn, dark, false);

        ImageProcessor.ProcessCallback callback = new ImageProcessor.ProcessCallback() {
            @Override
            public void onFileInspected(String displayName, long originalBytes, int width, int height) {
                if (isFinishing() || isDestroyed()) return;
                String origSize = MediaToolUtils.formatBytes(originalBytes);
                fileNameTextView.setText(displayName + "  (" + width + "×" + height + " · " + origSize + ")");
            }

            @Override
            public void onProgress(int percent, String statusText) {
                if (isFinishing() || isDestroyed()) return;
                progressBar.setProgress(percent);
                statusTextView.setText(statusText);
            }

            @Override
            public void onSuccess(ImageProcessor.ImageResult result) {
                if (isFinishing() || isDestroyed()) return;
                taskCompleted = true;
                progressBar.setProgress(100);
                titleTextView.setText("✓ File done!");
                subtitleTextView.setText("Saved to Downloads: " + result.savedFileName);
                fileNameTextView.setText(result.savedFileName
                        + "  (" + result.outWidth + "×" + result.outHeight + ")");

                String origStr = MediaToolUtils.formatBytes(result.originalBytes);
                String newStr = MediaToolUtils.formatBytes(result.outputBytes);
                if (upscale) {
                    statusTextView.setText("Upscaled " + result.srcWidth + "×" + result.srcHeight
                            + " → " + result.outWidth + "×" + result.outHeight + " HD (" + newStr + ")");
                } else if (result.originalBytes > 0) {
                    statusTextView.setText("Reduced from " + origStr + " → " + newStr);
                } else {
                    statusTextView.setText("Final size: " + newStr);
                }

                showDoneButton();
                Toast.makeText(
                        ImageCompressActivity.this,
                        "✓ File done: " + result.savedFileName,
                        Toast.LENGTH_SHORT
                ).show();
            }

            @Override
            public void onError(String errorMessage) {
                if (isFinishing() || isDestroyed()) return;
                taskCompleted = true;
                showErrorState(errorMessage);
            }
        };

        if (upscale) {
            ImageProcessor.upscaleImage2xAsync(this, imageUri, cancelFlag, callback);
        } else {
            ImageProcessor.compressImageAsync(this, imageUri, cancelFlag, callback);
        }
    }
}
