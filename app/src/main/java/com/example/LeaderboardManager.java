package com.example;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import androidx.annotation.NonNull;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.IOException;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.TimeUnit;

import okhttp3.Call;
import okhttp3.Callback;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;

/**
 * LeaderboardManager handles:
 * 1. Local Best-Score Leaderboard persisted in SharedPreferences.
 * 2. Online Global Leaderboard & Match History via the standalone Node.js server REST API,
 *    including server-side score validation payload parameters (score, coins, durationMs).
 */
public class LeaderboardManager {
    private static final String TAG = "LeaderboardManager";
    private static final String PREFS_NAME = "fly_bird_leaderboard_prefs";
    private static final String KEY_LOCAL_ENTRIES = "local_leaderboard_json";
    private static final int MAX_LOCAL_ENTRIES = 15;
    private static final MediaType JSON_MEDIA_TYPE = MediaType.get("application/json; charset=utf-8");

    public static class ScoreEntry {
        public int rank;
        public String playerName;
        public int score;
        public int coins;
        public String birdName;
        public String dateLabel;

        public ScoreEntry(int rank, String playerName, int score, int coins, String birdName, String dateLabel) {
            this.rank = rank;
            this.playerName = playerName;
            this.score = score;
            this.coins = coins;
            this.birdName = birdName;
            this.dateLabel = dateLabel;
        }
    }

    public static class OnlineMatchEntry {
        public String winnerName;
        public String player1Name;
        public int player1Score;
        public String player2Name;
        public int player2Score;
        public String playedAt;

        public OnlineMatchEntry(String winnerName, String player1Name, int player1Score,
                                String player2Name, int player2Score, String playedAt) {
            this.winnerName = winnerName;
            this.player1Name = player1Name;
            this.player1Score = player1Score;
            this.player2Name = player2Name;
            this.player2Score = player2Score;
            this.playedAt = playedAt;
        }
    }

    public interface OnlineFetchCallback {
        void onOnlineDataLoaded(List<ScoreEntry> globalScores, List<OnlineMatchEntry> recentMatches, String statusMessage);
        void onOnlineDataError(String errorMessage);
    }

    private final SharedPreferences prefs;
    private final GameManager gameManager;
    private final OkHttpClient httpClient;
    private final Handler mainHandler;

    private final List<ScoreEntry> localScores = new ArrayList<>();
    private final List<ScoreEntry> cachedOnlineScores = new ArrayList<>();
    private final List<OnlineMatchEntry> cachedOnlineMatches = new ArrayList<>();
    private String onlineStatusMessage = "Connect a deployed Node.js server to view Online Global Rankings.";

    public LeaderboardManager(Context context, GameManager gameManager) {
        this.prefs = context.getApplicationContext().getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        this.gameManager = gameManager;
        this.mainHandler = new Handler(Looper.getMainLooper());
        this.httpClient = new OkHttpClient.Builder()
                .connectTimeout(6, TimeUnit.SECONDS)
                .readTimeout(6, TimeUnit.SECONDS)
                .build();
        loadLocalScores();
    }

    private void loadLocalScores() {
        localScores.clear();
        String raw = prefs.getString(KEY_LOCAL_ENTRIES, "[]");
        try {
            JSONArray array = new JSONArray(raw);
            for (int i = 0; i < array.length(); i++) {
                JSONObject obj = array.getJSONObject(i);
                localScores.add(new ScoreEntry(
                        i + 1,
                        obj.optString("playerName", "Pilot"),
                        obj.optInt("score", 0),
                        obj.optInt("coins", 0),
                        obj.optString("birdName", "Blue Bird"),
                        obj.optString("dateLabel", "Today")
                ));
            }
            sortAndRankLocal();
        } catch (Exception e) {
            Log.w(TAG, "Failed to parse local scores: " + e.getMessage());
        }
    }

    private void sortAndRankLocal() {
        Collections.sort(localScores, (a, b) -> Integer.compare(b.score, a.score));
        while (localScores.size() > MAX_LOCAL_ENTRIES) {
            localScores.remove(localScores.size() - 1);
        }
        for (int i = 0; i < localScores.size(); i++) {
            localScores.get(i).rank = i + 1;
        }
    }

