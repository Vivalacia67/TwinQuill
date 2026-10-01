# TwinQuill KRKR Direct Integration — M0 Handoff

Status as of 2026-10-02: M0 tasks 1–2 are complete and formally accepted by the user. The user confirmed that the manual Krkr smoke test matched the documented result. Acceptance covers the M0 integration gates and smoke behavior; it does not establish playable game compatibility.

## Checkout and history

- Repository: `https://github.com/Vivalacia67/TwinQuill.git`
- Branch: `refactor/krkr-direct-integration`, based directly on `main` at `890fc77d6c2dffe339fc86571090d90dbf937196`.
- The original 2026-09-24 code HEAD was `caaf2231954199dc533f68081bb703086a290d49`; its tracked handoff was committed as `b348502`. The acceptance fixes below follow that handoff on the same branch.
- Main-based code commits, oldest first: `e8918021dc5b340e2bf80d243d170f630d7e78ea`, `69918f19125880fd3ef005f437992c8ab94a35e0`, `3eb8a447ecccd57070fa5189a09d39476afb42d9`, `cf542e9fe777b2413def3ad3f3d8024c3ad2eff2`, `3f0a0b4b0b7051b647f1a043dd98e13d1bbca579`, `caaf2231954199dc533f68081bb703086a290d49`.
- The user authorized a normal push of M0 code plus this tracked handoff after the gates passed. Verify the remote branch after publication; do not force-push, merge to `main`, or tag.

On another device, after the authorized push is visible:

```sh
git clone https://github.com/Vivalacia67/TwinQuill.git
cd TwinQuill
git fetch origin
git switch --track origin/refactor/krkr-direct-integration
git status --short --branch
```

## Original M0 gates (2026-09-24)

| Gate | Result |
| --- | --- |
| Q0 host checks | 89/89 Python tests; repository hygiene 7,351 paths; source snapshots 7,218 files; `git diff --check` exit 0. |
| Q1 Debug | Fresh `assembleDebug` plus `lintDebug` passed. |
| Q2 instrumentation | 14/14 passed on `emulator-5554`, Android 15 / API 35, x86_64 emulator with arm64 translation support. |
| Q3 KRKR process instrumentation | 2/2 passed on the same API 35 emulator. |
| Q4 Release/APK | Fresh Release build and APK native-library checker passed; both APKs contain `arm64-v8a` and `armeabi-v7a`. |
| Q5 standalone decoder | Rebuilt ARM64 decoder ran through the emulator's arm64 bridge and exited 0; the runner cleaned remote artifacts. This tests the isolated image decoder seam, not the game runtime. |

## Acceptance and Windows follow-up (2026-10-02)

The user reported: “验收完成，与你描述的结果完全一致。” The manual procedure selected a SAF directory containing an ASCII `startup.tjs` with `global.twinQuillM0Result = 42;`, launched it with Kirikiri, displayed the basic test surface, and returned to the library with `引擎返回：NORMAL_EXIT`. The user did not separately identify the manual-test device or ABI.

The follow-up changes address the failures observed during acceptance:

- Restore the exact pinned libjpeg-turbo `doc/change.log` and exempt this audited source file from the generic log ignore rule. The original source checksums are unchanged.
- Pin the Gradle daemon to a locally installed JDK 17. Keep Python virtual environments ignored.
- Handle Android 13+ predictive Back explicitly so the Krkr runtime exits normally on target/API 36.
- Allow 60 seconds for functional engine launches, isolate instrumentation host tasks, await task/runtime cleanup, and cover cleanup during a running ONS script. Complete the post-crash ONS launch before starting another test.
- Document cold boot with SwiftShader and Vulkan disabled when Windows host graphics stall the emulator. Per-machine emulator settings remain outside Git.

| Follow-up check | Result |
| --- | --- |
| Python regression | 89/89 passed on this Windows checkout. |
| Source/repository gates | Repository hygiene and 7,218-file pinned source snapshot checks passed. |
| Acceptance build/APK gates | Debug and Release builds, Android lint, and the native-library checker for both APKs passed. |
| Default instrumentation | Two consecutive runs passed 15/15 on `Medium_Phone`, Android 16 / API 36, x86_64 with arm64 translation, SwiftShader and Vulkan disabled. |
| Isolated Krkr instrumentation | 2/2 passed after a cold boot using the persisted emulator configuration. |
| Manual acceptance | User-confirmed SAF startup, basic rendering, Back, and `NORMAL_EXIT`. |

