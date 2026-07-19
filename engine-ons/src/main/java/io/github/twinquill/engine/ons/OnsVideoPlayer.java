/*
 * SPDX-License-Identifier: GPL-2.0-or-later
 * Copyright (C) 2026 TwinQuill contributors
 */
package io.github.twinquill.engine.ons;

import android.app.Activity;
import android.graphics.Color;
import android.graphics.SurfaceTexture;
import android.media.AudioAttributes;
import android.media.MediaPlayer;
import android.os.ParcelFileDescriptor;
import android.util.Log;
import android.view.Gravity;
import android.view.Surface;
import android.view.TextureView;
import android.view.ViewGroup;
import android.widget.FrameLayout;

import java.io.IOException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Plays a platform-supported movie over SDL while preserving ONS's synchronous
 * {@code movie} and {@code mpegplay} command behavior.
 */
final class OnsVideoPlayer {
    interface Source {
        ParcelFileDescriptor open(String path) throws IOException;
    }

    private static final String TAG = "TwinQuill/OnsVideo";

    private final Activity activity;
    private final ViewGroup root;
    private final Source source;
    private final AtomicReference<Playback> current = new AtomicReference<>();

    OnsVideoPlayer(Activity activity, ViewGroup root, Source source) {
        this.activity = activity;
        this.root = root;
        this.source = source;
    }

    void play(String path, boolean skippable, boolean looping) {
        ParcelFileDescriptor descriptor;
        try {
            descriptor = source.open(path);
        } catch (IOException | RuntimeException exception) {
            Log.e(TAG, "Unable to open ONS video: " + path, exception);
            return;
        }

        Playback playback = new Playback(descriptor, skippable, looping);
        Playback previous = current.getAndSet(playback);
        if (previous != null) {
            previous.cancel();
        }
        try {
            activity.runOnUiThread(playback::attach);
        } catch (RuntimeException exception) {
            Log.e(TAG, "Unable to attach ONS video surface", exception);
            playback.finish();
        }

        boolean interrupted = false;
        while (true) {
            try {
                playback.finished.await();
                break;
            } catch (InterruptedException exception) {
                interrupted = true;
                playback.cancel();
            }
        }
        current.compareAndSet(playback, null);
        if (interrupted) {
            Thread.currentThread().interrupt();
        }
    }

    void stop() {
        Playback playback = current.getAndSet(null);
        if (playback != null) {
            playback.cancel();
        }
    }

