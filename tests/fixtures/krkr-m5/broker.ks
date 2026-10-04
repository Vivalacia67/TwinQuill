; SPDX-License-Identifier: GPL-2.0-or-later
; Copyright (C) 2026 TwinQuill contributors
*start
[iscript]
sf.visits=(sf.visits===void?0:sf.visits)+1;
kag.saveSystemVariables();
[endscript]
[if exp="sf.visits>1"]
[load place=0 ask=false]
[endif]
[eval exp="f.value=17"]
[image layer=base page=fore storage="station.png"]
[playbgm storage="tone.wav" loop=true]
*checkpoint|Broker checkpoint
[iscript]
if(f.value!==17 || sf.visits>2) throw new Exception("Persistent KAG state failed");
if(sf.visits===1 && !kag.saveBookMark(0)) throw new Exception("Persistent KAG save failed");
[endscript]
[wait time=300]
[close ask=false]
