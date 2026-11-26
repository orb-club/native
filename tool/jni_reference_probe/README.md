# Sentry/JNI native regression probe

This synthetic app uses `club.orb.audit.sentry_jni_probe`, a closed loopback Sentry
DSN, and no Orb account. It does not install, clear or modify Orb. The blank screen
is intentional: measurements come from Android's native reference table. Use an
Android 14 debug emulator where `Debug.dumpReferenceTables` is available. A denied
or missing dump is a failed experiment, never a pass.

## Dependency source

Orb pins [the JNI 0.14.2 backport](https://github.com/orb-club/native/commit/e15a035060c99aeb6c3e28a763b70479b75f925f)
in `orb-club/native`, at `pkgs/jni`. The fork is based on upstream tag
`jni-v0.14.2` (`108ae181b0bb767239e0df9033c4f253b226a7fc`). Its only runtime change
is the Java initialization guard; generated bindings and native sources are unchanged.
This probe uses the current checkout's `pkgs/jni` as a path dependency, so it tests
the code beside the harness. Use the same fork commit pinned by Orb to reproduce
its dependency. `--stock` instead resolves the unpatched published package.

Every file in the original published JNI 0.14.2 archive matches that upstream tag.
Archive SHA-256: `d2c361082d554d4593c3012e26f6b188f902acd291330f13d6427641a92b3da1`.
The resolved fork package matches the previously tested vendored implementation;
the recorded native measurements below still describe that exact implementation.

Native references, the class-loading lock and thread-local keys belong to the
process. Initialize them once under the plugin class lock; do not reset them on
engine detach while another engine can still use them. The activity/context API
retains JNI 0.14.2 behavior. Upstream [JNI 0.15's fix](https://github.com/dart-lang/native/commit/f75c068e83743586d388b8e5c4152d30a3c52b98)
includes a breaking context/activity migration, so do not force that version
under Sentry's generated 0.14 bindings. Remove the override when Sentry supports
an upstream JNI version with this fix, after rerunning this probe.

## Prepare and run

Use the probe's pinned FVM SDK (`tool/jni_reference_probe/.fvmrc`) and JDK 17.
Run preparation from the fork checkout, not the Orb app repository. Preparation creates a new directory
and resolves dependencies; it does not build or launch anything:

```sh
python3 tool/jni_reference_probe/prepare.py /tmp/orb-jni-patched
# Negative control in a different directory:
python3 tool/jni_reference_probe/prepare.py /tmp/orb-jni-stock --stock
```

After explicit build/emulator authorization, build from the prepared directory:

```sh
fvm flutter build apk --debug --target-platform android-arm64 --dart-define=ENGINE_PROBE=true
```

The diagnostic uses Orb's AGP 8.11.1, Gradle 8.14.4, Kotlin 2.2.20 and NDK
28.2.13676358. `probeMavenCache` is an optional Gradle property pointing to a local
Maven repository when downloads fail; only use artifacts verified against their
publisher's checksums. It is unnecessary when normal repositories are reachable.

Use the device-bound `agent-device` command returned by `device_open` for install
and launch. Assign that command path to `PROBE_DRIVER`, and the selected emulator
identifier to `PROBE_DEVICE`. Each run needs `--relaunch` for a fresh process:

```sh
"$PROBE_DRIVER" install build/app/outputs/flutter-apk/app-debug.apk
# Plugin registration only; no Dart entrypoint or Sentry initialization:
"$PROBE_DRIVER" open club.orb.audit.sentry_jni_probe --relaunch \
  --launch-args --ez --launch-args registrationOnly --launch-args true
# Real Sentry initialization/capture followed by automatic engine/isolate teardown:
"$PROBE_DRIVER" open club.orb.audit.sentry_jni_probe --relaunch
# Engine A remains alive and makes JNI/string/class/Sentry calls after B is destroyed:
"$PROBE_DRIVER" open club.orb.audit.sentry_jni_probe --relaunch \
  --launch-args --ez --launch-args survivor --launch-args true
# Fresh-process synchronized first use: 8 threads, 20 calls each, two rounds:
"$PROBE_DRIVER" open club.orb.audit.sentry_jni_probe --relaunch \
  --launch-args --ez --launch-args firstUse --launch-args true
```

Wait for completion before replacing/relaunching the probe. Capture each run's
logs separately; never clear the device-wide log buffer:

```sh
PROBE_PID=$(adb -s "$PROBE_DEVICE" shell pidof club.orb.audit.sentry_jni_probe)
adb -s "$PROBE_DEVICE" logcat -d --pid="$PROBE_PID" -v brief > /tmp/probe.log
python3 tool/jni_reference_probe/analyze_log.py /tmp/probe.log --mode registration
```

Use `--mode engine`, `survivor` or `first-use` for the corresponding run.
Run parser regression coverage with
`python3 -m unittest discover -s tool/jni_reference_probe -p 'test_*.py'`. The parser
requires the completion marker, every expected snapshot and no failure markers.
It rejects reference growth greater than two after the first sample. Run the stock
registration/engine control with `--expect-leak` to verify at least six additional
references per engine; the same log must fail without that flag. Do not run the
unsafe stock native initializer concurrently merely to provoke an abort.

The single-engine SDK experiment is a separate build without `ENGINE_PROBE=true`.
Launch it with `--activity .MainActivity`, then analyze with `--mode sdk`. It first
holds/releases 32 explicit JNI references to validate measurement sensitivity,
then performs 100 real SDK init/capture/close cycles. Its lifecycle notifications
are synthetic; the other modes perform actual engine destruction. All native
Sentry captures must return a nonempty ID; no message reaches a production service.

## Evidence and limitations

Measured on Pixel 6 Pro emulator, Android 14/arm64, Flutter 3.47.5,
Sentry Flutter 9.30.1, JNI 0.14.2, Java Sentry 8.56.0, on 2026-11-26:

| Experiment | Unchanged JNI | Patched JNI |
| --- | --- | --- |
| Explicit reference control | +32 held, -32 released | Same native/Dart reference APIs |
| 100 Sentry init/close cycles in one engine | 415 first / 415 last | Original baseline; not rerun |
| 30 plugin registration/destruction cycles | 386 first / 560 last | 386 first / 386 last |
| 30 real Sentry init/engine destruction cycles | 390 first / 579 last | 389 first / 389 last |
| Live engine survives 30 sibling teardowns | Not run | 405 first / 406 last; all JNI/capture checks passed |
| 320 concurrent first-use calls, eight threads | Not run (unsafe native race) | 350 baseline / 356 after each round; exactly six allocated |

The registration control isolates the six references allocated by repeated JNI
initialization: four cached Java classes, one classloader and one application
context. No sampled run aborted. Measurements verify reference ownership, not a
production crash reproduction. These are debug, headless-engine experiments;
Orb release builds, physical/OEM devices, 32-bit processes and Android 10/13/15/16
were not run. Existing 0.14.2 shared activity/context behavior is preserved, not
redesigned into JNI 0.15's per-engine API.

ORB2-1479, ORB2-1484, ORB2-1495 and ORB2-1516 share an ART `AddGlobalRef` abort
inside Sentry initialization, but retained events lack the actual ART abort message
and engine-count evidence. At six references per engine this leak alone would need
thousands of engines to exhaust a 51,200-entry table. The fix removes a demonstrated
contributor; it does not establish all four crash groups are resolved. Keep the
tickets open until diagnostics or post-release evidence establish attribution.
