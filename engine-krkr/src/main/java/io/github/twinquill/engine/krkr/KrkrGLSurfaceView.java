/*
 * SPDX-License-Identifier: GPL-2.0-or-later
 * Copyright (C) 2026 TwinQuill contributors
 */
package io.github.twinquill.engine.krkr;

import android.content.Context;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.SurfaceHolder;

import android.opengl.GLSurfaceView;

/** First-party GLES2 surface with primitive, bounded input forwarding. */
final class KrkrGLSurfaceView extends GLSurfaceView {
    private static final int MAX_POINTERS = 10;

    private final KrkrRuntimeRenderer renderer;

    KrkrGLSurfaceView(Context context, KrkrRuntimeRenderer renderer) {
        super(context);
        this.renderer = renderer;
        setFocusable(true);
        setFocusableInTouchMode(true);
        setEGLContextClientVersion(2);
        setPreserveEGLContextOnPause(true);
        setRenderer(renderer);
        setRenderMode(RENDERMODE_CONTINUOUSLY);
        renderer.attachView(this);
    }

    @Override
    public boolean onTouchEvent(MotionEvent event) {
        if (event == null) {
            return false;
        }
        final int action = event.getActionMasked();
        if (action == MotionEvent.ACTION_DOWN) requestFocus();
        final long eventTime = event.getEventTime();
        final int eventPointerCount = event.getPointerCount();
        final int pointerCount = Math.min(eventPointerCount, MAX_POINTERS);
        if (pointerCount <= 0) {
            renderer.dropInput();
            return false;
        }

        if (action == MotionEvent.ACTION_MOVE || action == MotionEvent.ACTION_CANCEL) {
            renderer.dropInput(eventPointerCount - pointerCount);
            for (int index = 0; index < pointerCount; index++) {
                renderer.enqueueTouch(
                    action,
                    event.getPointerId(index),
                    event.getX(index),
                    event.getY(index),
                    eventTime
                );
            }
            return true;
        }

        if (action == MotionEvent.ACTION_DOWN
            || action == MotionEvent.ACTION_UP
            || action == MotionEvent.ACTION_POINTER_DOWN
            || action == MotionEvent.ACTION_POINTER_UP) {
            int index = event.getActionIndex();
            if (index < 0 || index >= pointerCount) {
                renderer.dropInput();
                return true;
            }
            renderer.enqueueTouch(
                action,
                event.getPointerId(index),
                event.getX(index),
                event.getY(index),
                eventTime
            );
            return true;
        }
        return super.onTouchEvent(event);
    }

    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        if (event == null) {
            return false;
        }
        if (keyCode == KeyEvent.KEYCODE_BACK) {
            super.onKeyDown(keyCode, event);
            return false;
        }
        if (isSystemKey(event, keyCode)) {
            return super.onKeyDown(keyCode, event);
        }
        renderer.enqueueKey(
            true,
            keyCode,
            event.getUnicodeChar(event.getMetaState()),
            event.getMetaState(),
            event.getRepeatCount(),
            event.getEventTime()
        );
        return true;
    }

    @Override
    public boolean onKeyUp(int keyCode, KeyEvent event) {
        if (event == null) {
            return false;
        }
        if (keyCode == KeyEvent.KEYCODE_BACK) {
            super.onKeyUp(keyCode, event);
            return false;
        }
        if (isSystemKey(event, keyCode)) {
            return super.onKeyUp(keyCode, event);
        }
        renderer.enqueueKey(
            false,
            keyCode,
            event.getUnicodeChar(event.getMetaState()),
            event.getMetaState(),
            event.getRepeatCount(),
            event.getEventTime()
        );
        return true;
    }

    @Override
    public void surfaceDestroyed(SurfaceHolder holder) {
        renderer.surfaceLostAndWait();
        super.surfaceDestroyed(holder);
    }

    private static boolean isSystemKey(KeyEvent event, int keyCode) {
        return event.isSystem()
            || keyCode == KeyEvent.KEYCODE_BACK
            || keyCode == KeyEvent.KEYCODE_HOME
            || keyCode == KeyEvent.KEYCODE_POWER
            || keyCode == KeyEvent.KEYCODE_VOLUME_UP
            || keyCode == KeyEvent.KEYCODE_VOLUME_DOWN
            || keyCode == KeyEvent.KEYCODE_VOLUME_MUTE;
    }
}