    private void saveLocalScores() {
        try {
            JSONArray array = new JSONArray();
            for (ScoreEntry entry : localScores) {
                JSONObject obj = new JSONObject();
                obj.put("playerName", entry.playerName);
                obj.put("score", entry.score);
                obj.put("coins", entry.coins);
                obj.put("birdName", entry.birdName);
                obj.put("dateLabel", entry.dateLabel);
                array.put(obj);
            }
            prefs.edit().putString(KEY_LOCAL_ENTRIES, array.toString()).apply();
        } catch (Exception e) {
            Log.w(TAG, "Failed to save local scores: " + e.getMessage());
        }
    }

    /**
     * Records a score in the local best-score leaderboard and optionally submits it to the
     * online Node.js server for server-side validation and database storage.
     */
    public synchronized void recordScore(int score, int coins, long durationMs) {
        if (score <= 0) {
            return;
        }
        String dateStr = new SimpleDateFormat("MMM dd, HH:mm", Locale.US).format(new Date());
        String birdName = gameManager.getSelectedBirdSkin().name;
        localScores.add(new ScoreEntry(
                localScores.size() + 1,
                gameManager.getPlayerName(),
                score,
                coins,
                birdName,
                dateStr
        ));
        sortAndRankLocal();
        saveLocalScores();

        if (gameManager.isServerConfigured()) {
            submitScoreOnline(score, coins, durationMs, birdName);
        }
    }

    public synchronized List<ScoreEntry> getLocalScores() {
        return new ArrayList<>(localScores);
    }

    public synchronized List<ScoreEntry> getCachedOnlineScores() {
        return new ArrayList<>(cachedOnlineScores);
    }

    public synchronized List<OnlineMatchEntry> getCachedOnlineMatches() {
        return new ArrayList<>(cachedOnlineMatches);
    }

    public String getOnlineStatusMessage() {
        return onlineStatusMessage;
    }

    /**
     * Submits a run to POST /api/leaderboard on the standalone Node.js server.
     * The server validates score rate vs. durationMs before storing in SQLite/PostgreSQL.
     */
    public void submitScoreOnline(int score, int coins, long durationMs, String birdName) {
        String baseUrl = resolveHttpBaseUrl();
        if (baseUrl == null) {
            return;
        }
        try {
            JSONObject body = new JSONObject();
            body.put("playerId", gameManager.getClientSessionId());
            body.put("playerName", gameManager.getPlayerName());
            body.put("score", score);
            body.put("coins", coins);
            body.put("durationMs", durationMs);
            body.put("birdName", birdName);

            Request request = new Request.Builder()
                    .url(baseUrl + "/api/leaderboard")
                    .post(RequestBody.create(body.toString(), JSON_MEDIA_TYPE))
                    .build();

            httpClient.newCall(request).enqueue(new Callback() {
                @Override
                public void onFailure(@NonNull Call call, @NonNull IOException e) {
                    Log.d(TAG, "Online score submission failed: " + e.getMessage());
                }

                @Override
                public void onResponse(@NonNull Call call, @NonNull Response response) {
                    response.close();
                }
            });
        } catch (Exception e) {
            Log.w(TAG, "Error building score submission request: " + e.getMessage());
        }
    }

