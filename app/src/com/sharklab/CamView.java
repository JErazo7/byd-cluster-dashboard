package com.sharklab;

import android.app.Activity;
import android.graphics.SurfaceTexture;
import android.os.Bundle;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.TextureView;
import android.view.View;
import android.view.WindowManager;
import android.widget.FrameLayout;
import android.widget.TextView;

/**
 * CamView — tenta mostrar a câmera do carro (ré, pisca, panorâmica) em tela cheia.
 *
 * Entrega um Surface do TextureView para o serviço da BYD e mostra o que vier. Se o serviço
 * não existir nesta unidade (ou exigir permissão de sistema), aparece o motivo em texto, em
 * vez de uma tela preta — é o que permite ajustar sem o carro na mão.
 */
public class CamView extends Activity implements TextureView.SurfaceTextureListener {

    private TextureView view;
    private TextView info;
    private SurfaceTexture texture;
    private android.view.Surface surface;

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        getWindow().getDecorView().setSystemUiVisibility(
                View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY | View.SYSTEM_UI_FLAG_FULLSCREEN
                        | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION | View.SYSTEM_UI_FLAG_LAYOUT_STABLE);

        FrameLayout box = new FrameLayout(this);
        box.setBackgroundColor(0xFF000000);
        view = new TextureView(this);
        view.setSurfaceTextureListener(this);
        box.addView(view, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT));

        info = new TextView(this);
        info.setTextColor(0xFFE6EEF6);
        info.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16);
        info.setGravity(Gravity.CENTER);
        info.setPadding(24, 24, 24, 24);
        box.addView(info, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT));
        setContentView(box);
    }

    @Override
    public void onSurfaceTextureAvailable(SurfaceTexture st, int w, int h) {
        texture = st;
        surface = new android.view.Surface(st);
        String r = CameraService.openPreview(surface);
        info.setText("ok".equals(r) ? "" : "câmera indisponível\n\n" + r
                + "\n\nserviço: " + (CameraService.serviceName().isEmpty() ? "—" : CameraService.serviceName()));
    }

    @Override
    public void onSurfaceTextureSizeChanged(SurfaceTexture st, int w, int h) {
    }

    @Override
    public boolean onSurfaceTextureDestroyed(SurfaceTexture st) {
        if (surface != null) {
            CameraService.closePreview(surface);
            try { surface.release(); } catch (Throwable ignored) {}
            surface = null;
        }
        return true;
    }

    @Override
    public void onSurfaceTextureUpdated(SurfaceTexture st) {
    }

    @Override
    protected void onDestroy() {
        if (surface != null) {
            CameraService.closePreview(surface);
            try { surface.release(); } catch (Throwable ignored) {}
            surface = null;
        }
        super.onDestroy();
    }
}
