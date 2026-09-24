# TwinQuill KRKR Direct Integration — M0 Handoff

Status as of 2026-09-24: M0 tasks 1–2 technical gates are complete. This is not formal user acceptance and does not prove a playable KRKR game. Keep roadmap task 2 unchecked until the user signs off.

## Checkout and history

- Repository: `https://github.com/Vivalacia67/TwinQuill.git`
- Branch: `refactor/krkr-direct-integration`, based directly on `main` at `890fc77d6c2dffe339fc86571090d90dbf937196`.
- Current code HEAD before this handoff is published: `caaf2231954199dc533f68081bb703086a290d49`.
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

## M0 result

| Gate | Result |
| --- | --- |
| Q0 host checks | 89/89 Python tests; repository hygiene 7,351 paths; source snapshots 7,218 files; `git diff --check` exit 0. |
| Q1 Debug | Fresh `assembleDebug` plus `lintDebug` passed. |
| Q2 instrumentation | 14/14 passed on `emulator-5554`, Android 15 / API 35, x86_64 emulator with arm64 translation support. |
| Q3 KRKR process instrumentation | 2/2 passed on the same API 35 emulator. |
| Q4 Release/APK | Fresh Release build and APK native-library checker passed; both APKs contain `arm64-v8a` and `armeabi-v7a`. |
| Q5 standalone decoder | Rebuilt ARM64 decoder ran through the emulator's arm64 bridge and exited 0; the runner cleaned remote artifacts. This tests the isolated image decoder seam, not the game runtime. |

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

## Artifact identities and limits

- Debug APK SHA-256: `95cbd300ac279ff723e0e5a4dc26abb2b91047cbeedaa8a06b0ea83746897d01`.
- Release APK SHA-256: `21ff0966a7fac3b20623061de2c7a102e97b9b312a8d773246c29457e6f07baa`.
- Q5 ARM64 decoder SHA-256: `d5ce08053bb509a4d7d56703b640f3553d54f6bd90ed65d9711da034ca7c5e08`.
- Q5 packaged ARM64 `libc++_shared.so` SHA-256: `cd61762848882a16c8244c964a6f396c0caa0b440588a210ce9cc4ab0e6d9f0c`.
- There is no API 26, physical-device, or armv7 runtime proof. Q2/Q3/Q5 are scoped test results only; no complete game boot, rendering/input gameplay, or playable-compatibility claim is made.
- Detailed command logs and result XML live under ignored `.omo/evidence/krkr-integration/M0/task-2/final-current-20260924/` on the original device and are not included in GitHub. This handoff preserves the summary and key hashes.

## Provenance and next stage

On 2026-09-24, the user attested “自行生成的” for the launcher vector icon and the queried embedded/generated test BMP, WAV, MP4, PNG, and JPEG fixtures. The attestation is recorded as user-provided provenance, not independent verification or a legal/license conclusion; exact per-asset generation recipes remain undocumented.

Formal M0 sign-off is still pending. If the user accepts M0, the next planned work is M1 task 3 (formal TJS entry); it has not started. Do not treat the ignored `.agent-work/krkr-direct-integration/` notes as GitHub files. Never read or reuse `D:/Project/.agent-work/TwinQuill/HANDOFF.md`, which belongs to the M3 line.