    private final class Playback
        implements TextureView.SurfaceTextureListener,
            MediaPlayer.OnPreparedListener,
            MediaPlayer.OnCompletionListener,
            MediaPlayer.OnErrorListener,
            MediaPlayer.OnVideoSizeChangedListener {
        private final ParcelFileDescriptor descriptor;
        private final boolean skippable;
        private final boolean looping;
        private final AtomicBoolean closed = new AtomicBoolean();
        private final CountDownLatch finished = new CountDownLatch(1);

        private FrameLayout overlay;
        private TextureView texture;
        private MediaPlayer player;
        private Surface surface;
        private int videoWidth;
        private int videoHeight;

        Playback(
            ParcelFileDescriptor descriptor,
            boolean skippable,
            boolean looping
        ) {
            this.descriptor = descriptor;
            this.skippable = skippable;
            this.looping = looping;
        }

        void attach() {
            if (closed.get() || activity.isFinishing() || activity.isDestroyed()) {
                finish();
                return;
            }
            overlay = new FrameLayout(activity);
            overlay.setBackgroundColor(Color.BLACK);
            overlay.setClickable(skippable);
            if (skippable) {
                overlay.setOnClickListener(view -> finish());
            }
            texture = new TextureView(activity);
            texture.setOpaque(true);
            texture.setSurfaceTextureListener(this);
            overlay.addView(
                texture,
                new FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    Gravity.CENTER
                )
            );
            overlay.addOnLayoutChangeListener(
                (view, left, top, right, bottom, oldLeft, oldTop, oldRight, oldBottom) ->
                    updateVideoLayout()
            );
            root.addView(
                overlay,
                new ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT
                )
            );
            overlay.bringToFront();
        }

        @Override
        public void onSurfaceTextureAvailable(
            SurfaceTexture surfaceTexture,
            int width,
            int height
        ) {
            if (closed.get()) {
                return;
            }
            try {
                surface = new Surface(surfaceTexture);
                player = new MediaPlayer();
                player.setAudioAttributes(
                    new AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_GAME)
                        .setContentType(AudioAttributes.CONTENT_TYPE_MOVIE)
                        .build()
                );
                player.setDataSource(descriptor.getFileDescriptor());
                player.setSurface(surface);
                player.setLooping(looping);
                player.setOnPreparedListener(this);
                player.setOnCompletionListener(this);
                player.setOnErrorListener(this);
                player.setOnVideoSizeChangedListener(this);
                player.prepareAsync();
            } catch (IOException | RuntimeException exception) {
                Log.e(TAG, "Unable to prepare ONS video", exception);
                finish();
            }
        }

        @Override
        public void onPrepared(MediaPlayer preparedPlayer) {
            if (closed.get()) {
                return;
            }
            preparedPlayer.start();
        }

        @Override
        public void onCompletion(MediaPlayer completedPlayer) {
            if (!looping) {
                finish();
            }
        }

        @Override
        public boolean onError(MediaPlayer failedPlayer, int what, int extra) {
            Log.e(TAG, "ONS video playback failed: what=" + what + ", extra=" + extra);
            finish();
            return true;
        }

        @Override
        public void onVideoSizeChanged(
            MediaPlayer mediaPlayer,
            int width,
            int height
        ) {
            videoWidth = width;
            videoHeight = height;
            updateVideoLayout();
        }

        @Override
        public void onSurfaceTextureSizeChanged(
            SurfaceTexture surfaceTexture,
            int width,
            int height
        ) {
            updateVideoLayout();
        }

        @Override
        public boolean onSurfaceTextureDestroyed(SurfaceTexture surfaceTexture) {
            finish();
            return true;
        }

        @Override
        public void onSurfaceTextureUpdated(SurfaceTexture surfaceTexture) {
            // MediaPlayer drives frame updates.
        }

        void cancel() {
            try {
                activity.runOnUiThread(this::finish);
            } catch (RuntimeException exception) {
                Log.w(TAG, "Unable to stop ONS video on the UI thread", exception);
                finish();
            }
        }

        void finish() {
            if (!closed.compareAndSet(false, true)) {
                return;
            }
            MediaPlayer activePlayer = player;
            player = null;
            if (activePlayer != null) {
                activePlayer.release();
            }
            Surface activeSurface = surface;
            surface = null;
            if (activeSurface != null) {
                activeSurface.release();
            }
            FrameLayout activeOverlay = overlay;
            overlay = null;
            if (activeOverlay != null && activeOverlay.getParent() instanceof ViewGroup) {
                ((ViewGroup) activeOverlay.getParent()).removeView(activeOverlay);
            }
            try {
                descriptor.close();
            } catch (IOException exception) {
                Log.w(TAG, "Unable to close ONS video descriptor", exception);
            }
            finished.countDown();
        }

        private void updateVideoLayout() {
            if (texture == null || overlay == null
                || videoWidth <= 0 || videoHeight <= 0
                || overlay.getWidth() <= 0 || overlay.getHeight() <= 0) {
                return;
            }
            float videoAspect = (float) videoWidth / videoHeight;
            float containerAspect = (float) overlay.getWidth() / overlay.getHeight();
            int targetWidth;
            int targetHeight;
            if (videoAspect > containerAspect) {
                targetWidth = overlay.getWidth();
                targetHeight = Math.round(targetWidth / videoAspect);
            } else {
                targetHeight = overlay.getHeight();
                targetWidth = Math.round(targetHeight * videoAspect);
            }
            FrameLayout.LayoutParams parameters =
                (FrameLayout.LayoutParams) texture.getLayoutParams();
            if (parameters.width != targetWidth || parameters.height != targetHeight) {
                parameters.width = targetWidth;
                parameters.height = targetHeight;
                parameters.gravity = Gravity.CENTER;
                texture.setLayoutParams(parameters);
            }
        }
    }
}
