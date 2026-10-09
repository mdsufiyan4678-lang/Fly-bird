package com.example;

import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

/**
 * MultiplayerManager coordinates two-player online multiplayer rooms, player readiness,
 * synchronized match start seeds, real-time opponent score & alive/eliminated states,
 * reconnect handling, and server-authoritative match results.
 */
public class MultiplayerManager implements WebSocketClient.Listener {
    private static final String TAG = "MultiplayerManager";

    public enum RoomState {
        DISCONNECTED,
        CONNECTING,
        CONNECTED_IDLE,
        WAITING_IN_ROOM,
        READY_TO_START,
        IN_MATCH,
        MATCH_FINISHED
    }

    public static class PlayerSlot {
        public String playerId = "";
        public String playerName = "";
        public int birdSkinId = 0;
        public boolean ready = false;
        public boolean connected = true;
        public int score = 0;
        public boolean alive = true;
        public float normalizedY = 0.5f;
    }

    public static class MatchResult {
        public String matchId = "";
        public String roomCode = "";
        public String winnerId = "";
        public String winnerName = "";
        public boolean isDraw = false;
        public String player1Name = "";
        public int player1Score = 0;
        public String player2Name = "";
        public int player2Score = 0;
        public int rewardCoins = 0;
        public String reason = "";
    }

    public interface MultiplayerListener {
        void onStateChanged(RoomState state, String statusText);
        void onRoomUpdated(String roomCode, List<PlayerSlot> players);
        void onMatchStart(String matchId, long obstacleSeed);
        void onOpponentUpdated(PlayerSlot opponent);
        void onMatchResult(MatchResult result);
        void onMultiplayerError(String code, String message);
    }

    private final GameManager gameManager;
    private final WebSocketClient wsClient;
    private final MultiplayerListener listener;
    private final Handler mainHandler;

    private RoomState currentState = RoomState.DISCONNECTED;
    private String currentRoomCode = "";
    private String currentMatchId = "";
    private String pendingAction = null; // "CREATE" or "JOIN" or "RECONNECT"
    private String pendingJoinCode = "";
    private final List<PlayerSlot> roomPlayers = new ArrayList<>();
    private MatchResult lastMatchResult = null;
    private String statusBanner = "Not connected to server";
    private int reconnectAttempts = 0;

    public MultiplayerManager(GameManager gameManager, MultiplayerListener listener) {
        this.gameManager = gameManager;
        this.listener = listener;
        this.mainHandler = new Handler(Looper.getMainLooper());
        this.wsClient = new WebSocketClient(this);
    }

    public RoomState getCurrentState() {
        return currentState;
    }

    public String getCurrentRoomCode() {
        return currentRoomCode;
    }

    public String getStatusBanner() {
        return statusBanner;
    }

    public List<PlayerSlot> getRoomPlayers() {
        return new ArrayList<>(roomPlayers);
    }

    public MatchResult getLastMatchResult() {
        return lastMatchResult;
    }

    public PlayerSlot getOpponentSlot() {
        String myId = gameManager.getClientSessionId();
        for (PlayerSlot slot : roomPlayers) {
            if (!slot.playerId.equals(myId)) {
                return slot;
            }
        }
        return null;
    }

    public PlayerSlot getMySlot() {
        String myId = gameManager.getClientSessionId();
        for (PlayerSlot slot : roomPlayers) {
            if (slot.playerId.equals(myId)) {
                return slot;
            }
        }
        return null;
    }

    /**
     * Connects to the configured WebSocket server and creates a new 2-player room.
     */
    public void createRoom() {
        pendingAction = "CREATE";
        pendingJoinCode = "";
        reconnectAttempts = 0;
        if (wsClient.isConnected()) {
            sendCreateRoomRequest();
        } else {
            updateState(RoomState.CONNECTING, "Connecting to " + gameManager.getWsServerUrl() + "...");
            wsClient.connect(gameManager.getWsServerUrl());
        }
    }

