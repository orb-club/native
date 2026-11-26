// Standalone native experiment: no Orb account or production DSN.
// ignore_for_file: invalid_use_of_internal_member, implementation_imports
import 'dart:async';
import 'dart:developer' as developer;

import 'package:flutter/services.dart';
import 'package:flutter/widgets.dart';
import 'package:jni/jni.dart';
import 'package:sentry_flutter/sentry_flutter.dart';
import 'package:sentry_flutter/src/native/java/binding.dart' as native;

const channel = MethodChannel('orb.audit/jni');
const cycleCount = int.fromEnvironment('PROBE_CYCLES', defaultValue: 100);

Future<void> main() async {
  WidgetsFlutterBinding.ensureInitialized();
  runApp(const SizedBox.shrink());
  if (const bool.fromEnvironment('ENGINE_PROBE')) {
    try {
      await initialize();
      channel.setMethodCallHandler((call) async {
        if (call.method != 'verifySurvivor') {
          throw StateError('Unknown probe call');
        }
        using((arena) {
          JClass.forName('java/lang/String').releasedBy(arena);
          final text = 'surviving-engine'.toJString()..releasedBy(arena);
          if (text.toDartString() != 'surviving-engine' ||
              !native.Sentry.isEnabled()) {
            throw StateError(
              'JNI or Sentry unavailable after sibling teardown',
            );
          }
          final id = native.Sentry.captureMessage(text)..releasedBy(arena);
          final value = id.toString$1().use((value) => value?.toDartString());
          if (value == null || value == const SentryId.empty().toString()) {
            throw StateError('Native capture failed after sibling teardown');
          }
        });
        return 'ok';
      });
      using((arena) {
        final message = 'engine-probe'.toJString()..releasedBy(arena);
        final id = native.Sentry.captureMessage(message)..releasedBy(arena);
        final text = id.toString$1().use((value) => value?.toDartString());
        if (text == null || text == const SentryId.empty().toString()) {
          throw StateError('Native capture failed before engine teardown');
        }
      });
      // Intentionally no Sentry.close: test automatic owner-isolate teardown.
      await channel.invokeMethod<void>('engineReady');
    } catch (error) {
      await channel.invokeMethod<void>('status', 'ENGINE_PROBE_FAILED $error');
    }
    return;
  }
  try {
    await probe();
    await channel.invokeMethod<void>('status', 'PROBE_COMPLETED');
  } catch (error, stack) {
    developer.log('Probe failed', error: error, stackTrace: stack);
    await channel.invokeMethod<void>('status', 'PROBE_FAILED $error');
  } finally {
    await Sentry.close();
  }
}

Future<void> snapshot(String phase) =>
    channel.invokeMethod<void>('snapshot', {'phase': phase});

Future<void> settle() async {
  // Requests are hints, not proof of finalizer completion. Compare snapshots
  // separately from the deliberately retained/released reference control.
  for (var i = 0; i < 3; i++) {
    final scratch = List.generate(4096, (j) => 'probe-scratch-$j-$i');
    if (scratch.length != 4096) throw StateError('allocation control');
    await channel.invokeMethod<void>('gc');
    await Future<void>.delayed(const Duration(milliseconds: 200));
  }
}

Future<void> initialize() async {
  await SentryFlutter.init((options) {
    // Local closed port: synthetic test messages cannot reach Sentry servers.
    options.dsn = 'http://public@127.0.0.1:9/1';
    options.debug = false;
    options.tracesSampleRate = 0.1;
    options.enableAutoSessionTracking = false;
  });
  final context = Jni.getCachedApplicationContext();
  try {
    if (context.isNull) throw StateError('Application context unavailable');
  } finally {
    context.release();
  }
  if (!native.Sentry.isEnabled()) {
    throw StateError('Native Sentry unexpectedly disabled');
  }
}

Future<void> probe() async {
  await initialize();
  await Sentry.close();
  await settle();
  await snapshot('control_before');
  final held = List.generate(32, (i) => 'probe-held-$i'.toJString());
  await snapshot('control_held_32');
  for (final item in held) {
    item.release();
  }
  await snapshot('control_released');

  final deadline = DateTime.now().add(const Duration(minutes: 8));
  for (var cycle = 1; cycle <= cycleCount; cycle++) {
    if (DateTime.now().isAfter(deadline)) {
      throw StateError('Probe deadline reached before cycle $cycle');
    }
    await initialize();
    // Synthetic Flutter lifecycle only; this does not recreate FlutterEngine.
    WidgetsBinding.instance.handleAppLifecycleStateChanged(
      AppLifecycleState.resumed,
    );
    WidgetsBinding.instance.handleAppLifecycleStateChanged(
      AppLifecycleState.detached,
    );
    await Future<void>.delayed(const Duration(milliseconds: 10));
    WidgetsBinding.instance.handleAppLifecycleStateChanged(
      AppLifecycleState.resumed,
    );
    using((arena) {
      JClass.forName('java/lang/String').releasedBy(arena);
      final message = 'probe-cycle-$cycle'.toJString()..releasedBy(arena);
      final id = native.Sentry.captureMessage(message)..releasedBy(arena);
      final value = id.toString$1().use((value) => value?.toDartString());
      if (value == null || value == const SentryId.empty().toString()) {
        throw StateError('Native capture failed at cycle $cycle');
      }
    });
    await Sentry.close();
    await settle();
    if (cycle == 1 || cycle % 10 == 0) await snapshot('cycle_$cycle');
  }
}

@pragma('vm:entry-point')
Future<void> secondaryMain() async {
  WidgetsFlutterBinding.ensureInitialized();
  const secondary = MethodChannel('orb.audit/secondary');
  try {
    using((arena) {
      JClass.forName('java/lang/String').releasedBy(arena);
      final text = 'second-engine'.toJString()..releasedBy(arena);
      if (text.toDartString() != 'second-engine') {
        throw StateError('JNI unavailable');
      }
    });
    await secondary.invokeMethod<void>('ready');
  } catch (error) {
    await secondary.invokeMethod<void>('failed', error.toString());
  }
}
