; SPDX-License-Identifier: GPL-2.0-or-later
; Copyright (C) 2026 TwinQuill contributors
*start
[macro name="stationline"]
[emb exp="mp.text"][r]
[endmacro]
[image layer=base page=fore storage="station.png"]
[image layer=0 page=fore storage="guide.png" visible=true left=440 top=60]
[playbgm storage="tone.wav" loop=true]
[nowait]
[stationline text="TwinQuill M4 - Station"]
游戏演出 / KAG3 on Android[r]
Welcome. A tone is playing.[r]
Tap to choose your route.[p]
[cm]
Choose a route:[r]
[link target="*alpha"]ALPHA - Blue platform[endlink][r]
[link target="*beta"]BETA - Amber platform[endlink][s]
*alpha
[eval exp="tf.route='ALPHA'"]
[image layer=base page=fore storage="blue.png"]
[jump target="*joined"]
*beta
[eval exp="tf.route='BETA'"]
[image layer=base page=fore storage="amber.png"]
*joined
[nowait]
[cm]
[call storage="chapter.ks" target="*note"]
[iscript]
if(tf.noteSeen !== true) throw new Exception("Cross-file return did not run");
tf.completed=true;
[endscript]
[stopbgm]
[playse storage="chime.wav"]
[wait time=300]
[nowait]
Route [emb exp="tf.route"] selected.[r]
Tap to finish.[p]
[close ask=false]
