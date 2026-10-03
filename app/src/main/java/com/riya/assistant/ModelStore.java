package com.riya.assistant;

import android.content.Context;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/** Downloads and unpacks the small offline speech model that listens for "Hey Riya". */
final class ModelStore {
    static final String DEFAULT_URL = "https://alphacephei.com/vosk/models/vosk-model-small-en-in-0.4.zip";

    interface Progress {
        void update(String message);
    }

    private ModelStore() {
    }

    static File modelDir(Context c) {
        String p = RiyaClient.prefs(c).getString("model_path", "");
        return p == null || p.isEmpty() ? null : new File(p);
    }

    static boolean ready(Context c) {
        File d = modelDir(c);
        return d != null && new File(d, "am/final.mdl").exists();
    }

    /** Blocking: run on a background thread. */
    static void download(Context c, Progress progress) throws IOException {
        String url = RiyaClient.get(c, "model_url", DEFAULT_URL);
        File zip = new File(c.getCacheDir(), "model.zip");
        HttpURLConnection h = (HttpURLConnection) new URL(url).openConnection();
        h.setConnectTimeout(15000);
        h.setReadTimeout(30000);
        if (h.getResponseCode() != 200) {
            throw new IOException("HTTP " + h.getResponseCode());
        }
        long total = h.getContentLengthLong();
        long done = 0, lastReport = 0;
        try (InputStream in = h.getInputStream(); OutputStream out = new FileOutputStream(zip)) {
            byte[] buf = new byte[64 * 1024];
            int n;
            while ((n = in.read(buf)) > 0) {
                out.write(buf, 0, n);
                done += n;
                if (done - lastReport > 1024 * 1024) {
                    lastReport = done;
                    progress.update("Downloading model: " + (done >> 20) + (total > 0 ? " / " + (total >> 20) : "") + " MB");
                }
            }
        }
        progress.update("Unpacking model...");
        File root = new File(c.getFilesDir(), "vosk");
        deleteRecursively(root);
        root.mkdirs();
        unzip(zip, root);
        zip.delete();
        File model = find(root, 0);
        if (model == null) {
            throw new IOException("model files not found in the zip");
        }
        RiyaClient.prefs(c).edit().putString("model_path", model.getAbsolutePath()).apply();
        progress.update("Model ready.");
    }

    private static void unzip(File zip, File dest) throws IOException {
        String base = dest.getCanonicalPath() + File.separator;
        try (ZipInputStream z = new ZipInputStream(new java.io.BufferedInputStream(new java.io.FileInputStream(zip)))) {
            ZipEntry e;
            byte[] buf = new byte[64 * 1024];
            while ((e = z.getNextEntry()) != null) {
                File out = new File(dest, e.getName());
                if (!out.getCanonicalPath().startsWith(base)) {
                    throw new IOException("bad zip entry");  // zip-slip guard
                }
                if (e.isDirectory()) {
                    out.mkdirs();
                    continue;
                }
                out.getParentFile().mkdirs();
                try (OutputStream o = new FileOutputStream(out)) {
                    int n;
                    while ((n = z.read(buf)) > 0) {
                        o.write(buf, 0, n);
                    }
                }
            }
        }
    }

    private static File find(File dir, int depth) {
        if (new File(dir, "am/final.mdl").exists()) {
            return dir;
        }
        File[] kids = dir.listFiles();
        if (kids == null || depth > 3) {
            return null;
        }
        for (File k : kids) {
            if (k.isDirectory()) {
                File r = find(k, depth + 1);
                if (r != null) {
                    return r;
                }
            }
        }
        return null;
    }

    private static void deleteRecursively(File f) {
        File[] kids = f.listFiles();
        if (kids != null) {
            for (File k : kids) {
                deleteRecursively(k);
            }
        }
        f.delete();
    }
}
