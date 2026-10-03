package com.riya.assistant;

import android.Manifest;
import android.app.Activity;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.text.InputType;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

/** Setup screen: token, permissions, default assistant, and the "Hey Riya" background listener. */
public class SettingsActivity extends Activity {
    private EditText token, port, owner, lang, dir, wakeWords, modelUrl;
    private TextView status;
    private final Handler ui = new Handler(Looper.getMainLooper());

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        int pad = (int) (16 * getResources().getDisplayMetrics().density);
        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setPadding(pad, pad, pad, pad);

        TextView help = new TextView(this);
        help.setTextSize(15);
        help.setText("Riya assistant\n\n"
                + "A. Connect\n"
                + "1. In Termux run:  cat ~/riya-assistant/.riya_token | termux-clipboard-set\n"
                + "2. Tap 'Paste token', then 'Save'.\n"
                + "3. Tap 'Grant microphone' and 'Allow Termux start'.\n\n"
                + "B. Hold home / power button\n"
                + "Tap 'Set as default assistant' and choose Riya.\n\n"
                + "C. Hey Riya (hands-free)\n"
                + "1. 'Download wake-word model' (once, about 40 MB).\n"
                + "2. 'Allow notifications' and 'Don't restrict battery'.\n"
                + "3. 'Start Hey Riya', then just say her name.");
        col.addView(help);

        status = new TextView(this);
        status.setTextSize(14);
        status.setPadding(0, pad / 2, 0, pad / 2);
        col.addView(status);

        token = field(col, "Token (from Termux)", "token", "", true);
        port = field(col, "Server port", "port", "8765", false);
        owner = field(col, "Your name for her to use", "owner", "Boss", false);
        lang = field(col, "Speech language (en-IN, hi-IN)", "lang", "en-IN", false);
        dir = field(col, "Riya folder in Termux", "dir", RiyaClient.DEFAULT_DIR, false);
        wakeWords = field(col, "Wake words, comma separated", "wake_words", "riya,ria,rhea", false);
        modelUrl = field(col, "Wake-word model URL", "model_url", ModelStore.DEFAULT_URL, false);

        button(col, "Paste token", () -> {
            ClipboardManager cm = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
            ClipData d = cm.getPrimaryClip();
            if (d != null && d.getItemCount() > 0 && d.getItemAt(0).getText() != null) {
                token.setText(d.getItemAt(0).getText().toString().trim());
            } else {
                toast("Clipboard khali hai");
            }
        });
        button(col, "Save", () -> {
            save();
            toast("Saved");
        });
        button(col, "Grant microphone", () -> requestPermissions(new String[]{Manifest.permission.RECORD_AUDIO}, 1));
        button(col, "Allow Termux start", () ->
                requestPermissions(new String[]{"com.termux.permission.RUN_COMMAND"}, 2));
        button(col, "Set as default assistant", () -> {
            try {
                startActivity(new Intent(Settings.ACTION_VOICE_INPUT_SETTINGS));
            } catch (Exception e) {
                toast("Settings > Apps > Default apps > Digital assistant app");
            }
        });
        button(col, "Test now", () -> {
            save();
            startActivity(new Intent(this, VoiceActivity.class));
        });

        button(col, "Download wake-word model", () -> {
            save();
            status.setText("Starting download...");
            new Thread(() -> {
                try {
                    ModelStore.download(this, msg -> runOnUiThread(() -> status.setText(msg)));
                    runOnUiThread(this::refresh);
                } catch (Exception e) {
                    runOnUiThread(() -> status.setText("Download failed: " + e.getMessage()));
                }
            }).start();
        });
        button(col, "Allow notifications", () -> {
            if (Build.VERSION.SDK_INT >= 33) {
                requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, 3);
            } else {
                toast("Not needed on this Android version");
            }
        });
        button(col, "Don't restrict battery", () -> {
            try {
                startActivity(new Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                        Uri.parse("package:" + getPackageName())));
            } catch (Exception e) {
                toast("Settings > Battery > Riya > Unrestricted");
            }
        });
        button(col, "Start Hey Riya", () -> {
            save();
            if (!ModelStore.ready(this)) {
                toast("Download the wake-word model first");
                return;
            }
            if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
                toast("Grant microphone first");
                return;
            }
            RiyaClient.prefs(this).edit().putBoolean("wake_on", true).apply();
            if (WakeService.isRunning()) {  // already running: restart so new wake words apply
                startService(new Intent(this, WakeService.class).setAction(WakeService.ACTION_STOP));
                ui.postDelayed(() -> {
                    RiyaClient.prefs(this).edit().putBoolean("wake_on", true).apply();
                    startForegroundService(new Intent(this, WakeService.class));
                }, 1000);
            } else {
                startForegroundService(new Intent(this, WakeService.class));
            }
            ui.postDelayed(this::refresh, 1500);
        });
        button(col, "Stop Hey Riya", () -> {
            RiyaClient.prefs(this).edit().putBoolean("wake_on", false).apply();
            if (WakeService.isRunning()) {
                startService(new Intent(this, WakeService.class).setAction(WakeService.ACTION_STOP));
            }
            ui.postDelayed(this::refresh, 800);
        });

        ScrollView sv = new ScrollView(this);
        sv.addView(col, new ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        setContentView(sv);
    }

    private void refresh() {
        status.setText("Wake-word model: " + (ModelStore.ready(this) ? "ready" : "not downloaded")
                + "\nHey Riya: " + (WakeService.isRunning() ? "running" : "stopped"));
    }

    private EditText field(LinearLayout col, String hint, String key, String def, boolean secret) {
        EditText e = new EditText(this);
        e.setHint(hint);
        e.setSingleLine(true);
        if (secret) {
            e.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        }
        String v = RiyaClient.prefs(this).getString(key, "");
        e.setText(v == null || v.isEmpty() ? (secret ? "" : def) : v);
        col.addView(e);
        return e;
    }

    private void button(LinearLayout col, String label, Runnable r) {
        Button bt = new Button(this);
        bt.setText(label);
        bt.setOnClickListener(v -> r.run());
        col.addView(bt);
    }

    private void save() {
        RiyaClient.prefs(this).edit()
                .putString("token", token.getText().toString().trim())
                .putString("port", port.getText().toString().trim())
                .putString("owner", owner.getText().toString().trim())
                .putString("lang", lang.getText().toString().trim())
                .putString("dir", dir.getText().toString().trim())
                .putString("wake_words", wakeWords.getText().toString().trim())
                .putString("model_url", modelUrl.getText().toString().trim())
                .apply();
    }

    private void toast(String s) {
        Toast.makeText(this, s, Toast.LENGTH_SHORT).show();
    }

    @Override
    protected void onResume() {
        super.onResume();
        refresh();
    }

    @Override
    protected void onPause() {
        save();
        super.onPause();
    }
}
