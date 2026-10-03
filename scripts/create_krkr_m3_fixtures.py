# SPDX-License-Identifier: GPL-2.0-or-later
# Copyright (C) 2026 TwinQuill contributors
"""Reproducible authored XP3/storage fixtures; no commercial game data."""
from __future__ import annotations
import argparse
import json
import struct
import zlib
from pathlib import Path
ROOT = Path(__file__).resolve().parents[1]
MARK = b"XP3\r\n \n\x1a\x8bg\x01"

def deflate(content):
    """Canonical fixed-Huffman DEFLATE for small fixtures, independent of zlib version.

    Greedy LZ77 with a fixed candidate bound is used only to author test inputs.
    Production decoding still uses the source-pinned zlib target.
    """
    output = bytearray(b"\x78\x01")
    bits = bit_count = 0
    def emit(value, width):
        nonlocal bits, bit_count
        bits |= value << bit_count
        bit_count += width
        while bit_count >= 8:
            output.append(bits & 255)
            bits >>= 8
            bit_count -= 8
    def reversed_bits(value, width):
        result = 0
        for _ in range(width):
            result = (result << 1) | (value & 1)
            value >>= 1
        return result
    def symbol(value):
        if value < 144: code,width = 0x30+value,8
        elif value < 256: code,width = 0x190+value-144,9
        elif value < 280: code,width = value-256,7
        else: code,width = 0xc0+value-280,8
        emit(reversed_bits(code,width),width)
    lengths = [3,4,5,6,7,8,9,10,11,13,15,17,19,23,27,31,35,43,51,59,
               67,83,99,115,131,163,195,227,258]
    length_extra = [0]*8+[1]*4+[2]*4+[3]*4+[4]*4+[5]*4+[0]
    distances = [1,2,3,4,5,7,9,13,17,25,33,49,65,97,129,193,257,385,
                 513,769,1025,1537,2049,3073,4097,6145,8193,12289,16385,24577]
    distance_extra = [0]*4+[e for e in range(1,14) for _ in range(2)]
    emit(3,3)  # BFINAL=1, BTYPE=fixed Huffman.
    matches = {}
    position = 0
    while position < len(content):
        prefix = content[position:position+3]
        best, distance = 0,0
        for prior in reversed(matches.get(prefix,[])):
            delta = position-prior
            if delta > 32768: break
            size = 0
            while size < 258 and position+size < len(content) and content[prior+size] == content[position+size]:
                size += 1
            if size > best:
                best,distance = size,delta
        if best >= 3:
            li = next(i for i in range(len(lengths)-1,-1,-1) if lengths[i] <= best)
            symbol(257+li)
            emit(best-lengths[li],length_extra[li])
            di = next(i for i in range(len(distances)-1,-1,-1) if distances[i] <= distance)
            emit(reversed_bits(di,5),5)
            emit(distance-distances[di],distance_extra[di])
            take = best
        else:
            symbol(content[position]);take=1
        for index in range(position,position+take):
            key = content[index:index+3]
            candidates = matches.setdefault(key,[])
            candidates.append(index)
            if len(candidates)>64: del candidates[0]
        position += take
    symbol(256)
    if bit_count: output.append(bits & 255)
    output.extend(struct.pack(">I",zlib.adler32(content)))
    encoded = bytes(output)
    assert zlib.decompress(encoded) == content
    return encoded

def chunk(tag: bytes, payload: bytes) -> bytes:
    return tag + struct.pack("<Q", len(payload)) + payload

