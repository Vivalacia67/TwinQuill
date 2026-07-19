/*
 * SPDX-License-Identifier: GPL-2.0-or-later
 * Copyright (C) 2026 TwinQuill contributors
 */
package io.github.twinquill.engine.ons;

import static org.junit.Assert.assertEquals;

import android.media.AudioManager;

import org.junit.Test;

public final class OnsAudioFocusControllerTest {
    @Test
    public void mapsFocusChangesToSafePlaybackVolumes() {
        assertEquals(
            1.0f,
            OnsAudioFocusController.volumeForFocusChange(
                AudioManager.AUDIOFOCUS_GAIN
            ),
            0.0f
        );
        assertEquals(
            0.2f,
            OnsAudioFocusController.volumeForFocusChange(
                AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK
            ),
            0.0f
        );
        assertEquals(
            0.0f,
            OnsAudioFocusController.volumeForFocusChange(
                AudioManager.AUDIOFOCUS_LOSS_TRANSIENT
            ),
            0.0f
        );
        assertEquals(
            0.0f,
            OnsAudioFocusController.volumeForFocusChange(
                AudioManager.AUDIOFOCUS_LOSS
            ),
            0.0f
        );
    }
}