    /**
     * Validates the 6-digit room code and joins an existing room on the server.
     */
    public void joinRoom(String sixDigitCode) {
        if (sixDigitCode == null) {
            notifyError("INVALID_ROOM_CODE", "Please enter a 6-digit room code.");
            return;
        }
        String cleaned = sixDigitCode.trim();
        if (!cleaned.matches("^\\d{6}$")) {
            notifyError("INVALID_ROOM_CODE", "Room code must be exactly 6 digits (0-9).");
            return;
        }
        pendingAction = "JOIN";
        pendingJoinCode = cleaned;
        reconnectAttempts = 0;
        if (wsClient.isConnected()) {
            sendJoinRoomRequest(cleaned);
        } else {
            updateState(RoomState.CONNECTING, "Connecting to server to join room #" + cleaned + "...");
            wsClient.connect(gameManager.getWsServerUrl());
        }
    }

    /**
     * Toggles or sets player readiness in the room waiting screen.
     */
    public void setPlayerReady(boolean ready) {
        if (!wsClient.isConnected() || currentRoomCode.isEmpty()) {
            notifyError("NOT_IN_ROOM", "You are not currently in an active room.");
            return;
        }
        try {
            JSONObject msg = new JSONObject();
            msg.put("type", "SET_READY");
            msg.put("roomCode", currentRoomCode);
            msg.put("playerId", gameManager.getClientSessionId());
            msg.put("ready", ready);
            wsClient.sendJson(msg);
        } catch (Exception e) {
            Log.w(TAG, "Failed to send SET_READY: " + e.getMessage());
        }
    }

    /**
     * Sends real-time player score, normalized vertical bird position, and alive state.
     */
    public void sendScoreAndPositionUpdate(int score, int coins, float normalizedY, boolean alive, long elapsedMs) {
        if (!wsClient.isConnected() || currentRoomCode.isEmpty() || currentState != RoomState.IN_MATCH) {
            return;
        }
        try {
            JSONObject msg = new JSONObject();
            msg.put("type", "SCORE_UPDATE");
            msg.put("roomCode", currentRoomCode);
            msg.put("matchId", currentMatchId);
            msg.put("playerId", gameManager.getClientSessionId());
            msg.put("score", score);
            msg.put("coins", coins);
            msg.put("normalizedY", Math.max(0f, Math.min(1f, normalizedY)));
            msg.put("alive", alive);
            msg.put("elapsedMs", elapsedMs);
            wsClient.sendJson(msg);
        } catch (Exception e) {
            Log.w(TAG, "Error sending SCORE_UPDATE: " + e.getMessage());
        }
    }

    /**
     * Notifies the authoritative server that the local player collided with an obstacle.
     */
    public void sendPlayerEliminated(int finalScore, int coins, long elapsedMs) {
        if (!wsClient.isConnected() || currentRoomCode.isEmpty()) {
            return;
        }
        try {
            JSONObject msg = new JSONObject();
            msg.put("type", "PLAYER_ELIMINATED");
            msg.put("roomCode", currentRoomCode);
            msg.put("matchId", currentMatchId);
            msg.put("playerId", gameManager.getClientSessionId());
            msg.put("finalScore", finalScore);
            msg.put("coins", coins);
            msg.put("elapsedMs", elapsedMs);
            wsClient.sendJson(msg);
        } catch (Exception e) {
            Log.w(TAG, "Error sending PLAYER_ELIMINATED: " + e.getMessage());
        }
    }

    /**
     * Requests a rematch in the current room after a match concludes.
     */
    public void requestPlayAgain() {
        if (!wsClient.isConnected() || currentRoomCode.isEmpty()) {
            return;
        }
        try {
            JSONObject msg = new JSONObject();
            msg.put("type", "PLAY_AGAIN");
            msg.put("roomCode", currentRoomCode);
            msg.put("playerId", gameManager.getClientSessionId());
            wsClient.sendJson(msg);
        } catch (Exception e) {
            Log.w(TAG, "Error sending PLAY_AGAIN: " + e.getMessage());
        }
    }

    /**
     * Leaves the active room and returns to the multiplayer lobby.
     */
    public void leaveRoom() {
        if (wsClient.isConnected() && !currentRoomCode.isEmpty()) {
            try {
                JSONObject msg = new JSONObject();
                msg.put("type", "LEAVE_ROOM");
                msg.put("roomCode", currentRoomCode);
                msg.put("playerId", gameManager.getClientSessionId());
                wsClient.sendJson(msg);
            } catch (Exception ignored) {
            }
        }
        currentRoomCode = "";
        currentMatchId = "";
        pendingAction = null;
        roomPlayers.clear();
        lastMatchResult = null;
        wsClient.disconnect();
        updateState(RoomState.DISCONNECTED, "Returned to Multiplayer Lobby");
    }

