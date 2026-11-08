package com.example.topbardrop;

import android.app.Activity;
import android.content.ClipData;
import android.content.ContentValues;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.provider.MediaStore;
import android.provider.Settings;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import java.io.OutputStream;

public class MainActivity extends Activity {

    private TextView statusBadge;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(Color.parseColor("#F8F9FA"));
        int pad = dpToPx(24f);
        root.setPadding(pad, dpToPx(48f), pad, pad);

        TextView title = new TextView(this);
        title.setText("TopBarDrop (Material Design)");
        title.setTextSize(TypedValue.COMPLEX_UNIT_SP, 22f);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        title.setTextColor(Color.parseColor("#202124"));
        root.addView(title);

        statusBadge = new TextView(this);
        statusBadge.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f);
        statusBadge.setPadding(0, dpToPx(12f), 0, dpToPx(20f));
        statusBadge.setTextColor(Color.parseColor("#5F6368"));
        root.addView(statusBadge);

        Button enableBtn = new Button(this);
        enableBtn.setText("Enable / Refresh Top Bar Icon");
        enableBtn.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                ensureOverlayPermissionAndStart();
            }
        });
        root.addView(enableBtn);

        // Built-in draggable sample card so the user can test dragging an image immediately
        TextView dragCard = new TextView(this);
        dragCard.setText("🖼  Long-press & drag me to the top bar icon!\n(Test file: sample_image.png → sample_image_dl.png)");
        dragCard.setTextColor(Color.WHITE);
        dragCard.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f);
        dragCard.setGravity(Gravity.CENTER);
        int cardPad = dpToPx(20f);
        dragCard.setPadding(cardPad, cardPad, cardPad, cardPad);

        GradientDrawable cardBg = new GradientDrawable();
        cardBg.setCornerRadius(dpToPx(16f));
        cardBg.setColor(Color.parseColor("#1A73E8"));
        dragCard.setBackground(cardBg);

        LinearLayout.LayoutParams cardLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
        );
        cardLp.topMargin = dpToPx(32f);
        root.addView(dragCard, cardLp);

        dragCard.setOnLongClickListener(new View.OnLongClickListener() {
            @Override
            public boolean onLongClick(View v) {
                Uri sampleUri = createSampleImageUri();
                if (sampleUri == null) {
                    Toast.makeText(MainActivity.this, "Could not create sample image", Toast.LENGTH_SHORT).show();
                    return false;
                }
                ClipData clipData = ClipData.newUri(getContentResolver(), "sample_image.png", sampleUri);
                View.DragShadowBuilder shadow = new View.DragShadowBuilder(v);
                int flags = View.DRAG_FLAG_GLOBAL | View.DRAG_FLAG_GLOBAL_URI_READ;
                v.startDragAndDrop(clipData, shadow, null, flags);
                return true;
            }
        });

        setContentView(root);
        ensureOverlayPermissionAndStart();
    }

    @Override
    protected void onResume() {
        super.onResume();
        updateStatusText();
        if (Settings.canDrawOverlays(this)) {
            startTopBarService();
        }
    }

    private void updateStatusText() {
        if (Settings.canDrawOverlays(this)) {
            statusBadge.setText("Status: ACTIVE — Look at the top bar (left of battery/Wi-Fi) for the blue ⬇ pill. Drag any file or the test card below into it.");
        } else {
            statusBadge.setText("Status: PERMISSION NEEDED — Please allow 'Display over other apps' so TopBarDrop can place its icon at the top bar.");
        }
    }

    private void ensureOverlayPermissionAndStart() {
        if (!Settings.canDrawOverlays(this)) {
            Intent intent = new Intent(
                    Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                    Uri.parse("package:" + getPackageName())
            );
            startActivity(intent);
        } else {
            startTopBarService();
            Toast.makeText(this, "Top bar icon active!", Toast.LENGTH_SHORT).show();
        }
        updateStatusText();
    }

    private void startTopBarService() {
        Intent serviceIntent = new Intent(this, TopBarDropService.class);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(serviceIntent);
        } else {
            startService(serviceIntent);
        }
    }

    private Uri createSampleImageUri() {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                ContentValues values = new ContentValues();
                values.put(MediaStore.Images.Media.DISPLAY_NAME, "sample_image.png");
                values.put(MediaStore.Images.Media.MIME_TYPE, "image/png");
                values.put(MediaStore.Images.Media.RELATIVE_PATH, Environment.DIRECTORY_PICTURES);

                Uri uri = getContentResolver().insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values);
                if (uri != null) {
                    Bitmap bmp = Bitmap.createBitmap(300, 300, Bitmap.Config.ARGB_8888);
                    Canvas canvas = new Canvas(bmp);
                    canvas.drawColor(Color.parseColor("#1A73E8"));
                    Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
                    p.setColor(Color.WHITE);
                    p.setTextSize(36f);
                    canvas.drawText("Sample PNG", 45f, 160f, p);
                    try (OutputStream out = getContentResolver().openOutputStream(uri)) {
                        bmp.compress(Bitmap.CompressFormat.PNG, 100, out);
                    }
                    return uri;
                }
            }
        } catch (Exception ignored) {
        }
        return null;
    }

    private int dpToPx(float dp) {
        return Math.round(TypedValue.applyDimension(
                TypedValue.COMPLEX_UNIT_DIP,
                dp,
                getResources().getDisplayMetrics()
        ));
    }
}
