package com.riya.assistant;

import android.Manifest;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ServiceInfo;
import android.graphics.drawable.Icon;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.PowerManager;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONObject;
import org.vosk.Model;
import org.vosk.Recognizer;
import org.vosk.android.RecognitionListener;
import org.vosk.android.SpeechService;

import java.util.HashSet;
import java.util.Locale;
import java.util.Set;

/**
 * "Hey Riya": a foreground service that listens for her name with a small OFFLINE model (nothing leaves the phone
 * while waiting). When it hears the name it releases the mic and runs a hands-free Conversation, then goes back to waiting.
 */
public class WakeService extends Service implements RecognitionListener {
    static final String ACTION_STOP = "com.riya.assistant.WAKE_STOP";
    private static final String TAG = "RiyaWake";
    private static final String CHANNEL = "riya_wake";
    private static final int NOTE_ID = 42;
    private static final float RATE = 16000f;

    static volatile WakeService instance;

    private final Handler main = new Handler(Looper.getMainLooper());
    private PowerManager.WakeLock lock;
    private Model model;
    private SpeechService speech;
    private Recognizer recognizer;
    private Conversation convo;
    private boolean paused, talking, loading;
    private long lastWake;
    private Set<String> wakeWords = new HashSet<>();

    static boolean isRunning() {
        return instance != null;
    }

    /** Called by the panel so the two never fight over the microphone. */
    static void pauseListening() {
        WakeService s = instance;
        if (s != null) {
            s.main.post(s::doPause);
        }
    }

    static void resumeListening() {
        WakeService s = instance;
        if (s != null) {
            s.main.post(s::doResume);
        }
    }