    /**
     * Attempts to reconnect to an active room if a transient connection drop occurred.
     */
    public void attemptReconnect() {
        if (currentRoomCode.isEmpty()) {
            return;
        }
        pendingAction = "RECONNECT";
        updateState(RoomState.CONNECTING, "Reconnecting to room #" + currentRoomCode + "...");
        wsClient.connect(gameManager.getWsServerUrl());
    }

    private void sendCreateRoomRequest() {
        try {
            JSONObject msg = new JSONObject();
            msg.put("type", "CREATE_ROOM");
            msg.put("playerId", gameManager.getClientSessionId());
            msg.put("playerName", gameManager.getPlayerName());
            msg.put("birdSkinId", gameManager.getSelectedBirdId());
            wsClient.sendJson(msg);
        } catch (Exception e) {
            notifyError("CLIENT_ERROR", "Failed to create room request: " + e.getMessage());
        }
    }

    private void sendJoinRoomRequest(String code) {
        try {
            JSONObject msg = new JSONObject();
            msg.put("type", "JOIN_ROOM");
            msg.put("roomCode", code);
            msg.put("playerId", gameManager.getClientSessionId());
            msg.put("playerName", gameManager.getPlayerName());
            msg.put("birdSkinId", gameManager.getSelectedBirdId());
            wsClient.sendJson(msg);
        } catch (Exception e) {
            notifyError("CLIENT_ERROR", "Failed to send join request: " + e.getMessage());
        }
    }

    private void sendReconnectRoomRequest() {
        try {
            JSONObject msg = new JSONObject();
            msg.put("type", "RECONNECT_ROOM");
            msg.put("roomCode", currentRoomCode);
            msg.put("playerId", gameManager.getClientSessionId());
            msg.put("playerName", gameManager.getPlayerName());
            msg.put("birdSkinId", gameManager.getSelectedBirdId());
            wsClient.sendJson(msg);
        } catch (Exception e) {
            notifyError("CLIENT_ERROR", "Failed to send reconnect request: " + e.getMessage());
        }
    }

    @Override
    public void onConnected() {
        reconnectAttempts = 0;
        if ("CREATE".equals(pendingAction)) {
            pendingAction = null;
            sendCreateRoomRequest();
        } else if ("JOIN".equals(pendingAction)) {
            pendingAction = null;
            sendJoinRoomRequest(pendingJoinCode);
        } else if ("RECONNECT".equals(pendingAction) && !currentRoomCode.isEmpty()) {
            pendingAction = null;
            sendReconnectRoomRequest();
        } else {
            updateState(RoomState.CONNECTED_IDLE, "Connected to server");
        }
    }

