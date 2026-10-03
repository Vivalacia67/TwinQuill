/* SPDX-License-Identifier: GPL-2.0-or-later */
#pragma once
#include <jni.h>
#include <string_view>
void TVPInitializeAndroidAudio(JNIEnv*);
int TVPOpenAndroidWave(std::string_view);
int TVPControlAndroidAudio(int,int,int);
