package com.riya.assistant;

import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.service.voice.VoiceInteractionSession;

/** Called by Android when the assistant gesture happens: just open Riya's panel. */
public class RiyaSession extends VoiceInteractionSession {
    public RiyaSession(Context context) {
        super(context);
    }

    @Override
    public void onShow(Bundle args, int showFlags) {
        super.onShow(args, showFlags);
        Intent i = new Intent(getContext(), VoiceActivity.class);
        startAssistantActivity(i);
        hide();
    }
}
