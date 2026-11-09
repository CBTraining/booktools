package com.example.topbardrop;

/**
 * Floating desktop dialog activity launched from the Files app right-click menu:
 * Right-Click image -> Open with (or Share) -> "Upscale image (2x HD)".
 * Inherits UI and lifecycle from ImageCompressActivity with zero duplicate code.
 */
public class ImageUpscaleActivity extends ImageCompressActivity {

    @Override
    protected boolean isUpscaleMode() {
        return true;
    }
}
