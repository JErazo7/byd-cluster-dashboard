package com.sharklab;

import android.app.Activity;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.view.WindowManager;
import android.widget.FrameLayout;
import android.widget.ImageView;

/**
 * MirrorActivity — shows the head unit screen in real time, full screen.
 *
 * Made to run on the cluster panel display: it opens after the capture consent and draws the
 * latest Mirror frame (~10 fps). Tap to exit.
 */
public class MirrorActivity extends Activity {

    private ImageView view;
    private final Handler ui = new Handler(Looper.getMainLooper());
    private long lastAt = -1;
    private final Runnable tick = new Runnable() {
        @Override
        public void run() {
            byte[] f = Mirror.frame();
            long at = Mirror.frameAge();
            if (f != null && at >= 0) {
                /* only decodes a new frame */
                long stamp = System.currentTimeMillis() - at;
                if (stamp != lastAt) {
                    lastAt = stamp;
                    Bitmap bmp = BitmapFactory.decodeByteArray(f, 0, f.length);
                    if (bmp != null) view.setImageBitmap(bmp);
                }
            }
            ui.postDelayed(this, 90);
        }
    };

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        getWindow().getDecorView().setSystemUiVisibility(
                View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY | View.SYSTEM_UI_FLAG_FULLSCREEN
                        | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION | View.SYSTEM_UI_FLAG_LAYOUT_STABLE);

        FrameLayout box = new FrameLayout(this);
        box.setBackgroundColor(0xFF000000);
        view = new ImageView(this);
        view.setScaleType(ImageView.ScaleType.FIT_CENTER);
        box.addView(view, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT));
        box.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                finish();
            }
        });
        setContentView(box);
        ui.post(tick);
    }

    @Override
    protected void onDestroy() {
        ui.removeCallbacks(tick);
        Mirror.stop();
        super.onDestroy();
    }
}
