package com.sharklab;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;

/**
 * WakeActivity — acorda a SharkLab sem mostrar nada.
 *
 * O vigia roda como shell e, com o pacote parado depois do apagamento, `am start-foreground-service`
 * é recusado ("Not found; no service started"). `am start` de uma activity é sempre aceito.
 * É o mesmo truque do EvSound (`ArmActivity --ez wake_only true`): esta activity não tem layout,
 * sobe o PanelService e sai antes do primeiro quadro.
 */
public class WakeActivity extends Activity {

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        try {
            Intent svc = new Intent(this, PanelService.class);
            if (android.os.Build.VERSION.SDK_INT >= 26) startForegroundService(svc);
            else startService(svc);
            AdbShell.trace(this, "wake: activity");
        } catch (Throwable t) {
            AdbShell.trace(this, "wake: falhou " + t);
        }
        finish();
    }
}
