; SPDX-License-Identifier: GPL-2.0-or-later
; Copyright (C) 2026 TwinQuill contributors
*start
[iscript]
if(sf.boots === void) kag.userChSpeed=12;
else if(kag.userChSpeed!=12) throw new Exception("Saved text configuration was not restored");
sf.boots = (sf.boots === void ? 0 : sf.boots) + 1;
tf.notice = "Ready";
kag.saveSystemVariables();
[endscript]
[macro name="statusline"]
[emb exp="mp.text"][r]
[endmacro]
*menu
[stopbgm]
[image layer=base page=fore storage="menu.png"]
[nowait][cm]
TwinQuill M5 - Save / Load[r]
System boots: [emb exp="sf.boots"] / [emb exp="tf.notice"][r]
[link target="*new"]NEW GAME[endlink][r]
[link target="*load0"]LOAD SLOT 0[endlink][r]
[link target="*load1"]LOAD SLOT 1[endlink][r]
[link target="*quit"]QUIT[endlink]
[eval exp="Storages.writeText(System.dataPath+'m5-ready.tjs','menu')"][s]
*new
[eval exp="f.route='NONE';f.coins=7;tf.notice='New game'"]
[image layer=base page=fore storage="station.png"]
[image layer=0 page=fore storage="guide.png" visible=true left=440 top=60]
[playbgm storage="tone.wav" loop=true]
[bgmopt volume=40]
*checkpoint|Station checkpoint
[iscript]
if(f.route !== 'NONE' || f.coins !== 7) throw new Exception("Slot 0 variables were not restored");
if(kag.bgm.currentStorage !== 'tone.wav' || kag.bgm.volume !== 40000 || kag.bgm.buf1.paused)
    throw new Exception("Slot 0 BGM state was not restored");
[endscript]
[nowait][cm]
[statusline text="Station checkpoint - route NONE, coins 7"]
[emb exp="tf.notice"][r]
[link target="*save0"]SAVE SLOT 0[endlink][r]
[link target="*alpha"]ALPHA - Blue platform[endlink][r]
[link target="*beta"]BETA - Amber platform[endlink][r]
[link target="*quit"]QUIT[endlink]
[eval exp="Storages.writeText(System.dataPath+'m5-ready.tjs','station')"][s]
*save0
[iscript]
if(!kag.saveBookMark(0)) throw new Exception("Slot 0 save failed");
sf.saves = (sf.saves === void ? 0 : sf.saves) + 1;
kag.saveSystemVariables();
tf.notice = "Saved slot 0";
[endscript]
[jump target="*checkpoint"]
*load0
[if exp="Storages.isExistentStorage(System.dataPath+'/data0.kdt')"]
[eval exp="tf.notice='Loaded slot 0'"]
[iscript]
// The saved macro must replace this deliberately invalid live definition.
kag.mainConductor.macros.statusline='[eval exp="MissingM5Macro()"]';
[endscript]
[load place=0 ask=false]
[else]
[eval exp="tf.notice='Slot 0 is empty'"]
[jump target="*menu"]
[endif]
[s]
*load1
[if exp="Storages.isExistentStorage(System.dataPath+'/data1.kdt')"]
[eval exp="tf.notice='Loaded slot 1'"]
[load place=1 ask=false]
[else]
[eval exp="tf.notice='Slot 1 is empty'"]
[jump target="*menu"]
[endif]
[s]
*alpha
[eval exp="f.route='ALPHA';f.coins=8"]
[image layer=base page=fore storage="blue.png"]
[jump target="*call"]
*beta
[eval exp="f.route='BETA';f.coins=9"]
[image layer=base page=fore storage="amber.png"]
*call
[eval exp="tf.notice='Chapter ready'"]
[pausebgm]
[call storage="chapter.ks" target="*inside"]
[iscript]
if(tf.returned !== true || (f.route==='ALPHA' ? f.coins!==8 : f.coins!==9))
    throw new Exception("Restored chapter call stack or variables failed");
[endscript]
[resumebgm]
[nowait][cm]
Route [emb exp="f.route"] returned. Coins: [emb exp="f.coins"][r]
Call stack restored correctly.[r]
[link target="*load0"]LOAD SLOT 0[endlink][r]
[link target="*quit"]QUIT[endlink]
[eval exp="Storages.writeText(System.dataPath+'m5-ready.tjs','returned'+f.route+f.coins)"][s]
*quit
[close ask=false]