    @Override
    public void onMessageReceived(JSONObject message) {
        String type = message.optString("type", "");
        switch (type) {
            case "ROOM_CREATED":
            case "ROOM_UPDATE": {
                currentRoomCode = message.optString("roomCode", currentRoomCode);
                String roomStatus = message.optString("status", "WAITING");
                parsePlayersArray(message.optJSONArray("players"));

                if ("WAITING".equals(roomStatus)) {
                    if (roomPlayers.size() < 2) {
                        updateState(RoomState.WAITING_IN_ROOM,
                                "Waiting for Opponent... Share Room Code: " + currentRoomCode);
                    } else {
                        updateState(RoomState.READY_TO_START,
                                "Both players connected! Tap READY to launch match.");
                    }
                } else if ("READY".equals(roomStatus)) {
                    updateState(RoomState.READY_TO_START,
                            "Both players connected! Tap READY to launch match.");
                }
                if (listener != null) {
                    listener.onRoomUpdated(currentRoomCode, getRoomPlayers());
                }
                break;
            }
            case "MATCH_START": {
                currentRoomCode = message.optString("roomCode", currentRoomCode);
                currentMatchId = message.optString("matchId", "m_" + System.currentTimeMillis());
                long seed = message.optLong("seed", System.currentTimeMillis());
                parsePlayersArray(message.optJSONArray("players"));
                for (PlayerSlot slot : roomPlayers) {
                    slot.score = 0;
                    slot.alive = true;
                }
                updateState(RoomState.IN_MATCH, "LIVE 2P MATCH • ROOM #" + currentRoomCode);
                if (listener != null) {
                    listener.onMatchStart(currentMatchId, seed);
                }
                break;
            }
            case "OPPONENT_STATE": {
                String pId = message.optString("playerId", "");
                int score = message.optInt("score", 0);
                boolean alive = message.optBoolean("alive", true);
                float normY = (float) message.optDouble("normalizedY", 0.5);
                for (PlayerSlot slot : roomPlayers) {
                    if (slot.playerId.equals(pId)) {
                        slot.score = score;
                        slot.alive = alive;
                        slot.normalizedY = normY;
                        if (listener != null) {
                            listener.onOpponentUpdated(slot);
                        }
                        break;
                    }
                }
                break;
            }
            case "MATCH_OVER": {
                MatchResult result = new MatchResult();
                result.matchId = message.optString("matchId", currentMatchId);
                result.roomCode = message.optString("roomCode", currentRoomCode);
                result.winnerId = message.optString("winnerId", "");
                result.winnerName = message.optString("winnerName", "Draw");
                result.isDraw = message.optBoolean("isDraw", false);
                result.player1Name = message.optString("p1Name", "Player 1");
                result.player1Score = message.optInt("p1Score", 0);
                result.player2Name = message.optString("p2Name", "Player 2");
                result.player2Score = message.optInt("p2Score", 0);
                result.rewardCoins = message.optInt("rewardCoins", 0);
                result.reason = message.optString("reason", "Match completed");

                String myId = gameManager.getClientSessionId();
                int myReward = result.isDraw ? 10 : (myId.equals(result.winnerId) ? result.rewardCoins : 5);
                result.rewardCoins = myReward;
                gameManager.claimServerValidatedMatchReward(result.matchId, myReward);

                lastMatchResult = result;
                updateState(RoomState.MATCH_FINISHED, "Match Complete! Winner: " + result.winnerName);
                if (listener != null) {
                    listener.onMatchResult(result);
                }
                break;
            }
            case "ERROR": {
                String code = message.optString("code", "SERVER_ERROR");
                String errMessage = message.optString("message", "Server rejected request.");
                notifyError(code, errMessage);
                break;
            }
            default:
                break;
        }
    }

    private void parsePlayersArray(JSONArray arr) {
        if (arr == null) {
            return;
        }
        roomPlayers.clear();
        for (int i = 0; i < arr.length(); i++) {
            JSONObject obj = arr.optJSONObject(i);
            if (obj == null) continue;
            PlayerSlot slot = new PlayerSlot();
            slot.playerId = obj.optString("playerId", "");
            slot.playerName = obj.optString("playerName", "Player " + (i + 1));
            slot.birdSkinId = obj.optInt("birdSkinId", 0);
            slot.ready = obj.optBoolean("ready", false);
            slot.connected = obj.optBoolean("connected", true);
            slot.score = obj.optInt("score", 0);
            slot.alive = obj.optBoolean("alive", true);
            slot.normalizedY = (float) obj.optDouble("normalizedY", 0.5);
            roomPlayers.add(slot);
        }
    }

    @Override
    public void onDisconnected(int code, String reason, boolean remote) {
        if (!currentRoomCode.isEmpty() && reconnectAttempts < 2 && remote) {
            reconnectAttempts++;
            updateState(RoomState.CONNECTING, "Connection lost. Reconnecting (" + reconnectAttempts + "/2)...");
            mainHandler.postDelayed(this::attemptReconnect, 1500);
        } else {
            updateState(RoomState.DISCONNECTED, "Disconnected from server");
        }
    }

    @Override
    public void onError(String errorMessage) {
        updateState(RoomState.DISCONNECTED, errorMessage);
        notifyError("CONNECTION_ERROR", errorMessage);
    }

    private void updateState(RoomState newState, String bannerText) {
        this.currentState = newState;
        this.statusBanner = bannerText;
        if (listener != null) {
            listener.onStateChanged(newState, bannerText);
        }
    }

    private void notifyError(String code, String message) {
        this.statusBanner = message;
        if (listener != null) {
            listener.onMultiplayerError(code, message);
        }
    }
}
