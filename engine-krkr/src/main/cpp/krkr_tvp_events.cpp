/*
 * SPDX-License-Identifier: GPL-2.0-or-later
 * Copyright (C) 2026 TwinQuill contributors
 */
#include "tjsCommHead.h"
#include "tjsDictionary.h"
#include "TimerIntf.h"
#include "krkr_tvp_events.h"
#include "krkr_tjs_execution.h"

#include <algorithm>
#include <chrono>
#include <deque>
#include <vector>

namespace {
std::vector<tTJSNI_Timer*> timers;
struct Event {
    tTJSVariant source, target;
    ttstr name;
    tjs_uint32 tag, flags;
    std::vector<tTJSVariant> args;
};
std::deque<Event> events;
std::deque<Event> delivery;
std::vector<iTJSDispatch2*> async_owners;
constexpr std::size_t kTimerLimit = 128;
constexpr std::size_t kEventLimit = 256;

tjs_uint64 now() {
    using namespace std::chrono;
    return static_cast<tjs_uint64>(duration_cast<milliseconds>(
        steady_clock::now().time_since_epoch()).count()) << TVP_SUBMILLI_FRAC_BITS;
}

bool matches(const Event& event, iTJSDispatch2* source, iTJSDispatch2* target,
             const ttstr& name, tjs_uint32 tag) {
    return event.source.AsObjectNoAddRef() == source && event.target.AsObjectNoAddRef() == target
        && event.name == name && (tag == 0 || event.tag == tag);
}
}

ttstr TVPActionName(TJS_W("action"));

// This port admits no ambient command-line overrides. The upstream constructor
// only queries -laxtimer; the worker and bounded event queue govern scheduling.
bool TVPGetCommandLine(const tjs_char*, tTJSVariant*) { return false; }

tjs_error TJS_INTF_METHOD tTJSNI_Timer::Construct(tjs_int count, tTJSVariant** args,
                                               iTJSDispatch2* owner) {
    if (twinquill::krkr::execution_is_cleanup()) TJS_eTJSError(TJS_W("Cannot create Timer during shutdown"));
    const auto result = tTJSNI_BaseTimer::Construct(count, args, owner);
    if (TJS_FAILED(result)) return result;
    if (timers.size() >= kTimerLimit) TJS_eTJSError(TJS_W("Timer limit exceeded"));
    timers.push_back(this);
    return TJS_S_OK;
}

void TJS_INTF_METHOD tTJSNI_Timer::Invalidate() {
    timers.erase(std::remove(timers.begin(), timers.end(), this), timers.end());
    enabled_ = false;
    tTJSNI_BaseTimer::Invalidate();
}

void tTJSNI_Timer::ShutdownOwner() {
    // A timer action can retain its own object. Invalidate explicitly before
    // clearing the VM globals to break such script/native reference cycles.
    auto* owner = Owner;
    if (owner == nullptr) {
        Invalidate();
        return;
    }
    owner->AddRef();
    try { owner->Invalidate(0, nullptr, nullptr, owner); }
    catch (...) { owner->Release(); throw; }
    owner->Release();
}

void tTJSNI_Timer::SetInterval(tjs_uint64 interval) {
    if (interval > (86400000ULL << TVP_SUBMILLI_FRAC_BITS)) {
        TJS_eTJSError(TJS_W("Timer interval must be between zero and one day"));
    }
    interval_ = interval;
    CancelEvents();
    ResetClock();
}

void tTJSNI_Timer::SetEnabled(bool enabled) {
    enabled_ = enabled;
    CancelEvents();
    ResetClock();
}

void tTJSNI_Timer::ResetClock() {
    next_ = now() + std::max<tjs_uint64>(interval_, 3ULL << TVP_SUBMILLI_FRAC_BITS);
}

void tTJSNI_Timer::Pump(tjs_uint64 tick) {
    if (!enabled_ || interval_ == 0 || tick < next_) return;
    // At most one notification per frame; discarded background time never
    // becomes a callback burst on resume. Upstream Fire applies capacity/mode.
    next_ = tick + std::max<tjs_uint64>(interval_, 3ULL << TVP_SUBMILLI_FRAC_BITS);
    Fire(1);
}

tTJSNativeInstance* tTJSNC_Timer::CreateNativeInstance() { return new tTJSNI_Timer(); }
tTJSNativeClass* TVPCreateNativeClass_Timer() { return new tTJSNC_Timer(); }

void TVPPostEvent(iTJSDispatch2* source, iTJSDispatch2* target, ttstr& name,
                 tjs_uint32 tag, tjs_uint32 flags, tjs_uint count, tTJSVariant* args) {
    if (events.size() + delivery.size() >= kEventLimit || count > 16) TJS_eTJSError(TJS_W("TVP event limit exceeded"));
    Event event{tTJSVariant(source, source), tTJSVariant(target, target), name, tag, flags, {}};
    if (count != 0) event.args.assign(args, args + count);
    events.push_back(std::move(event));
}

