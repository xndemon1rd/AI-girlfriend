package com.riya.assistant;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

/** Brings "Hey Riya" back after a reboot, if it was switched on. */
public class BootReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context c, Intent i) {
        if (!Intent.ACTION_BOOT_COMPLETED.equals(i.getAction())) {
            return;
        }
        if (!RiyaClient.prefs(c).getBoolean("wake_on", false) || !ModelStore.ready(c)) {
            return;
        }
        try {
            c.startForegroundService(new Intent(c, WakeService.class));
        } catch (Exception ignored) {  // newer Android versions refuse mic services at boot: open the app once
        }
    }
}
