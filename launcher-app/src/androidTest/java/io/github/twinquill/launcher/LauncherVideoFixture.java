/*
 * SPDX-License-Identifier: GPL-2.0-or-later
 * Copyright (C) 2026 TwinQuill contributors
 */
package io.github.twinquill.launcher;

import android.content.Context;
import android.media.MediaCodec;
import android.media.MediaCodecInfo;
import android.media.MediaFormat;
import android.media.MediaMuxer;
import android.os.SystemClock;

import java.io.File;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.file.Files;

/** Creates a short H.264/MP4 fixture from raw frames on the test device. */
final class LauncherVideoFixture {
    private static final int WIDTH = 64;
    private static final int HEIGHT = 64;
    private static final int FRAME_RATE = 10;
    private static final int FRAME_COUNT = 20;
    private static final long FRAME_DURATION_US = 1_000_000L / FRAME_RATE;
    private static final long ENCODE_TIMEOUT_MILLIS = 15_000L;

    private static byte[] cached;

    private LauncherVideoFixture() {
    }

    static synchronized byte[] bytes(Context context) throws IOException {
        if (cached == null) {
            cached = encode(context);
        }
        return cached;
    }

    private static byte[] encode(Context context) throws IOException {
        File output = File.createTempFile(
            "twinquill-ons-video-",
            ".mp4",
            context.getCacheDir()
        );
        MediaCodec encoder = null;
        MediaMuxer muxer = null;
        boolean encoderStarted = false;
        boolean muxerStarted = false;
        try {
            MediaFormat format = MediaFormat.createVideoFormat(
                MediaFormat.MIMETYPE_VIDEO_AVC,
                WIDTH,
                HEIGHT
            );
            format.setInteger(
                MediaFormat.KEY_COLOR_FORMAT,
                MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Flexible
            );
            format.setInteger(MediaFormat.KEY_BIT_RATE, 96_000);
            format.setInteger(MediaFormat.KEY_FRAME_RATE, FRAME_RATE);
            format.setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1);
            format.setInteger(
                MediaFormat.KEY_MAX_INPUT_SIZE,
                WIDTH * HEIGHT * 3 / 2
            );

            encoder = MediaCodec.createEncoderByType(
                MediaFormat.MIMETYPE_VIDEO_AVC
            );
            encoder.configure(
                format,
                null,
                null,
                MediaCodec.CONFIGURE_FLAG_ENCODE
            );
            encoder.start();
            encoderStarted = true;

            muxer = new MediaMuxer(
                output.getAbsolutePath(),
                MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4
            );
            MediaCodec.BufferInfo bufferInfo = new MediaCodec.BufferInfo();
            int frameIndex = 0;
            int trackIndex = -1;
            boolean inputEnded = false;
            boolean outputEnded = false;
            long deadline =
                SystemClock.elapsedRealtime() + ENCODE_TIMEOUT_MILLIS;

            while (!outputEnded && SystemClock.elapsedRealtime() < deadline) {
                if (!inputEnded) {
                    int inputIndex = encoder.dequeueInputBuffer(10_000);
                    if (inputIndex >= 0) {
                        ByteBuffer input = encoder.getInputBuffer(inputIndex);
                        if (input == null) {
                            throw new IOException("Video encoder input is unavailable");
                        }
                        input.clear();
                        if (frameIndex < FRAME_COUNT) {
                            writeFrame(input, frameIndex);
                            encoder.queueInputBuffer(
                                inputIndex,
                                0,
                                WIDTH * HEIGHT * 3 / 2,
                                frameIndex * FRAME_DURATION_US,
                                0
                            );
                            frameIndex++;
                        } else {
                            encoder.queueInputBuffer(
                                inputIndex,
                                0,
                                0,
                                frameIndex * FRAME_DURATION_US,
                                MediaCodec.BUFFER_FLAG_END_OF_STREAM
                            );
                            inputEnded = true;
                        }
                    }
                }

                int outputIndex = encoder.dequeueOutputBuffer(bufferInfo, 10_000);
                if (outputIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    if (muxerStarted) {
                        throw new IOException(
                            "Video encoder changed format more than once"
                        );
                    }
                    trackIndex = muxer.addTrack(encoder.getOutputFormat());
                    muxer.start();
                    muxerStarted = true;
                } else if (outputIndex >= 0) {
                    ByteBuffer encoded = encoder.getOutputBuffer(outputIndex);
                    if (encoded == null) {
                        throw new IOException("Video encoder output is unavailable");
                    }
                    if ((bufferInfo.flags & MediaCodec.BUFFER_FLAG_CODEC_CONFIG) != 0) {
                        bufferInfo.size = 0;
                    }
                    if (bufferInfo.size > 0) {
                        if (!muxerStarted || trackIndex < 0) {
                            throw new IOException(
                                "Video sample arrived before muxer format"
                            );
                        }
                        encoded.position(bufferInfo.offset);
                        encoded.limit(bufferInfo.offset + bufferInfo.size);
                        muxer.writeSampleData(trackIndex, encoded, bufferInfo);
                    }
                    outputEnded =
                        (bufferInfo.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0;
                    encoder.releaseOutputBuffer(outputIndex, false);
                }
            }
            if (!outputEnded) {
                throw new IOException("Timed out while generating video fixture");
            }

            muxer.stop();
            muxerStarted = false;
            byte[] result = Files.readAllBytes(output.toPath());
            if (result.length == 0) {
                throw new IOException("Generated video fixture is empty");
            }
            return result;
        } finally {
            if (encoder != null) {
                if (encoderStarted) {
                    try {
                        encoder.stop();
                    } catch (RuntimeException ignored) {
                        // Preserve the original fixture-generation failure.
                    }
                }
                encoder.release();
            }
            if (muxer != null) {
                if (muxerStarted) {
                    try {
                        muxer.stop();
                    } catch (RuntimeException ignored) {
                        // Preserve the original fixture-generation failure.
                    }
                }
                muxer.release();
            }
            Files.deleteIfExists(output.toPath());
        }
    }

    private static void writeFrame(ByteBuffer target, int frameIndex) {
        int lumaSize = WIDTH * HEIGHT;
        int chromaSize = lumaSize / 4;
        int luma = 32 + (frameIndex * 9) % 192;
        for (int index = 0; index < lumaSize; index++) {
            target.put((byte) luma);
        }
        for (int index = 0; index < chromaSize; index++) {
            target.put((byte) 96);
        }
        for (int index = 0; index < chromaSize; index++) {
            target.put((byte) 160);
        }
    }
}
