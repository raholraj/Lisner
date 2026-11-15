package com.example.listener;

import android.content.*;
import android.os.Build;

public class BootReceiver extends BroadcastReceiver {
    @Override public void onReceive(Context c, Intent i) {
        try {
            if (!Intent.ACTION_BOOT_COMPLETED.equals(i.getAction())) return;
            Intent svc = new Intent(c, ListenerService.class);
            if (Build.VERSION.SDK_INT >= 26) c.startForegroundService(svc);
            else c.startService(svc);
        } catch (Exception ignored) {}
    }
}
