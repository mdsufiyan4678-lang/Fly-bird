package com.example;

import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import org.json.JSONObject;

import java.util.concurrent.TimeUnit;

import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.WebSocket;
import okhttp3.WebSocketListener;

/**
 * Lightweight custom Java WebSocket client built on OkHttp.
 * Dispatches all lifecycle and JSON message callbacks onto the Android Main UI thread,
 * supports automatic ping/pong heartbeats, and handles reconnection gracefully.
 */
public class WebSocketClient {
    private static final String TAG = "WebSocketClient";

    public interface Listener {
        void onConnected();
        void onMessageReceived(JSONObject message);
        void onDisconnected(int code, String reason, boolean remote);
        void onError(String errorMessage);
    }

    private final OkHttpClient httpClient;
    private final Handler mainHandler;
    private final Listener listener;

    private WebSocket webSocket;
    private String currentServerUrl;
    private boolean isConnected = false;
    private boolean isConnecting = false;

    public WebSocketClient(Listener listener) {
        this.listener = listener;
        this.mainHandler = new Handler(Looper.getMainLooper());
        this.httpClient = new OkHttpClient.Builder()
                .connectTimeout(8, TimeUnit.SECONDS)
                .readTimeout(0, TimeUnit.MILLISECONDS)
                .pingInterval(15, TimeUnit.SECONDS)
                .retryOnConnectionFailure(true)
                .build();
    }

    public synchronized void connect(String serverUrl) {
        if (serverUrl == null || serverUrl.trim().isEmpty()) {
            postError("Server URL is empty. Configure your wss:// server address.");
            return;
        }
        String trimmed = serverUrl.trim();
        if (trimmed.contains("YOUR_SERVER_DOMAIN")) {
            postError("Server URL is set to placeholder (wss://YOUR_SERVER_DOMAIN). Tap 'Server Config' to enter your deployed Node.js server URL.");
            return;
        }
        if (!trimmed.startsWith("ws://") && !trimmed.startsWith("wss://")) {
            postError("Invalid WebSocket scheme. URL must start with wss:// or ws://");
            return;
        }

        if (isConnected || isConnecting) {
            disconnect();
        }

        this.currentServerUrl = trimmed;
        this.isConnecting = true;

        try {
            Request request = new Request.Builder()
                    .url(trimmed)
                    .build();

            webSocket = httpClient.newWebSocket(request, new WebSocketListener() {
                @Override
                public void onOpen(@NonNull WebSocket ws, @NonNull Response response) {
                    synchronized (WebSocketClient.this) {
                        isConnected = true;
                        isConnecting = false;
                    }
                    Log.d(TAG, "WebSocket connected to " + currentServerUrl);
                    mainHandler.post(() -> {
                        if (listener != null) {
                            listener.onConnected();
                        }
                    });
                }

                @Override
                public void onMessage(@NonNull WebSocket ws, @NonNull String text) {
                    try {
                        JSONObject json = new JSONObject(text);
                        mainHandler.post(() -> {
                            if (listener != null) {
                                listener.onMessageReceived(json);
                            }
                        });
                    } catch (Exception e) {
                        Log.w(TAG, "Malformed JSON from server: " + e.getMessage());
                    }
                }

                @Override
                public void onClosing(@NonNull WebSocket ws, int code, @NonNull String reason) {
                    ws.close(1000, null);
                }

                @Override
                public void onClosed(@NonNull WebSocket ws, int code, @NonNull String reason) {
                    synchronized (WebSocketClient.this) {
                        isConnected = false;
                        isConnecting = false;
                        webSocket = null;
                    }
                    mainHandler.post(() -> {
                        if (listener != null) {
                            listener.onDisconnected(code, reason, true);
                        }
                    });
                }

                @Override
                public void onFailure(@NonNull WebSocket ws, @NonNull Throwable t, @Nullable Response response) {
                    synchronized (WebSocketClient.this) {
                        isConnected = false;
                        isConnecting = false;
                        webSocket = null;
                    }
                    String msg = t.getMessage() != null ? t.getMessage() : "Connection failed";
                    Log.w(TAG, "WebSocket failure: " + msg);
                    mainHandler.post(() -> {
                        if (listener != null) {
                            listener.onError("Unable to reach multiplayer server (" + currentServerUrl + "): " + msg);
                        }
                    });
                }
            });
        } catch (Exception e) {
            isConnecting = false;
            postError("Invalid server URL: " + e.getMessage());
        }
    }

    public synchronized boolean sendJson(JSONObject payload) {
        if (!isConnected || webSocket == null || payload == null) {
            return false;
        }
        try {
            return webSocket.send(payload.toString());
        } catch (Exception e) {
            Log.w(TAG, "Error sending WebSocket message: " + e.getMessage());
            return false;
        }
    }

    public synchronized void disconnect() {
        isConnected = false;
        isConnecting = false;
        if (webSocket != null) {
            try {
                webSocket.close(1000, "Client leaving");
            } catch (Exception ignored) {
            }
            webSocket = null;
        }
    }

    public synchronized boolean isConnected() {
        return isConnected;
    }

    public synchronized boolean isConnecting() {
        return isConnecting;
    }

    private void postError(String message) {
        mainHandler.post(() -> {
            if (listener != null) {
                listener.onError(message);
            }
        });
    }
}
