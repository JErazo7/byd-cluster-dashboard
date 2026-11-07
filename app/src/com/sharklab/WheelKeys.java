package com.sharklab;

import android.accessibilityservice.AccessibilityService;
import android.content.ComponentName;
import android.content.Context;
import android.provider.Settings;
import android.view.KeyEvent;
import android.view.accessibility.AccessibilityEvent;

/**
 * WheelKeys — botões do volante.
 *
 * AccessibilityService com filtro de teclas: recebe o que a central entrega aos serviços de
 * acessibilidade (mídia, volume, setas, enter, voltar). Não interfere em outros apps e não
 * precisa de root; o usuário habilita o serviço uma vez.
 *
 * A skin lê Lab.wheel() e decide a ação (trocar motor, trocar painel, volume).
 */
public class WheelKeys extends AccessibilityService {

    private static volatile int lastKey = -1;
    private static volatile long lastAt = 0;
    private static volatile int count = 0;

    @Override
    protected void onServiceConnected() {
        super.onServiceConnected();
    }

    @Override
    public void onAccessibilityEvent(AccessibilityEvent event) {
        /* só as teclas interessam */
    }

    @Override
    public boolean onKeyEvent(KeyEvent event) {
        if (event != null && event.getAction() == KeyEvent.ACTION_DOWN) {
            lastKey = event.getKeyCode();
            lastAt = System.currentTimeMillis();
            count++;
        }
        return false;                     /* não consome: o app de origem continua recebendo */
    }

    @Override
    public void onInterrupt() {
    }

    /** última tecla: {key, age, count} */
    public static String snapshot() {
        return "{\"key\":" + lastKey + ",\"age\":" + (lastKey < 0 ? -1 : System.currentTimeMillis() - lastAt)
                + ",\"count\":" + count + "}";
    }

    public static boolean isEnabled(Context ctx) {
        try {
            String flat = Settings.Secure.getString(ctx.getContentResolver(), "enabled_accessibility_services");
            if (flat == null) return false;
            String me = new ComponentName(ctx, WheelKeys.class).flattenToString();
            return flat.contains(me) || flat.contains(ctx.getPackageName());
        } catch (Throwable t) {
            return false;
        }
    }
}