tjs_int TVPCountEventsInQueue(iTJSDispatch2* source, iTJSDispatch2* target,
                            const ttstr& name, tjs_uint32 tag) {
    const auto predicate = [&](const Event& event) { return matches(event, source, target, name, tag); };
    return static_cast<tjs_int>(std::count_if(events.begin(), events.end(), predicate)
        + std::count_if(delivery.begin(), delivery.end(), predicate));
}

bool TVPAreEventsInQueue(iTJSDispatch2* source, iTJSDispatch2* target,
                       const ttstr& name, tjs_uint32 tag) {
    return TVPCountEventsInQueue(source, target, name, tag) != 0;
}

tjs_int TVPCancelEvents(iTJSDispatch2* source, iTJSDispatch2* target,
                       const ttstr& name, tjs_uint32 tag) {
    const auto before = events.size() + delivery.size();
    const auto predicate = [&](const Event& event) { return matches(event, source, target, name, tag); };
    events.erase(std::remove_if(events.begin(), events.end(), predicate), events.end());
    delivery.erase(std::remove_if(delivery.begin(), delivery.end(), predicate), delivery.end());
    return static_cast<tjs_int>(before - events.size() - delivery.size());
}

void TVPCancelSourceEvents(iTJSDispatch2* source) {
    const auto predicate = [&](const Event& event) { return event.source.AsObjectNoAddRef() == source; };
    events.erase(std::remove_if(events.begin(), events.end(), predicate), events.end());
    delivery.erase(std::remove_if(delivery.begin(), delivery.end(), predicate), delivery.end());
}

iTJSDispatch2* TVPCreateEventObject(const tjs_char* type, iTJSDispatch2* targetThis,
                                 iTJSDispatch2* target) {
    auto* object = TJSCreateDictionaryObject();
    tTJSVariant typeValue(type), targetValue(targetThis, target);
    object->PropSet(TJS_MEMBERENSURE | TJS_IGNOREPROP, TJS_W("type"), nullptr, &typeValue, object);
    object->PropSet(TJS_MEMBERENSURE | TJS_IGNOREPROP, TJS_W("target"), nullptr, &targetValue, object);
    return object;
}

namespace twinquill::krkr {
void reset_tvp_timer_clocks() { for (auto* timer : timers) timer->ResetClock(); }
void clear_tvp_events() { events.clear(); delivery.clear(); }
void shutdown_tvp_timers() { while (!timers.empty()) timers.back()->ShutdownOwner(); }
void shutdown_tvp_asyncs() {
    while (!async_owners.empty()) {
        auto* owner = async_owners.back();
        tTJSVariant retained(owner, owner);
        owner->Invalidate(0, nullptr, nullptr, owner);
    }
}

void pump_tvp_events() {
    const auto tick = now();
    // No script executes while collecting due timers, so this vector is stable.
    for (auto* timer : timers) timer->Pump(tick);
    // Preserve the Timer interface's exclusive > normal > at-idle ordering.
    const auto priority = [](tjs_uint32 flags) {
        if ((flags & TVP_EPT_PRIO_MASK) == TVP_EPT_EXCLUSIVE) return 0;
        if ((flags & TVP_EPT_PRIO_MASK) == TVP_EPT_IDLE) return 2;
        return 1;
    };
    std::stable_sort(events.begin(), events.end(), [&](const Event& left, const Event& right) {
        return priority(left.flags) < priority(right.flags);
    });
    delivery.swap(events);
    // Newly triggered events stay in events until the next tick. Cancellation
    // searches both queues, including listeners still in this delivery batch.
    while (!delivery.empty()) {
        Event event = std::move(delivery.front());
        delivery.pop_front();
        auto* target = event.target.AsObjectNoAddRef();
        if (target->IsValid(0, nullptr, nullptr, target) != TJS_S_TRUE) continue;
        std::vector<tTJSVariant*> args;
        for (auto& arg : event.args) args.push_back(&arg);
        const auto result = target->FuncCall(0, event.name.c_str(), nullptr, nullptr,
            static_cast<tjs_int>(args.size()), args.data(), target);
        if (TJS_FAILED(result)) TJS_eTJSError(TJS_W("TVP timer callback failed"));
    }
}
}

void TVPAndroidRegisterAsync(iTJSDispatch2* owner) {
    if (twinquill::krkr::execution_is_cleanup()) TJS_eTJSError(TJS_W("Cannot create AsyncTrigger during shutdown"));
    if (async_owners.size() >= 128) TJS_eTJSError(TJS_W("AsyncTrigger limit exceeded"));
    async_owners.push_back(owner);
}
void TVPAndroidUnregisterAsync(iTJSDispatch2* owner) {
    async_owners.erase(std::remove(async_owners.begin(), async_owners.end(), owner), async_owners.end());
}