    /**
     * Fetches the global top scores and recent 2P match results from GET /api/leaderboard.
     * Never fakes online data if the server is unconfigured or offline.
     */
    public void fetchOnlineLeaderboard(OnlineFetchCallback callback) {
        String baseUrl = resolveHttpBaseUrl();
        if (baseUrl == null) {
            onlineStatusMessage = "Online Leaderboard requires a deployed Node.js server. Tap 'Server Config' to set your server URL.";
            if (callback != null) {
                callback.onOnlineDataError(onlineStatusMessage);
            }
            return;
        }

        onlineStatusMessage = "Fetching from " + baseUrl + "...";
        Request request = new Request.Builder()
                .url(baseUrl + "/api/leaderboard")
                .get()
                .build();

        httpClient.newCall(request).enqueue(new Callback() {
            @Override
            public void onFailure(@NonNull Call call, @NonNull IOException e) {
                String err = "Server unreachable (" + baseUrl + "): " + e.getMessage();
                onlineStatusMessage = err;
                mainHandler.post(() -> {
                    if (callback != null) {
                        callback.onOnlineDataError(err);
                    }
                });
            }

            @Override
            public void onResponse(@NonNull Call call, @NonNull Response response) throws IOException {
                try {
                    if (!response.isSuccessful() || response.body() == null) {
                        String err = "Server returned HTTP " + response.code();
                        onlineStatusMessage = err;
                        mainHandler.post(() -> {
                            if (callback != null) {
                                callback.onOnlineDataError(err);
                            }
                        });
                        return;
                    }
                    String jsonText = response.body().string();
                    JSONObject root = new JSONObject(jsonText);
                    JSONArray scoresArr = root.optJSONArray("leaderboard");
                    JSONArray matchesArr = root.optJSONArray("recentMatches");

                    List<ScoreEntry> parsedScores = new ArrayList<>();
                    if (scoresArr != null) {
                        for (int i = 0; i < scoresArr.length(); i++) {
                            JSONObject o = scoresArr.getJSONObject(i);
                            parsedScores.add(new ScoreEntry(
                                    i + 1,
                                    o.optString("playerName", "Pilot"),
                                    o.optInt("score", 0),
                                    o.optInt("coins", 0),
                                    o.optString("birdName", "Blue Bird"),
                                    o.optString("createdAt", "")
                            ));
                        }
                    }

                    List<OnlineMatchEntry> parsedMatches = new ArrayList<>();
                    if (matchesArr != null) {
                        for (int i = 0; i < matchesArr.length(); i++) {
                            JSONObject m = matchesArr.getJSONObject(i);
                            parsedMatches.add(new OnlineMatchEntry(
                                    m.optString("winnerName", "Draw"),
                                    m.optString("player1Name", "P1"),
                                    m.optInt("player1Score", 0),
                                    m.optString("player2Name", "P2"),
                                    m.optInt("player2Score", 0),
                                    m.optString("createdAt", "")
                            ));
                        }
                    }

                    synchronized (LeaderboardManager.this) {
                        cachedOnlineScores.clear();
                        cachedOnlineScores.addAll(parsedScores);
                        cachedOnlineMatches.clear();
                        cachedOnlineMatches.addAll(parsedMatches);
                        onlineStatusMessage = "Live Server Connected • " + parsedScores.size() + " ranked scores";
                    }

                    mainHandler.post(() -> {
                        if (callback != null) {
                            callback.onOnlineDataLoaded(parsedScores, parsedMatches, onlineStatusMessage);
                        }
                    });
                } catch (Exception ex) {
                    String err = "Invalid response from server: " + ex.getMessage();
                    onlineStatusMessage = err;
                    mainHandler.post(() -> {
                        if (callback != null) {
                            callback.onOnlineDataError(err);
                        }
                    });
                } finally {
                    response.close();
                }
            }
        });
    }

    private String resolveHttpBaseUrl() {
        if (!gameManager.isServerConfigured()) {
            return null;
        }
        String httpUrl = gameManager.getHttpServerUrl();
        if (httpUrl != null && !httpUrl.contains("YOUR_SERVER_DOMAIN")
                && (httpUrl.startsWith("http://") || httpUrl.startsWith("https://"))) {
            return httpUrl.replaceAll("/+$", "");
        }
        String wsUrl = gameManager.getWsServerUrl();
        if (wsUrl.startsWith("wss://")) {
            return ("https://" + wsUrl.substring("wss://".length())).replaceAll("/+$", "");
        } else if (wsUrl.startsWith("ws://")) {
            return ("http://" + wsUrl.substring("ws://".length())).replaceAll("/+$", "");
        }
        return null;
    }
}
