package com.riya.assistant;

import android.Manifest;
import android.app.Activity;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.view.Gravity;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

/** The Gemini-style panel shown when you hold home / the power button. The chat logic lives in Conversation. */
public class VoiceActivity extends Activity implements Conversation.Ui {
    private static final int REQ_MIC = 7;

    private TextView status, said, reply;
    private Conversation chat;
    private boolean awaitingPermission, paused;

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        buildUi();
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            awaitingPermission = true;
            requestPermissions(new String[]{Manifest.permission.RECORD_AUDIO}, REQ_MIC);
        } else {
            begin();
        }
    }

    private void begin() {
        WakeService.pauseListening();  // "Hey Riya" must let go of the mic while the panel is open
        paused = true;
        chat = new Conversation(this, this);
        chat.start();
    }

    @Override
    public void onRequestPermissionsResult(int code, String[] perms, int[] results) {
        awaitingPermission = false;
        if (code == REQ_MIC && results.length > 0 && results[0] == PackageManager.PERMISSION_GRANTED) {
            begin();
        } else {
            status.setText("Mic permission chahiye");
            reply.setText("Riya app kholkar Grant microphone dabaiye.");
        }
    }

    // ---------------- Conversation.Ui ----------------
    @Override
    public void status(String s) {
        status.setText(s);
    }

    @Override
    public void said(String s) {
        said.setText(s);
    }

    @Override
    public void reply(String s) {
        reply.setText(s);
    }

    @Override
    public void done() {
        finish();
    }

    // ---------------- UI ----------------
    private int dp(int v) {
        return (int) (v * getResources().getDisplayMetrics().density);
    }

    private TextView text(int sp, String color, boolean bold) {
        TextView t = new TextView(this);
        t.setTextSize(sp);
        t.setTextColor(Color.parseColor(color));
        if (bold) {
            t.setTypeface(t.getTypeface(), Typeface.BOLD);
        }
        return t;
    }

    private void buildUi() {
        FrameLayout root = new FrameLayout(this);
        root.setOnClickListener(v -> closePanel());  // tap outside the panel closes it

        LinearLayout panel = new LinearLayout(this);
        panel.setOrientation(LinearLayout.VERTICAL);
        panel.setPadding(dp(20), dp(18), dp(20), dp(28));
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(Color.parseColor("#1c1620"));
        bg.setCornerRadii(new float[]{dp(24), dp(24), dp(24), dp(24), 0, 0, 0, 0});
        panel.setBackground(bg);
        panel.setOnClickListener(v -> { });  // swallow taps inside the panel

        status = text(18, "#ff9ec0", true);
        said = text(14, "#9a8fa3", false);
        reply = text(16, "#ffffff", false);
        reply.setPadding(0, dp(8), 0, dp(12));

        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        Button mic = new Button(this);
        mic.setText("Tap to speak");
        mic.setOnClickListener(v -> {
            if (chat != null) {
                chat.listenNow();
            }
        });
        Button close = new Button(this);
        close.setText("Close");
        close.setOnClickListener(v -> closePanel());
        row.addView(mic, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        row.addView(close, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));

        panel.addView(status);
        panel.addView(said);
        panel.addView(reply);
        panel.addView(row);

        root.addView(panel, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM));
        setContentView(root);
        status.setText("Riya...");
    }

    // ---------------- lifecycle ----------------
    private void closePanel() {
        if (chat != null) {
            chat.stop();
        }
        finish();
    }

    @Override
    protected void onStop() {
        super.onStop();
        if (!awaitingPermission) {
            closePanel();  // never keep the mic open in the background
        }
    }

    @Override
    protected void onDestroy() {
        if (chat != null) {
            chat.stop();
        }
        if (paused) {
            WakeService.resumeListening();
        }
        super.onDestroy();
    }
}
