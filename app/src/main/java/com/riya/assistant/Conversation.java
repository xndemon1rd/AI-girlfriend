package com.riya.assistant;

import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.speech.RecognitionListener;
import android.speech.RecognizerIntent;
import android.speech.SpeechRecognizer;
import android.speech.tts.TextToSpeech;
import android.speech.tts.UtteranceProgressListener;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * One hands-free chat with Riya: listen -> ask her server -> speak, for a few turns.
 * Used by the panel (VoiceActivity) and by the "Hey Riya" background service. All callbacks run on the main thread.
 */
final class Conversation implements RecognitionListener, TextToSpeech.OnInitListener {
    interface Ui {
        void status(String s);

        void said(String s);

        void reply(String s);

        /** The conversation ended by itself (bye, silence, error, too many turns). */
        void done();
    }

    private static final int MAX_TURNS = 12;
    private static final Set<String> BYE = new HashSet<>(Arrays.asList("bye", "goodbye", "alvida", "exit", "bas"));

    private final Context ctx;
    private final Ui ui;
    private final String owner;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final ExecutorService io = Executors.newSingleThreadExecutor();
    private final Map<String, Runnable> afterSpeech = new HashMap<>();

    private SpeechRecognizer recognizer;
    private TextToSpeech tts;
    private boolean ttsReady, closed;
    private int turns, utterance;

    Conversation(Context ctx, Ui ui) {
        this.ctx = ctx;
        this.ui = ui;
        this.owner = RiyaClient.get(ctx, "owner", "Boss");
    }

    // ---------------- public ----------------
    void start() {
        ui.status("Connecting...");
        tts = new TextToSpeech(ctx, this);
        io.execute(() -> {
            boolean up = RiyaClient.ensureServer(ctx);
            main.post(() -> {
                if (closed) {
                    return;
                }
                if (RiyaClient.get(ctx, "token", "").isEmpty()) {
                    fail("Token set nahi hai. Riya app kholkar token paste kijiye.");
                } else if (!up) {
                    fail("Riya ka server band hai. Termux mein 'sh run.sh' chalaiye.");
                } else {
                    speak("Ji " + owner + ", boliye?", this::listen);
                }
            });
        });
    }

    /** "Tap to speak": cut off whatever she is saying and listen. */
    void listenNow() {
        if (tts != null) {
            tts.stop();
        }
        afterSpeech.clear();
        listen();
    }

    /** Release the mic and the voice. Does not call ui.done(). Safe to call twice. */
    void stop() {
        if (closed) {
            return;
        }
        closed = true;
        if (recognizer != null) {
            recognizer.destroy();
            recognizer = null;
        }
        if (tts != null) {
            tts.stop();
            tts.shutdown();
            tts = null;
        }
        io.shutdownNow();
    }

    // ---------------- flow ----------------
    private void finish() {
        if (closed) {
            return;
        }
        stop();
        ui.done();
    }

    private void fail(String msg) {
        speak(msg, this::finish);
    }

    private void listen() {
        if (closed) {
            return;
        }
        if (!SpeechRecognizer.isRecognitionAvailable(ctx)) {
            fail("Is phone par speech recognition available nahi hai.");
            return;
        }
        if (recognizer == null) {
            recognizer = SpeechRecognizer.createSpeechRecognizer(ctx);
            recognizer.setRecognitionListener(this);
        }
        Intent i = new Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH);
        i.putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM);
        i.putExtra(RecognizerIntent.EXTRA_LANGUAGE, RiyaClient.get(ctx, "lang", "en-IN"));
        i.putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true);
        i.putExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE, ctx.getPackageName());
        ui.status("Listening...");
        recognizer.startListening(i);
    }

    private void handleUser(String text) {
        String[] words = text.trim().toLowerCase(Locale.ROOT).split("\\s+");
        if (words.length <= 3 && (BYE.contains(words[0]) || BYE.contains(words[words.length - 1]))) {
            speak("Theek hai " + owner + ", take care.", this::finish);
            return;
        }
        ui.said(text);
        ui.status("Thinking...");
        io.execute(() -> {
            String answer;
            try {
                answer = RiyaClient.ask(ctx, text);
            } catch (Exception e) {
                answer = "forbidden".equals(e.getMessage())
                        ? "Token galat hai. Riya app mein dobara paste kijiye."
                        : "Sorry yaar, abhi connect nahi ho paayi.";
            }
            final String out = answer.isEmpty() ? "Ho gaya!" : answer;
            main.post(() -> {
                if (closed) {
                    return;
                }
                turns++;
                speak(out, turns < MAX_TURNS ? this::listen : this::finish);
            });
        });
    }

    // ---------------- speaking ----------------
    @Override
    public void onInit(int s) {
        ttsReady = s == TextToSpeech.SUCCESS;
        if (!ttsReady || tts == null) {
            return;
        }
        tts.setLanguage(new Locale("en", "IN"));
        tts.setPitch(1.25f);       // softer, girlier
        tts.setSpeechRate(0.95f);
        tts.setOnUtteranceProgressListener(new UtteranceProgressListener() {
            @Override
            public void onStart(String id) {
            }

            @Override
            public void onDone(String id) {
                main.post(() -> runAfter(id));
            }

            @Override
            public void onError(String id) {
                main.post(() -> runAfter(id));
            }
        });
    }

    private void runAfter(String id) {
        Runnable r = afterSpeech.remove(id);
        if (r != null && !closed) {
            r.run();
        }
    }

    private void speak(String text, Runnable then) {
        speakTry(text, then, 0);
    }

    private void speakTry(String text, Runnable then, int tries) {
        if (closed) {
            return;
        }
        ui.reply(text);
        ui.status("Speaking...");
        if (ttsReady && tts != null) {
            String id = "u" + (utterance++);
            afterSpeech.put(id, then);
            tts.speak(text, TextToSpeech.QUEUE_FLUSH, null, id);
        } else if (tts != null && tries < 10) {  // engine still starting: wait a moment
            main.postDelayed(() -> speakTry(text, then, tries + 1), 300);
        } else {  // no text-to-speech on this phone: carry on silently
            main.post(then);
        }
    }

    // ---------------- RecognitionListener ----------------
    @Override
    public void onResults(Bundle results) {
        ArrayList<String> list = results.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION);
        if (list == null || list.isEmpty() || list.get(0).trim().isEmpty()) {
            speak("Theek hai, jab zaroorat ho bula lijiyega.", this::finish);
        } else {
            handleUser(list.get(0));
        }
    }

    @Override
    public void onPartialResults(Bundle partial) {
        ArrayList<String> list = partial.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION);
        if (list != null && !list.isEmpty()) {
            ui.said(list.get(0));
        }
    }

    @Override
    public void onError(int error) {
        if (closed) {
            return;
        }
        if (error == SpeechRecognizer.ERROR_NO_MATCH || error == SpeechRecognizer.ERROR_SPEECH_TIMEOUT) {
            speak("Theek hai, jab zaroorat ho bula lijiyega.", this::finish);
        } else if (error == SpeechRecognizer.ERROR_RECOGNIZER_BUSY) {
            main.postDelayed(this::listen, 600);
        } else {
            ui.status("Sunai nahi diya (code " + error + ")");
            main.postDelayed(this::finish, 2500);
        }
    }

    @Override public void onReadyForSpeech(Bundle p) { }
    @Override public void onBeginningOfSpeech() { }
    @Override public void onRmsChanged(float rms) { }
    @Override public void onBufferReceived(byte[] buf) { }
    @Override public void onEndOfSpeech() { }
    @Override public void onEvent(int type, Bundle params) { }
}