def xp3(entries, *, compressed=True, chained=False, protected=False,
        duplicate=False, member_size=None, bad_segment=False):
    data = bytearray(MARK + b"\0" * 8)
    files = []
    for name, content in entries:
        segments, packed_total = [], 0
        parts = [content[:len(content)//2], content[len(content)//2:]] if len(content)>2 else [content]
        for i, part in enumerate(parts):
            zipped = compressed and i % 2 == 1
            packed = deflate(part) if zipped else part
            segments.append(struct.pack("<IQQQ", int(zipped), len(data), len(part), len(packed)))
            data.extend(packed)
            packed_total += len(packed)
        encoded = name.encode("utf-16le")
        size = len(content) if member_size is None else member_size
        info = struct.pack("<IQQH", 0x80000000 if protected else 0, size, packed_total,
                           len(encoded)//2) + encoded
        segm = b"".join(segments)
        if bad_segment:
            segm = struct.pack("<I",1)+segm[4:12]+struct.pack("<Q",33*1024*1024)+segm[20:]
        files.append(chunk(b"File",chunk(b"info",info)+chunk(b"segm",segm)
                           +chunk(b"adlr",struct.pack("<I",zlib.adler32(content)))))
    if duplicate:
        files.append(files[0])
    data[11:19] = struct.pack("<Q",len(data))
    groups = [files[:1],files[1:]] if chained and len(files)>1 else [files]
    for i, group in enumerate(groups):
        index = b"".join(group)
        packed = deflate(index) if compressed else index
        flags = int(compressed) | (0x80 if i+1<len(groups) else 0)
        data.append(flags)
        data.extend(struct.pack("<Q",len(packed)))
        if compressed:
            data.extend(struct.pack("<Q",len(index)))
        data.extend(packed)
        if flags & 0x80:
            data.extend(struct.pack("<Q",len(data)+8))
    return bytes(data)

def generated():
    outputs = {}
    def put(name, content):
        outputs[name] = content.encode("utf-8") if isinstance(content,str) else content
    visual = (ROOT/"tests/fixtures/krkr-m2/visual/startup.tjs").read_bytes()
    png = (ROOT/"tests/fixtures/krkr-m2/visual/checker.png").read_bytes()
    jpeg = (ROOT/"tests/fixtures/krkr-m2/visual/sample.jpg").read_bytes()
    visual = visual.replace(b"checker.png",b"images/checker.png").replace(b"sample.jpg",b"images/sample.jpg")
    entries = [("startup.tjs",visual),("images/checker.png",png),("images/sample.jpg",jpeg),
               ("scripts/日本語.tjs",'var m3Japanese="日本語";'.encode()),("empty.bin",b"")]
    for name, content in entries:
        put("m3-loose/"+name,content)
    put("m3-compressed/data.xp3",xp3(entries,chained=True))
    patch_script = """var w=new Window(); w.setInnerSize(320,200); w.caption="TwinQuill M3 patches";
var l=new Layer(w,null); l.type=ltOpaque; l.setSize(320,200); l.setImageSize(320,200); l.visible=true;
Scripts.execStorage("scripts/日本語.tjs");
if(m3Japanese!="日本語") throw "Unicode member lookup failed";
if(Storages.readText("marker.txt")!="PATCH") throw "Patch precedence failed";
if(Storages.readText("loose.txt")!="LOOSE") throw "Loose precedence failed";
Storages.addAutoPath("data.xp3>scripts/");
Scripts.execStorage("日本語.tjs");
Storages.removeAutoPath("data.xp3>scripts/");
if(Storages.getListAt("data.xp3>images/").count!=2) throw "Member listing failed";
l.fillRect(0,0,320,200,0x228844); w.visible=true;
w.onKeyDown=function(key){if(key==13)w.close();};
"""
    put("m3-patches/data.xp3",xp3(entries+[("marker.txt",b"BASE"),("loose.txt",b"BASE")]))
    put("m3-patches/patch.xp3",xp3([("startup.tjs",patch_script.encode()),("marker.txt",b"PATCH"),("loose.txt",b"PATCH")]))
    put("m3-patches/loose.txt","LOOSE")
    cp_script = """var w=new Window(); w.setInnerSize(320,200); w.caption="TwinQuill M3 CP932";
var l=new Layer(w,null); l.type=ltOpaque; l.setSize(320,200); l.setImageSize(320,200); l.visible=true;
Scripts.execStorage("日本語.tjs");
if(m3Japanese!="日本語") throw "CP932 decoding failed";
l.fillRect(0,0,320,200,0x663399); w.visible=true;
w.onKeyDown=function(key){if(key==13)w.close();};
"""
    put("m3-cp932/twinquill-krkr.conf","# Explicit game encoding; restart after changes.\ntextEncoding=cp932\n")
    put("m3-cp932/data.xp3",xp3([("startup.tjs",cp_script.encode("cp932")),
        ("日本語.tjs",'var m3Japanese="日本語";'.encode("cp932"))]))
    save_script = """var p=System.dataPath;
Storages.createFolders(p+"checks/");
var path=p+"checks/counter.tjs";
var n=Storages.isExistentStorage(path)?int(Scripts.evalStorage(path,"utf-8")):0;
Storages.writeText(path,string(n+1));
if(Storages.readText(path,"utf-8")!=string(n+1)) throw "Private round trip failed";
var good=p+"checks/last-good.tjs";
Storages.writeText(good,"GOOD");
var rejected=false;
try { Storages.writeText("../escape.txt","BAD"); } catch(e) {rejected=true;}
if(!rejected || Storages.readText(good)!="GOOD") throw "Write guard failed";
var d=%["count"=>n+1,"message"=>"中文日本語"];
(Dictionary.saveStruct incontextof d)(p+"checks/state.tjs");
var loaded=Scripts.evalStorage(p+"checks/state.tjs","utf-8");
if(loaded.count!=n+1) throw "Structured save failed";
var w=new Window(); w.setInnerSize(320,200); w.caption="TwinQuill M3 saves: "+string(n+1);
var l=new Layer(w,null); l.type=ltOpaque; l.setSize(320,200); l.setImageSize(320,200); l.visible=true;
l.fillRect(0,0,320,200,n==0?0x3366cc:0x22aa66); w.visible=true;
w.onKeyDown=function(key){if(key==13)w.close();};
"""
    put("m3-save/startup.tjs",save_script)
    auto_exit = b'\nvar brokerExitTicks=0;var brokerExitTimer=new Timer(function(){if(++brokerExitTicks>=6){brokerExitTimer.enabled=false;System.exit(0);}},"");brokerExitTimer.interval=250;brokerExitTimer.enabled=true;'
    put("m3-broker-compressed/data.xp3",xp3([("startup.tjs",visual+auto_exit)]+entries[1:],chained=True))
    put("m3-broker-save/startup.tjs",save_script.encode()+auto_exit)

    vectors = []
    def vector(name, content, status=0, detect=True):
        put("m3-vectors/"+name+".xp3",content)
        vectors.append({"file":name+".xp3","status":status,"detect":detect})
    minimal = [("startup.tjs",b"var a=1;"),("sub/中文日本語.bin",b"RESOURCE"),("empty.bin",b"")]
    vector("raw",xp3(minimal,compressed=False))
    vector("compressed",xp3(minimal))
    vector("chained",xp3([minimal[1],minimal[0],minimal[2]],chained=True))
    vector("protected",xp3(minimal,protected=True),35,False)
    vector("missing",xp3([("other.tjs",b"other")]),31,False)
    vector("duplicate",xp3(minimal,duplicate=True),34,False)
    vector("traversal",xp3([("../startup.tjs",b"BAD")]),34,False)
    vector("segment-bomb",xp3(minimal,bad_segment=True),34,False)
    vector("segment-total",xp3(minimal,member_size=1),34,False)
    valid = xp3(minimal)
    offset = struct.unpack_from("<Q",valid,11)[0]
    invalid=bytearray(valid);struct.pack_into("<Q",invalid,offset+9,16*1024*1024+1)
    vector("index-bomb",bytes(invalid),34,False)
    invalid=bytearray(valid);invalid[-1]^=0xff
    vector("bad-index-zlib",bytes(invalid),34,False)
    invalid=bytearray(valid);invalid[offset]=2
    vector("unsupported",bytes(invalid),33,False)
    invalid=bytearray(valid);invalid[offset]|=0x80;invalid.extend(struct.pack("<Q",offset))
    vector("cycle",bytes(invalid),34,False)
    invalid=bytearray(valid);invalid[offset-1]^=0xff
    vector("bad-resource-zlib",bytes(invalid),0,True)
    raw=xp3(minimal,compressed=False)
    raw_offset=struct.unpack_from("<Q",raw,11)[0]
    invalid=bytearray(raw);struct.pack_into("<H",invalid,raw_offset+9+12+12+22,0xd800)
    vector("invalid-utf16",bytes(invalid),34,False)
    invalid=bytearray(raw);adler=invalid.find(b"adlr",raw_offset);invalid[adler:adler+4]=b"skip"
    vector("missing-adlr",bytes(invalid),34,False)
    invalid=bytearray(raw);segment=invalid.find(b"segm",raw_offset);struct.pack_into("<Q",invalid,segment+12+4,4*1024*1024*1024+1)
    vector("segment-outside",bytes(invalid),34,False)
    invalid=bytearray(raw);invalid.append(0);index_size=struct.unpack_from("<Q",invalid,raw_offset+1)[0];struct.pack_into("<Q",invalid,raw_offset+1,index_size+1)
    vector("index-tail",bytes(invalid),34,False)
    invalid=bytearray(valid);invalid.append(0);packed_size=struct.unpack_from("<Q",invalid,offset+1)[0];struct.pack_into("<Q",invalid,offset+1,packed_size+1)
    vector("zlib-trailing",bytes(invalid),34,False)
    put("m3-revoke/data.xp3",xp3([("startup.tjs",b'var marker=Storages.readText("marker.txt"); TwinQuillHost.onKey=function(){Storages.readText("marker.txt");};'),("marker.txt",b"CACHE")]))
    put("m3-vectors/vectors.json",json.dumps(vectors,ensure_ascii=False,indent=2)+"\n")
    put("m3-vectors/vectors.tsv","".join(f'{v["file"]}\t{v["status"]}\n' for v in vectors))
    put("README.md","""# Authored M3 storage fixtures

Reproduce with python scripts/create_krkr_m3_fixtures.py; GPL-2.0-or-later.
No commercial game data. Display/image assets come from the authored M2 fixture.
m3-vectors is shared by native and Java regression tests.

- m3-loose / m3-compressed: identical M2 display and subdirectory images.
- m3-patches: green verifies patch/loose precedence, Unicode, auto-path and listing.
- m3-cp932: purple verifies explicitly configured CP932 scripts and Japanese names.
- m3-save: blue first run, green subsequent runs for the same imported game.
  Writes UTF-8 text/structured data in the game's private krkr directory. Enter exits.

These exercise M3 storage foundations; KAG and game-state saving are later phases.
""")
    return outputs

def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--output",type=Path,default=ROOT/"tests/fixtures/krkr-m3")
    args = parser.parse_args()
    for name, content in generated().items():
        destination = args.output/name
        destination.parent.mkdir(parents=True,exist_ok=True)
        destination.write_bytes(content)

if __name__ == "__main__":
    main()
