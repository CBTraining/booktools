package com.example.topbardrop;

import android.net.Uri;
import android.widget.Toast;

import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Floating desktop dialog activity launched from the Files app right-click menu:
 * Right-Click PDF -> Open with (or Share) -> "Extract PDF images (ZIP)".
 * Extracts embedded images and high-res page PNGs into a single .zip in Downloads.
 */
public class PdfExtractImagesActivity extends BaseProgressToolActivity {

    private AtomicBoolean cancelFlag;
    private boolean taskCompleted = false;

    @Override
    protected String getInitialTitle() {
        return "Extract PDF Images (ZIP)";
    }

    @Override
    protected String getInitialSubtitle() {
        return "100% Offline PDF Image & Page Extractor · BookTools";
    }

    @Override
    protected String getInitialFilePlaceholder() {
        return "Preparing PDF...";
    }

    @Override
    protected String getInitialStatusText() {
        return "Opening PDF document...";
    }

    @Override
    protected String getMissingUriErrorMessage() {
        return "No PDF file URI received from Files.";
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
    protected void onStartToolForUri(Uri pdfUri) {
        taskCompleted = false;
        cancelFlag = new AtomicBoolean(false);

        titleTextView.setText(getInitialTitle());
        subtitleTextView.setText(getInitialSubtitle());
        progressBar.setProgress(0);
        cancelOrCloseBtn.setText("Cancel");
        MediaToolUtils.updateButtonAppearance(this, cancelOrCloseBtn, dark, false);

        PdfImagesToZipExtractor.extractPdfImagesToZipAsync(
                this,
                pdfUri,
                cancelFlag,
                new PdfImagesToZipExtractor.ExtractCallback() {
                    @Override
                    public void onPdfInspected(String displayName, long originalBytes, int pageCount) {
                        if (isFinishing() || isDestroyed()) return;
                        String origSize = MediaToolUtils.formatBytes(originalBytes);
                        fileNameTextView.setText(displayName
                                + "  (" + pageCount + (pageCount == 1 ? " page" : " pages")
                                + " · " + origSize + ")");
                    }

                    @Override
                    public void onProgress(int percent, String statusText) {
                        if (isFinishing() || isDestroyed()) return;
                        progressBar.setProgress(percent);
                        statusTextView.setText(statusText);
                    }

                    @Override
                    public void onSuccess(PdfImagesToZipExtractor.ExtractResult result) {
                        if (isFinishing() || isDestroyed()) return;
                        taskCompleted = true;
                        progressBar.setProgress(100);
                        titleTextView.setText("✓ File done!");
                        subtitleTextView.setText("Saved to Downloads: " + result.savedZipFileName);

                        String zipSize = MediaToolUtils.formatBytes(result.zipSizeBytes);
                        fileNameTextView.setText(result.savedZipFileName + "  (" + zipSize + ")");

                        if (result.embeddedImagesExtracted > 0) {
                            statusTextView.setText("Packaged " + result.embeddedImagesExtracted
                                    + " embedded image(s) + " + result.pageImagesRendered
                                    + " page PNG(s) into ZIP (" + zipSize + ")");
                        } else {
                            statusTextView.setText("Packaged " + result.pageImagesRendered
                                    + " high-res page PNG(s) into ZIP (" + zipSize + ")");
                        }

                        showDoneButton();
                        Toast.makeText(
                                PdfExtractImagesActivity.this,
                                "✓ File done: " + result.savedZipFileName,
                                Toast.LENGTH_SHORT
                        ).show();
                    }

                    @Override
                    public void onError(String errorMessage) {
                        if (isFinishing() || isDestroyed()) return;
                        taskCompleted = true;
                        showErrorState(errorMessage);
                    }
                }
        );
    }
}
