package com.riya.assistant;

import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;

import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;

/** Talks to Riya's server running in Termux on this same phone (127.0.0.1 only). */
final class RiyaClient {
    static final String DEFAULT_DIR = "/data/data/com.termux/files/home/riya-assistant";

    private RiyaClient() {
    }

    static SharedPreferences prefs(Context c) {
        return c.getSharedPreferences("riya", Context.MODE_PRIVATE);
    }

    static String get(Context c, String key, String def) {
        String v = prefs(c).getString(key, def);
        return v == null || v.trim().isEmpty() ? def : v.trim();
    }

    private static String base(Context c) {
        return "http://127.0.0.1:" + get(c, "port", "8765");
    }

    static boolean ping(Context c) {
        try {
            HttpURLConnection h = (HttpURLConnection) new URL(base(c) + "/ping").openConnection();
            h.setConnectTimeout(1500);
            h.setReadTimeout(1500);
            return h.getResponseCode() == 200;
        } catch (Exception e) {
            return false;
        }
    }

    /** Sends one thing the owner said, returns Riya's reply. Blocks (tools and approvals can take a while). */
    static String ask(Context c, String text) throws Exception {
        HttpURLConnection h = (HttpURLConnection) new URL(base(c) + "/ask").openConnection();
        h.setRequestMethod("POST");
        h.setDoOutput(true);
        h.setConnectTimeout(3000);
        h.setReadTimeout(180000);
        h.setRequestProperty("Content-Type", "application/json");
        h.setRequestProperty("X-Riya-Key", get(c, "token", ""));
        byte[] body = new JSONObject().put("text", text).toString().getBytes("UTF-8");
        try (OutputStream o = h.getOutputStream()) {
            o.write(body);
        }
        int code = h.getResponseCode();
        if (code == 403) {
            throw new IOException("forbidden");
        }
        InputStream in = code < 400 ? h.getInputStream() : h.getErrorStream();
        return new JSONObject(readAll(in)).optString("reply", "");
    }

    private static String readAll(InputStream in) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buf = new byte[4096];
        int n;
        while (in != null && (n = in.read(buf)) > 0) {
            out.write(buf, 0, n);
        }
        return out.toString("UTF-8");
    }

    /** Asks Termux to start the server (needs "allow-external-apps = true" in termux.properties). */
    static boolean startServer(Context c) {
        try {
            String dir = get(c, "dir", DEFAULT_DIR);
            Intent i = new Intent("com.termux.RUN_COMMAND");
            i.setClassName("com.termux", "com.termux.app.RunCommandService");
            i.putExtra("com.termux.RUN_COMMAND_PATH", "/data/data/com.termux/files/usr/bin/sh");
            i.putExtra("com.termux.RUN_COMMAND_ARGUMENTS", new String[]{dir + "/server-bg.sh"});
            i.putExtra("com.termux.RUN_COMMAND_WORKDIR", dir);
            i.putExtra("com.termux.RUN_COMMAND_BACKGROUND", true);
            c.startForegroundService(i);
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    /** True once the server answers; tries to start it through Termux if it is down. */
    static boolean ensureServer(Context c) {
        if (ping(c)) {
            return true;
        }
        if (!startServer(c)) {
            return false;
        }
        for (int i = 0; i < 30; i++) {  // up to ~15 s
            try {
                Thread.sleep(500);
            } catch (InterruptedException e) {
                return false;
            }
            if (ping(c)) {
                return true;
            }
        }
        return false;
    }
}
