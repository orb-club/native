package club.orb.audit.sentry_jni_probe

import io.flutter.embedding.android.FlutterActivity
import io.flutter.embedding.engine.FlutterEngine
import io.flutter.plugin.common.MethodChannel
import android.os.Debug
import android.util.Log

class MainActivity : FlutterActivity() {
    override fun configureFlutterEngine(flutterEngine: FlutterEngine) {
        super.configureFlutterEngine(flutterEngine)
        MethodChannel(flutterEngine.dartExecutor.binaryMessenger, "orb.audit/jni")
            .setMethodCallHandler { call, result ->
                when (call.method) {
                    "snapshot" -> {
                        val phase = call.argument<String>("phase") ?: "unknown"
                        Log.i("OrbJniProbe", "SNAPSHOT_BEGIN $phase")
                        try {
                            Debug::class.java.getDeclaredMethod("dumpReferenceTables").invoke(null)
                            Log.i("OrbJniProbe", "SNAPSHOT_END $phase")
                            result.success(null)
                        } catch (failure: Throwable) {
                            Log.e("OrbJniProbe", "SNAPSHOT_UNAVAILABLE $phase", failure)
                            result.error("NO_REFERENCE_DUMP", failure.toString(), null)
                        }
                    }
                    "gc" -> {
                        System.gc()
                        System.runFinalization()
                        result.success(null)
                    }
                    "status" -> {
                        Log.i("OrbJniProbe", call.arguments.toString())
                        result.success(null)
                    }
                    else -> result.notImplemented()
                }
            }
    }
}
