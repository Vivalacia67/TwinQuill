/*
 * SPDX-License-Identifier: GPL-2.0-or-later
 * Copyright (C) 2026 TwinQuill contributors
 */
package io.github.twinquill.engine.ons;

import android.content.Context;
import android.media.AudioAttributes;
import android.media.AudioFocusRequest;
import android.media.AudioManager;
import android.media.AudioTrack;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import org.libsdl.app.SDLAudioManager;

/** Coordinates Android audio focus without bypassing SDL's lifecycle state machine. */
final class OnsAudioFocusController extends SDLAudioManager
    implements AudioManager.OnAudioFocusChangeListener {
    private static final String TAG = "TwinQuill/OnsAudio";
    private static final float FULL_VOLUME = 1.0f;
    private static final float DUCKED_VOLUME = 0.2f;
    private static final float MUTED_VOLUME = 0.0f;

    private final AudioManager audioManager;
    private final AudioFocusRequest focusRequest;

    private boolean resumed;
    private boolean ownsFocus;

    OnsAudioFocusController(Context context) {
        audioManager = context.getSystemService(AudioManager.class);
        if (audioManager == null) {
            throw new IllegalStateException("Android audio service is unavailable");
        }
        AudioAttributes attributes = new AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_GAME)
            .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
            .build();
        focusRequest = new AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
            .setAudioAttributes(attributes)
            .setOnAudioFocusChangeListener(
                this,
                new Handler(Looper.getMainLooper())
            )
            .setWillPauseWhenDucked(false)
            .build();
    }

    void onResume() {
        resumed = true;
        int result = audioManager.requestAudioFocus(focusRequest);
        ownsFocus = result == AudioManager.AUDIOFOCUS_REQUEST_GRANTED;
        setPlaybackVolume(ownsFocus ? FULL_VOLUME : MUTED_VOLUME);
        if (!ownsFocus) {
            Log.w(TAG, "Audio focus request was not granted: " + result);
        }
    }

    void onPause() {
        resumed = false;
        setPlaybackVolume(MUTED_VOLUME);
        if (ownsFocus) {
            audioManager.abandonAudioFocusRequest(focusRequest);
            ownsFocus = false;
        }
    }

    @Override
    public void onAudioFocusChange(int focusChange) {
        if (!resumed) {
            return;
        }
        setPlaybackVolume(volumeForFocusChange(focusChange));
        if (focusChange == AudioManager.AUDIOFOCUS_GAIN) {
            ownsFocus = true;
        } else if (focusChange == AudioManager.AUDIOFOCUS_LOSS) {
            ownsFocus = false;
        }
    }

    static float volumeForFocusChange(int focusChange) {
        switch (focusChange) {
            case AudioManager.AUDIOFOCUS_GAIN:
                return FULL_VOLUME;
            case AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK:
                return DUCKED_VOLUME;
            case AudioManager.AUDIOFOCUS_LOSS:
            case AudioManager.AUDIOFOCUS_LOSS_TRANSIENT:
            default:
                return MUTED_VOLUME;
        }
    }

    private static void setPlaybackVolume(float volume) {
        AudioTrack playback = mAudioTrack;
        if (playback == null || playback.getState() != AudioTrack.STATE_INITIALIZED) {
            return;
        }
        try {
            playback.setVolume(volume);
        } catch (IllegalStateException exception) {
            Log.w(TAG, "SDL playback volume could not be changed", exception);
        }
    }
}