    // ---------------- lifecycle ----------------
    @Override
    public void onCreate() {
        super.onCreate();
        instance = this;
        NotificationChannel ch = new NotificationChannel(CHANNEL, "Hey Riya listening", NotificationManager.IMPORTANCE_LOW);
        getSystemService(NotificationManager.class).createNotificationChannel(ch);
        try {
            Notification n = build("Starting...");
            if (Build.VERSION.SDK_INT >= 30) {
                startForeground(NOTE_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE);
            } else {
                startForeground(NOTE_ID, n);
            }
        } catch (Exception e) {
            Log.e(TAG, "cannot start foreground", e);
            stopSelf();
            return;
        }
        PowerManager pm = (PowerManager) getSystemService(POWER_SERVICE);
        lock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "riya:wake");
        lock.acquire();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null && ACTION_STOP.equals(intent.getAction())) {
            RiyaClient.prefs(this).edit().putBoolean("wake_on", false).apply();
            stopSelf();
            return START_NOT_STICKY;
        }
        if (lock == null) {  // onCreate failed (e.g. no microphone permission): nothing to run
            return START_NOT_STICKY;
        }
        if (model == null && !loading) {
            loadModel();
        }
        return START_STICKY;
    }

    @Override
    public void onDestroy() {
        instance = null;
        stopListening();
        if (convo != null) {
            convo.stop();
            convo = null;
        }
        if (model != null) {
            model.close();
            model = null;
        }
        if (lock != null && lock.isHeld()) {
            lock.release();
        }
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    // ---------------- wake-word listening ----------------
    private void loadModel() {
        if (!ModelStore.ready(this)) {
            note("Wake-word model missing: open the Riya app");
            return;
        }
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            note("Microphone permission needed: open the Riya app");
            return;
        }
        wakeWords = new HashSet<>();
        for (String w : RiyaClient.get(this, "wake_words", "riya,ria,rhea").toLowerCase(Locale.ROOT).split(",")) {
            if (!w.trim().isEmpty()) {
                wakeWords.add(w.trim());
            }
        }
        loading = true;
        note("Loading...");
        final String path = ModelStore.modelDir(this).getAbsolutePath();
        new Thread(() -> {
            try {
                Model m = new Model(path);
                main.post(() -> {
                    loading = false;
                    if (instance != this) {
                        m.close();
                        return;
                    }
                    model = m;
                    startListening();
                });
            } catch (Throwable t) {  // includes missing native libraries
                Log.e(TAG, "model load failed", t);
                main.post(() -> {
                    loading = false;
                    note("Could not load model: " + t.getMessage());
                });
            }
        }).start();
    }

    private String grammar() {
        JSONArray a = new JSONArray();
        for (String w : wakeWords) {
            a.put(w);
            a.put("hey " + w);
        }
        a.put("[unk]");
        return a.toString();
    }

    private String waitingText() {
        return "Say \"Hey Riya\"";
    }

    private void startListening() {
        if (paused || talking || model == null || speech != null) {
            return;
        }
        try {
            recognizer = new Recognizer(model, RATE, grammar());
            speech = new SpeechService(recognizer, RATE);
            speech.startListening(this);
            note(waitingText());
        } catch (Throwable t) {
            Log.e(TAG, "cannot listen", t);
            stopListening();
            note("Mic error: " + t.getMessage());
            main.postDelayed(this::startListening, 5000);
        }
    }

    private void stopListening() {
        if (speech != null) {
            try {
                speech.stop();
                speech.shutdown();
            } catch (Throwable ignored) {
            }
            speech = null;
        }
        if (recognizer != null) {
            try {
                recognizer.close();
            } catch (Throwable ignored) {
            }
            recognizer = null;
        }
    }

    private boolean heardWakeWord(String text) {
        if (text == null || text.isEmpty()) {
            return false;
        }
        for (String token : text.toLowerCase(Locale.ROOT).split("\\s+")) {
            if (wakeWords.contains(token)) {
                return true;
            }
        }
        return false;
    }

    private void wake() {
        long now = System.currentTimeMillis();
        if (talking || paused || now - lastWake < 3000) {
            return;
        }
        lastWake = now;
        talking = true;
        stopListening();
        note("Riya is talking with you...");
        convo = new Conversation(this, new Conversation.Ui() {
            @Override
            public void status(String s) {
                note(s);
            }

            @Override
            public void said(String s) {
            }

            @Override
            public void reply(String s) {
            }

            @Override
            public void done() {
                talking = false;
                convo = null;
                note(waitingText());
                if (!paused) {
                    main.postDelayed(WakeService.this::startListening, 800);
                }
            }
        });
        convo.start();
    }

    private void doPause() {
        paused = true;
        stopListening();
        if (convo != null) {
            convo.stop();
            convo = null;
            talking = false;
        }
        note("Paused while Riya's panel is open");
    }

    private void doResume() {
        paused = false;
        if (!talking) {
            note(waitingText());
            main.postDelayed(this::startListening, 700);
        }
    }

    // ---------------- Vosk RecognitionListener (main thread) ----------------
    @Override
    public void onPartialResult(String json) {
        if (speech == null) {
            return;
        }
        try {
            if (heardWakeWord(new JSONObject(json).optString("partial"))) {
                wake();
            }
        } catch (Exception ignored) {
        }
    }

    @Override
    public void onResult(String json) {
        if (speech == null) {
            return;
        }
        try {
            if (heardWakeWord(new JSONObject(json).optString("text"))) {
                wake();
            }
        } catch (Exception ignored) {
        }
    }

    @Override
    public void onFinalResult(String json) {
    }

    @Override
    public void onError(Exception e) {
        Log.e(TAG, "recognizer error", e);
        stopListening();
        main.postDelayed(this::startListening, 3000);
    }

    @Override
    public void onTimeout() {
        stopListening();
        startListening();
    }

    // ---------------- notification ----------------
    private Notification build(String text) {
        PendingIntent open = PendingIntent.getActivity(this, 0, new Intent(this, SettingsActivity.class),
                PendingIntent.FLAG_IMMUTABLE);
        PendingIntent stop = PendingIntent.getService(this, 1,
                new Intent(this, WakeService.class).setAction(ACTION_STOP), PendingIntent.FLAG_IMMUTABLE);
        Notification.Action stopAction = new Notification.Action.Builder(
                Icon.createWithResource(this, android.R.drawable.ic_menu_close_clear_cancel), "Stop", stop).build();
        return new Notification.Builder(this, CHANNEL)
                .setContentTitle("Hey Riya")
                .setContentText(text)
                .setSmallIcon(android.R.drawable.ic_btn_speak_now)
                .setContentIntent(open)
                .addAction(stopAction)
                .setOngoing(true)
                .build();
    }

    private void note(String text) {
        getSystemService(NotificationManager.class).notify(NOTE_ID, build(text));
    }
}
