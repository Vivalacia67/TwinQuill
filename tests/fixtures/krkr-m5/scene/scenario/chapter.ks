; SPDX-License-Identifier: GPL-2.0-or-later
; Copyright (C) 2026 TwinQuill contributors
*inside|Chapter checkpoint
[iscript]
if(kag.bgm.currentStorage !== 'tone.wav' || kag.bgm.volume !== 40000 || !kag.bgm.buf1.paused)
    throw new Exception("Slot 1 paused BGM state was not restored");
if((f.route==='ALPHA' ? f.coins!==8 : f.coins!==9) || (f.route!=='ALPHA' && f.route!=='BETA'))
    throw new Exception("Slot 1 variables were not restored");
[endscript]
[nowait][cm]
[emb exp="'Chapter checkpoint / '+tf.notice"][r]
Route [emb exp="f.route"] / coins [emb exp="f.coins"][r]
[link target="*save1"]SAVE SLOT 1[endlink][r]
[link target="*return"]RETURN TO STATION[endlink][r]
[link storage="first.ks" target="*load0"]LOAD SLOT 0[endlink][r]
[link storage="first.ks" target="*quit"]QUIT[endlink]
[eval exp="Storages.writeText(System.dataPath+'m5-ready.tjs','chapter'+f.route+f.coins)"][s]
*save1
[iscript]
if(!kag.saveBookMark(1)) throw new Exception("Slot 1 save failed");
tf.notice='Saved slot 1';
[endscript]
[jump target="*inside"]
*return
[eval exp="tf.returned=true"]
[return]
