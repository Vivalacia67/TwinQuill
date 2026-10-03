/*
 * SPDX-License-Identifier: GPL-2.0-or-later
 * Copyright (C) 2026 TwinQuill contributors
 */
package io.github.twinquill.engine.krkr;

import android.media.AudioAttributes;
import android.media.AudioFormat;
import android.media.AudioTrack;
import java.util.LinkedHashMap;
import java.util.Map;

/** Bounded PCM16 output. Engine calls run on the script worker. */
public final class KrkrPcmAudio {
    private static final int BYTE_LIMIT = 8 * 1024 * 1024;
    private static final Map<Integer, Sound> sounds = new LinkedHashMap<>();
    private static int nextId = 1;
    private static int allocatedBytes;
    private static long renderedFrames;
    private static boolean hostPaused;

    private KrkrPcmAudio() {}

    private static final class Sound {
        final AudioTrack track;
        final int bytes;
        final int frames;
        boolean playing;
        boolean pausedByHost;
        boolean manuallyPaused;
        boolean looping;
        long previousHead;

        Sound(AudioTrack output, int size, int channels) {
            track = output;
            bytes = size;
            frames = size / (2 * channels);
        }
    }

    public static synchronized int open(int rate, int channels, byte[] pcm) {
        if ((channels != 1 && channels != 2) || rate < 8000 || rate > 48000
                || pcm.length == 0 || pcm.length % (channels * 2) != 0
                || sounds.size() >= 16 || pcm.length > BYTE_LIMIT - allocatedBytes) {
            throw new IllegalArgumentException("PCM limits exceeded");
        }
        AudioTrack output = new AudioTrack.Builder()
            .setAudioAttributes(new AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_GAME)
                .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC).build())
            .setAudioFormat(new AudioFormat.Builder().setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                .setSampleRate(rate).setChannelMask(channels == 1
                    ? AudioFormat.CHANNEL_OUT_MONO : AudioFormat.CHANNEL_OUT_STEREO).build())
            .setTransferMode(AudioTrack.MODE_STATIC).setBufferSizeInBytes(pcm.length).build();
        try {
            if (output.write(pcm, 0, pcm.length) != pcm.length
                    || output.getState() != AudioTrack.STATE_INITIALIZED) {
                throw new IllegalStateException("PCM output initialization failed");
            }
            int id = nextId++;
            if (nextId <= 0) nextId = 1;
            if (sounds.containsKey(id)) throw new IllegalStateException("PCM handle exhausted");
            sounds.put(id, new Sound(output, pcm.length, channels));
            allocatedBytes += pcm.length;
            return id;
        } catch (RuntimeException failure) {
            output.release();
            throw failure;
        }
    }

    private static void updateHead(Sound sound) {
        if (!sound.playing) return;
        long head = Integer.toUnsignedLong(sound.track.getPlaybackHeadPosition());
        long delta = head - sound.previousHead;
        if (delta < 0) delta += 1L << 32;
        renderedFrames += delta;
        sound.previousHead = head;
    }

    public static synchronized int control(int id, int action, int value) {
        if (id == 0) {
            if (action == 9) {
                hostPaused = true;
                for (Sound sound : sounds.values()) {
                    updateHead(sound);
                    if (sound.playing && !sound.manuallyPaused) {
                        sound.track.pause();
                        sound.pausedByHost = true;
                    }
                }
            } else if (action == 10) {
                hostPaused = false;
                for (Sound sound : sounds.values()) {
                    if (sound.pausedByHost) {
                        sound.pausedByHost = false;
                        if (sound.playing && !sound.manuallyPaused) sound.track.play();
                    }
                }
            } else if (action == 11) {
                for (Sound sound : sounds.values()) {
                    updateHead(sound);
                    sound.track.release();
                }
                sounds.clear();
                allocatedBytes = 0;
                hostPaused = false;
            } else if (action == 12) {
                return (int) Math.min(Integer.MAX_VALUE, renderedFrames());
            } else {
                throw new IllegalArgumentException("Unknown PCM command");
            }
            return 0;
        }
        Sound sound = sounds.get(id);
        if (sound == null) throw new IllegalArgumentException("Unknown PCM handle");
        switch (action) {
            case 1:
                updateHead(sound);
                sound.track.stop();
                if (sound.track.reloadStaticData() != AudioTrack.SUCCESS) {
                    throw new IllegalStateException("PCM rewind failed");
                }
                sound.previousHead = 0;
                sound.looping = value != 0;
                if (sound.track.setLoopPoints(0, sound.frames, sound.looping ? -1 : 0) != AudioTrack.SUCCESS) {
                    throw new IllegalArgumentException("PCM loop range rejected");
                }
                sound.playing = true;
                sound.pausedByHost = hostPaused;
                if (!hostPaused && !sound.manuallyPaused) sound.track.play();
                break;
            case 2:
                updateHead(sound);
                sound.track.stop();
                sound.playing = false;
                sound.pausedByHost = false;
                break;
            case 3:
                updateHead(sound);
                sound.track.release();
                sounds.remove(id);
                allocatedBytes -= sound.bytes;
                break;
            case 4:
                updateHead(sound);
                if (sound.playing && !sound.looping && sound.previousHead >= sound.frames) {
                    sound.playing = false;
                    sound.track.stop();
                }
                return sound.playing ? 1 : 0;
            case 5:
                if (value < 0 || value > 100000) throw new IllegalArgumentException("PCM volume range");
                if (sound.track.setVolume(value / 100000f) != AudioTrack.SUCCESS) {
                    throw new IllegalStateException("PCM volume rejected");
                }
                break;
            case 6:
                sound.manuallyPaused = value != 0;
                if (sound.manuallyPaused) sound.track.pause();
                else if (sound.playing && !hostPaused) sound.track.play();
                break;
            case 7:
                return sound.track.getPlaybackHeadPosition();
            default:
                throw new IllegalArgumentException("Unknown PCM command");
        }
        return 0;
    }

    /** Polls actual AudioTrack heads, including while the engine timer is paused. */
    public static synchronized long renderedFrames() {
        for (Sound sound : sounds.values()) updateHead(sound);
        return renderedFrames;
    }

    public static synchronized int activeSoundCount() { return sounds.size(); }
}
