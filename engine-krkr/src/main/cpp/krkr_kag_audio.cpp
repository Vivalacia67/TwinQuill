/* SPDX-License-Identifier: GPL-2.0-or-later */
#include "krkr_kag_audio.h"
#include "tjsError.h"
#include <cstdint>
#include <stdexcept>
namespace {
JavaVM* vm=nullptr;
jclass audio=nullptr;
jmethodID open_method=nullptr, control_method=nullptr;
JNIEnv* environment() {
    JNIEnv* env=nullptr;
    if(!vm || vm->GetEnv(reinterpret_cast<void**>(&env),JNI_VERSION_1_6)!=JNI_OK)
        throw std::invalid_argument("Audio needs Java script worker");
    return env;
}
unsigned le16(const char* p) { return (unsigned char)p[0]|((unsigned char)p[1]<<8); }
std::uint32_t le32(const char* p) { return le16(p)|(le16(p+2)<<16); }
void check(JNIEnv* env) {
    if(env->ExceptionCheck()) { env->ExceptionClear(); TJS::TJS_eTJSError(TJS_W("Android PCM output failed")); }
}
}
void TVPInitializeAndroidAudio(JNIEnv* env) {
    if(audio) return;
    if (env->GetJavaVM(&vm) != JNI_OK) throw std::invalid_argument("Missing audio VM");
    auto local = env->FindClass("io/github/twinquill/engine/krkr/KrkrPcmAudio");
    if (!local) { env->ExceptionClear(); throw std::invalid_argument("Missing PCM backend"); }
    auto open = env->GetStaticMethodID(local, "open", "(II[B)I");
    auto control = open ? env->GetStaticMethodID(local, "control", "(III)I") : nullptr;
    if (!open || !control) {
        env->ExceptionClear(); env->DeleteLocalRef(local);
        throw std::invalid_argument("Missing PCM methods");
    }
    auto global = static_cast<jclass>(env->NewGlobalRef(local));
    env->DeleteLocalRef(local);
    if (!global) { env->ExceptionClear(); throw std::invalid_argument("PCM class allocation failed"); }
    open_method = open; control_method = control; audio = global;
}
int TVPOpenAndroidWave(std::string_view source) {
    if(source.size()<12 || source.substr(0,4)!="RIFF" || source.substr(8,4)!="WAVE"
        || le32(source.data()+4)!=source.size()-8) throw std::invalid_argument("Malformed WAV header");
    unsigned channels=0, rate=0; std::string_view pcm; bool data_seen=false;
    for(std::size_t offset=12; offset<source.size();) {
        if(source.size()-offset<8) throw std::invalid_argument("Truncated WAV chunk");
        const auto size=le32(source.data()+offset+4);
        if(size>source.size()-offset-8) throw std::invalid_argument("WAV chunk outside resource");
        auto chunk=source.substr(offset+8,size);
        if(source.substr(offset,4)=="fmt ") {
            if(channels || size<16 || le16(chunk.data())!=1 || le16(chunk.data()+14)!=16)
                throw std::invalid_argument("Only PCM16 WAV is supported");
            channels=le16(chunk.data()+2); rate=le32(chunk.data()+4);
            if((channels!=1 && channels!=2) || rate<8000 || rate>48000
                || le16(chunk.data()+12)!=channels*2 || le32(chunk.data()+8)!=rate*channels*2)
                throw std::invalid_argument("Invalid WAV PCM format");
        } else if(source.substr(offset,4)=="data") {
            if(data_seen) throw std::invalid_argument("Duplicate WAV data"); data_seen=true; pcm=chunk;
        }
        offset+=8+size+(size&1);
        if(offset>source.size()) throw std::invalid_argument("Truncated WAV padding");
    }
    if(!channels || pcm.empty() || pcm.size()%(channels*2) || pcm.size()>8*1024*1024) throw std::invalid_argument("Invalid WAV samples");
    auto* env=environment(); auto bytes=env->NewByteArray(static_cast<jsize>(pcm.size()));
    if(!bytes) { env->ExceptionClear(); throw std::invalid_argument("WAV allocation failed"); }
    env->SetByteArrayRegion(bytes,0,pcm.size(),reinterpret_cast<const jbyte*>(pcm.data()));
    if(env->ExceptionCheck()) { env->DeleteLocalRef(bytes); check(env); }
    const int id=env->CallStaticIntMethod(audio,open_method,rate,channels,bytes);
    env->DeleteLocalRef(bytes); check(env); return id;
}
int TVPControlAndroidAudio(int id,int action,int value) {
    auto* env=environment(); const int result=env->CallStaticIntMethod(audio,control_method,id,action,value);
    check(env); return result;
}
