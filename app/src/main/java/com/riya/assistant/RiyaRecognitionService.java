package com.riya.assistant;

import android.content.Intent;
import android.speech.RecognitionService;
import android.speech.SpeechRecognizer;

/** Android requires an assistant to declare one. Riya uses the phone's own recognizer, so this does nothing. */
public class RiyaRecognitionService extends RecognitionService {
    @Override
    protected void onStartListening(Intent recognizerIntent, Callback listener) {
        try {
            listener.error(SpeechRecognizer.ERROR_CLIENT);
        } catch (Exception ignored) {
        }
    }

    @Override
    protected void onCancel(Callback listener) {
    }

    @Override
    protected void onStopListening(Callback listener) {
    }
}
