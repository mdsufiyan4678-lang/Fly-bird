package com.example;

import android.content.Context;
import android.content.SharedPreferences;
import android.media.AudioAttributes;
import android.media.MediaPlayer;
import android.media.SoundPool;
import android.util.Log;

/**
 * SoundManager handles all arcade sound effects (flap, coin, collision, button click)
 * and looping background music using SoundPool and MediaPlayer.
 * Preferences for sound and music are persisted in SharedPreferences.
 */
public class SoundManager {
    private static final String TAG = "SoundManager";
    private static final String PREFS_NAME = "fly_bird_audio_prefs";
    private static final String KEY_SOUND_ENABLED = "sound_enabled";
    private static final String KEY_MUSIC_ENABLED = "music_enabled";

    private final Context appContext;
    private final SharedPreferences prefs;

    private SoundPool soundPool;
    private int flapSoundId = 0;
    private int coinSoundId = 0;
    private int hitSoundId = 0;
    private int buttonSoundId = 0;
    private boolean soundsLoaded = false;

    private MediaPlayer bgmPlayer;
    private boolean soundEnabled;
    private boolean musicEnabled;

    public SoundManager(Context context) {
        this.appContext = context.getApplicationContext();
        this.prefs = appContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        this.soundEnabled = prefs.getBoolean(KEY_SOUND_ENABLED, true);
        this.musicEnabled = prefs.getBoolean(KEY_MUSIC_ENABLED, true);
        initSoundPool();
        initBackgroundMusic();
    }

    private void initSoundPool() {
        try {
            AudioAttributes attrs = new AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_GAME)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                    .build();

            soundPool = new SoundPool.Builder()
                    .setMaxStreams(6)
                    .setAudioAttributes(attrs)
                    .build();

            soundPool.setOnLoadCompleteListener((pool, sampleId, status) -> {
                if (status == 0) {
                    soundsLoaded = true;
                }
            });

            flapSoundId = soundPool.load(appContext, R.raw.sfx_flap, 1);
            coinSoundId = soundPool.load(appContext, R.raw.sfx_coin, 1);
            hitSoundId = soundPool.load(appContext, R.raw.sfx_hit, 1);
            buttonSoundId = soundPool.load(appContext, R.raw.sfx_button, 1);
        } catch (Exception e) {
            Log.w(TAG, "Unable to initialize SoundPool: " + e.getMessage());
        }
    }

    private void initBackgroundMusic() {
        try {
            bgmPlayer = MediaPlayer.create(appContext, R.raw.bgm_arcade);
            if (bgmPlayer != null) {
                bgmPlayer.setLooping(true);
                bgmPlayer.setVolume(0.35f, 0.35f);
                if (musicEnabled) {
                    bgmPlayer.start();
                }
            }
        } catch (Exception e) {
            Log.w(TAG, "Unable to initialize background music: " + e.getMessage());
        }
    }

    public void playFlap() {
        playEffect(flapSoundId, 0.85f);
    }

    public void playCoin() {
        playEffect(coinSoundId, 0.95f);
    }

    public void playHit() {
        playEffect(hitSoundId, 1.0f);
    }

    public void playButton() {
        playEffect(buttonSoundId, 0.8f);
    }

    private void playEffect(int soundId, float volume) {
        if (!soundEnabled || soundPool == null || soundId == 0 || !soundsLoaded) {
            return;
        }
        try {
            soundPool.play(soundId, volume, volume, 1, 0, 1.0f);
        } catch (Exception e) {
            Log.w(TAG, "Error playing sound effect: " + e.getMessage());
        }
    }

    public boolean isSoundEnabled() {
        return soundEnabled;
    }

    public void setSoundEnabled(boolean enabled) {
        this.soundEnabled = enabled;
        prefs.edit().putBoolean(KEY_SOUND_ENABLED, enabled).apply();
        if (enabled) {
            playButton();
        }
    }

    public boolean toggleSound() {
        setSoundEnabled(!soundEnabled);
        return soundEnabled;
    }

    public boolean isMusicEnabled() {
        return musicEnabled;
    }

    public void setMusicEnabled(boolean enabled) {
        this.musicEnabled = enabled;
        prefs.edit().putBoolean(KEY_MUSIC_ENABLED, enabled).apply();
        try {
            if (enabled) {
                if (bgmPlayer == null) {
                    initBackgroundMusic();
                } else if (!bgmPlayer.isPlaying()) {
                    bgmPlayer.start();
                }
            } else {
                if (bgmPlayer != null && bgmPlayer.isPlaying()) {
                    bgmPlayer.pause();
                }
            }
        } catch (Exception e) {
            Log.w(TAG, "Error toggling background music: " + e.getMessage());
        }
    }

    public boolean toggleMusic() {
        setMusicEnabled(!musicEnabled);
        return musicEnabled;
    }

    public void pauseMusic() {
        try {
            if (bgmPlayer != null && bgmPlayer.isPlaying()) {
                bgmPlayer.pause();
            }
        } catch (Exception e) {
            Log.w(TAG, "Error pausing music: " + e.getMessage());
        }
    }

    public void resumeMusic() {
        try {
            if (musicEnabled && bgmPlayer != null && !bgmPlayer.isPlaying()) {
                bgmPlayer.start();
            }
        } catch (Exception e) {
            Log.w(TAG, "Error resuming music: " + e.getMessage());
        }
    }

    public void release() {
        try {
            if (bgmPlayer != null) {
                if (bgmPlayer.isPlaying()) {
                    bgmPlayer.stop();
                }
                bgmPlayer.release();
                bgmPlayer = null;
            }
        } catch (Exception e) {
            Log.w(TAG, "Error releasing MediaPlayer: " + e.getMessage());
        }
        try {
            if (soundPool != null) {
                soundPool.release();
                soundPool = null;
            }
        } catch (Exception e) {
            Log.w(TAG, "Error releasing SoundPool: " + e.getMessage());
        }
    }
}
