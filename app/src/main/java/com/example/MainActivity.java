package com.example;

import android.app.Activity;
import android.app.AlertDialog;
import android.os.Bundle;
import android.text.InputFilter;
import android.text.InputType;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowManager;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

/**
 * MainActivity hosts the Fly Bird — Online Multiplayer Edition 2D Canvas GameView,
 * manages the AdMob banner and rewarded ads via AdManager, handles audio lifecycle
 * via SoundManager, and provides native configuration dialogs for 6-digit Room Codes
 * and the configurable Node.js WebSocket Server URL.
 */
public class MainActivity extends Activity implements GameView.HostUiCallbacks {

    private GameManager gameManager;
    private SoundManager soundManager;
    private LeaderboardManager leaderboardManager;
    private AdManager adManager;
    private GameView gameView;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        requestWindowFeature(Window.FEATURE_NO_TITLE);
        getWindow().setFlags(
                WindowManager.LayoutParams.FLAG_FULLSCREEN,
                WindowManager.LayoutParams.FLAG_FULLSCREEN
        );
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);

        gameManager = new GameManager(this);
        soundManager = new SoundManager(this);
        leaderboardManager = new LeaderboardManager(this, gameManager);
        adManager = new AdManager(this);

        FrameLayout rootContainer = new FrameLayout(this);
        rootContainer.setLayoutParams(new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
        ));

        gameView = new GameView(this, gameManager, soundManager, leaderboardManager, this);
        rootContainer.addView(gameView, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
        ));

        setContentView(rootContainer);

        // Initialize Google Mobile Ads (Banner on menus + optional Rewarded Ad)
        adManager.initialize(rootContainer);
    }

    @Override
    public void onRequestJoinRoomDialog() {
        LinearLayout layout = new LinearLayout(this);
        layout.setOrientation(LinearLayout.VERTICAL);
        int pad = (int) (20 * getResources().getDisplayMetrics().density);
        layout.setPadding(pad, pad / 2, pad, pad / 2);

        TextView hint = new TextView(this);
        hint.setText("Enter the 6-digit Room Code shared by Player 1:");
        layout.addView(hint);

        EditText codeInput = new EditText(this);
        codeInput.setHint("e.g. 482910");
        codeInput.setInputType(InputType.TYPE_CLASS_NUMBER);
        codeInput.setFilters(new InputFilter[]{new InputFilter.LengthFilter(6)});
        layout.addView(codeInput);

        new AlertDialog.Builder(this)
                .setTitle("Join 2-Player Room")
                .setView(layout)
                .setPositiveButton("JOIN ROOM", (dialog, which) -> {
                    String code = codeInput.getText() != null ? codeInput.getText().toString().trim() : "";
                    gameView.getMultiplayerManager().joinRoom(code);
                })
                .setNegativeButton("CANCEL", null)
                .show();
    }

    @Override
    public void onRequestServerAndProfileDialog() {
        LinearLayout layout = new LinearLayout(this);
        layout.setOrientation(LinearLayout.VERTICAL);
        int pad = (int) (20 * getResources().getDisplayMetrics().density);
        layout.setPadding(pad, pad / 2, pad, pad / 2);

        TextView nameLabel = new TextView(this);
        nameLabel.setText("Enter your Pilot Display Name (max 16 chars):");
        layout.addView(nameLabel);

        EditText nameInput = new EditText(this);
        nameInput.setText(gameManager.getPlayerName());
        nameInput.setSingleLine(true);
        nameInput.setFilters(new InputFilter[]{new InputFilter.LengthFilter(16)});
        layout.addView(nameInput);

        new AlertDialog.Builder(this)
                .setTitle("Edit Pilot Name")
                .setView(layout)
                .setPositiveButton("SAVE", (dialog, which) -> {
                    String newName = nameInput.getText() != null ? nameInput.getText().toString() : "";
                    gameManager.setPlayerName(newName);
                    gameView.showBannerNotice("Pilot Name saved: " + gameManager.getPlayerName());
                })
                .setNegativeButton("CANCEL", null)
                .show();
    }

    @Override
    public void onRequestRewardedAd() {
        adManager.showRewardedAd(new AdManager.RewardCallback() {
            @Override
            public void onCoinsRewarded(int bonusCoins) {
                gameManager.addCoins(bonusCoins);
                soundManager.playCoin();
                gameView.showBannerNotice("Rewarded Ad Completed! +" + bonusCoins + " Gold Coins added.");
            }

            @Override
            public void onAdUnavailable(String reason) {
                gameView.showBannerNotice(reason);
            }
        });
    }

    @Override
    public void onMenuStateChanged(boolean isMenuScreen) {
        if (adManager != null) {
            adManager.setMenuBannerVisible(isMenuScreen);
        }
    }

    @Override
    public void onShowToast(String message) {
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show();
    }

    @Override
    @SuppressWarnings("deprecation")
    public void onBackPressed() {
        if (gameView != null && gameView.handleBackPress()) {
            return;
        }
        super.onBackPressed();
    }

    @Override
    protected void onPause() {
        super.onPause();
        if (soundManager != null) {
            soundManager.pauseMusic();
        }
        if (adManager != null) {
            adManager.onPause();
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (soundManager != null) {
            soundManager.resumeMusic();
        }
        if (adManager != null) {
            adManager.onResume();
        }
    }

    @Override
    protected void onDestroy() {
        if (gameView != null && gameView.getMultiplayerManager() != null) {
            gameView.getMultiplayerManager().leaveRoom();
        }
        if (soundManager != null) {
            soundManager.release();
        }
        if (adManager != null) {
            adManager.onDestroy();
        }
        super.onDestroy();
    }
}