The extra default test covers removal of a running ONS task; the historical 14/14 result above is unchanged. Local result XML and logs are ignored under `.agent-work/device-test-stability/`; acceptance build and host-check logs are under `.agent-work/m0-acceptance/`.

The acceptance build produced Debug SHA-256 `e6ffc234445c51bdb2d32b535c069bfca025f427f1ae226a8e0d8629b1db0f3e` and Release SHA-256 `2a6403eb7441fdb182480be6fd2a677943c770d0fd0a086ec85629c76a9b7326`. These are separate from the original artifact identities below; rebuilt APK hashes can change.

Reproduction commands from the canonical Windows checkout (Q2/Q3 require a connected compatible Android device; prior evidence used API 35 `emulator-5554`):

```powershell
python -m unittest discover -s tests -v
python scripts/check_repository_hygiene.py
python scripts/verify_source_snapshots.py
git diff --check
.\gradlew.bat --no-daemon --rerun-tasks :launcher-app:assembleDebug :launcher-app:lintDebug
.\gradlew.bat --no-daemon --rerun-tasks :launcher-app:assembleRelease
python scripts/check_apk_native_libraries.py launcher-app/build/outputs/apk/debug/launcher-app-debug.apk launcher-app/build/outputs/apk/release/launcher-app-release-unsigned.apk
.\gradlew.bat --no-daemon :launcher-app:connectedDebugAndroidTest
.\gradlew.bat --no-daemon :launcher-app:connectedDebugAndroidTest -PtwinquillKrkrRuntimeInstrumentation=true
```

For Q5, use an ARM64-capable target. First build the CMake target with `cmake --build engine-krkr/.cxx/Debug/<current-hash>/arm64-v8a --target twinquill_krkr_cocos_image_decode_test`. The runner's `--binary` is the executable output under `engine-krkr/build/intermediates/cxx/Debug/<current-hash>/obj/arm64-v8a/`, not a path under `.cxx`. Resolve the current hash and SDK `adb.exe`; do not reuse the old build hash blindly:

```powershell
python scripts/run_krkr_cocos_image_decode_test.py --adb <SDK-adb.exe> --binary <engine-krkr/build/intermediates/cxx/Debug/<current-hash>/obj/arm64-v8a/twinquill_krkr_cocos_image_decode_test> --libcxx-shared <same-ABI-packaged-libc++_shared.so> --abi arm64-v8a --serial <device-serial>
```

## Original artifact identities and limits (2026-09-24)

- Debug APK SHA-256: `95cbd300ac279ff723e0e5a4dc26abb2b91047cbeedaa8a06b0ea83746897d01`.
- Release APK SHA-256: `21ff0966a7fac3b20623061de2c7a102e97b9b312a8d773246c29457e6f07baa`.
- Q5 ARM64 decoder SHA-256: `d5ce08053bb509a4d7d56703b640f3553d54f6bd90ed65d9711da034ca7c5e08`.
- Q5 packaged ARM64 `libc++_shared.so` SHA-256: `cd61762848882a16c8244c964a6f396c0caa0b440588a210ce9cc4ab0e6d9f0c`.
- There is no recorded API 26 or armv7 runtime proof. The later manual acceptance does not supply physical-device metadata. Q2/Q3/Q5 are scoped test results only; no complete game boot, rendering/input gameplay, or playable-compatibility claim is made.
- Detailed command logs and result XML live under ignored `.omo/evidence/krkr-integration/M0/task-2/final-current-20260924/` on the original device and are not included in GitHub. This handoff preserves the summary and key hashes.

## Provenance and next stage

On 2026-09-24, the user attested “自行生成的” for the launcher vector icon and the queried embedded/generated test BMP, WAV, MP4, PNG, and JPEG fixtures. The attestation is recorded as user-provided provenance, not independent verification or a legal/license conclusion; exact per-asset generation recipes remain undocumented.

M0 sign-off was received on 2026-10-02. The user authorized committing the acceptance updates and starting M1 task 3 (formal TJS entry). Task 3 begins after the M0 acceptance commit; this M0 record does not claim that M1 is complete. Do not treat the ignored `.agent-work/krkr-direct-integration/` notes as GitHub files. Never read or reuse `D:/Project/.agent-work/TwinQuill/HANDOFF.md`, which belongs to the M3 line.
