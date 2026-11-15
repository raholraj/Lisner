package com.example.listener;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;
import android.provider.MediaStore;
import java.io.File;

/**
 * Simple activity that opens the system camera.
 * Result is sent back via broadcast to ListenerService.
 */
public class CaptureActivity extends Activity {
    public static final String ACTION_PHOTO_RESULT = "com.example.listener.PHOTO_RESULT";
    public static final String EXTRA_PATH = "path";
    public static final String EXTRA_CHAT = "chatId";
    private static final int REQ_CAMERA = 1001;
    private String chatId;
    private File photoFile;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        chatId = getIntent().getStringExtra(EXTRA_CHAT);
        if (chatId == null) {
            finish();
            return;
        }
        try {
            File dir = new File(getExternalFilesDir(null), "photos");
            dir.mkdirs();
            photoFile = new File(dir, "P_" + System.currentTimeMillis() + ".jpg");
            Intent take = new Intent(MediaStore.ACTION_IMAGE_CAPTURE);
            startActivityForResult(take, REQ_CAMERA);
        } catch (Exception e) {
            sendResult(null);
            finish();
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        String path = null;
        if (requestCode == REQ_CAMERA && resultCode == RESULT_OK) {
            try {
                if (photoFile != null && photoFile.exists() && photoFile.length() > 0) {
                    path = photoFile.getAbsolutePath();
                }
            } catch (Exception ignored) {}
        }
        sendResult(path);
        finish();
    }

    private void sendResult(String path) {
        Intent i = new Intent(ACTION_PHOTO_RESULT);
        i.setPackage(getPackageName());
        i.putExtra(EXTRA_CHAT, chatId);
        if (path != null) i.putExtra(EXTRA_PATH, path);
        sendBroadcast(i);
    }
}
