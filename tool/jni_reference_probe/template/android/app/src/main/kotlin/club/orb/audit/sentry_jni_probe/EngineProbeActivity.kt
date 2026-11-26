package club.orb.audit.sentry_jni_probe

import android.app.Activity
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.Debug
import android.util.Log
import io.flutter.FlutterInjector
import io.flutter.embedding.engine.FlutterEngine
import io.flutter.embedding.engine.dart.DartExecutor
import io.flutter.plugin.common.MethodChannel

class EngineProbeActivity : Activity() {
    private val handler = Handler(Looper.getMainLooper())
    private var engine: FlutterEngine? = null
    private var sibling: FlutterEngine? = null
    private var iteration = 0
    private var siblingIteration = 0
    private val survivor get() = intent.getBooleanExtra("survivor", false)
    private var stopped = false
    private val registrationOnly get() = intent.getBooleanExtra("registrationOnly", false)
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Log.i("OrbJniProbe", "MODE registrationOnly=$registrationOnly")
        snapshot("engine_baseline")
        if (intent.getBooleanExtra("firstUse", false)) {
            concurrentFirstUse()
        } else {
            nextEngine()
        }
    }
    private fun fail(message: String, failure: Throwable? = null) {
        stopped = true
        handler.removeCallbacksAndMessages(null)
        sibling?.destroy()
        sibling = null
        engine?.destroy()
        engine = null
        Log.e("OrbJniProbe", "ENGINE_PROBE_FAILED $message", failure)
    }
    private fun snapshot(phase: String) {
        Log.i("OrbJniProbe", "SNAPSHOT_BEGIN $phase")
        try {
            Debug::class.java.getDeclaredMethod("dumpReferenceTables").invoke(null)
            Log.i("OrbJniProbe", "SNAPSHOT_END $phase")
        } catch (failure: Throwable) {
            fail("REFERENCE_DUMP", failure)
        }
    }
    private fun finishEngine(next: FlutterEngine) {
        handler.removeCallbacksAndMessages(null)
        handler.postDelayed({
            next.destroy()
            engine = null
            System.gc()
            System.runFinalization()
            handler.postDelayed({
                System.gc()
                snapshot("engine_$iteration")
                if (!stopped) {
                    if (iteration < 30) nextEngine()
                    else Log.i("OrbJniProbe", "ENGINE_PROBE_COMPLETED")
                }
            }, 1500)
        }, 100)
    }
    private fun nextEngine() {
        if (stopped) return
        iteration++
        val next = FlutterEngine(this)
        engine = next
        handler.postDelayed({ fail("TIMEOUT engine_$iteration") }, 30000)
        if (registrationOnly) {
            finishEngine(next)
            return
        }
        val channel = MethodChannel(next.dartExecutor.binaryMessenger, "orb.audit/jni")
        channel.setMethodCallHandler { call, result ->
                when (call.method) {
                    "engineReady" -> {
                        result.success(null)
                        if (survivor) nextSibling(channel) else finishEngine(next)
                    }
                    "status" -> {
                        val status = call.arguments.toString()
                        result.success(null)
                        if (status.contains("FAILED")) fail(status)
                        else Log.i("OrbJniProbe", status)
                    }
                    else -> result.notImplemented()
                }
            }
        next.dartExecutor.executeDartEntrypoint(DartExecutor.DartEntrypoint.createDefault())
    }
    private fun concurrentFirstUse() {
        val type = Class.forName("com.github.dart_lang.jni.JniPlugin")
        val setup = type.getDeclaredMethod("setup", android.content.Context::class.java)
        setup.isAccessible = true
        repeat(2) { round ->
            val ready = java.util.concurrent.CountDownLatch(8)
            val start = java.util.concurrent.CountDownLatch(1)
            val done = java.util.concurrent.CountDownLatch(8)
            val error = java.util.concurrent.atomic.AtomicReference<Throwable>()
            repeat(8) {
                Thread {
                    try {
                        val plugin = type.getDeclaredConstructor().newInstance()
                        ready.countDown()
                        check(start.await(5, java.util.concurrent.TimeUnit.SECONDS))
                        repeat(20) { setup.invoke(plugin, applicationContext) }
                    } catch (failure: Throwable) {
                        error.compareAndSet(null, failure)
                    } finally {
                        done.countDown()
                    }
                }.start()
            }
            if (!ready.await(5, java.util.concurrent.TimeUnit.SECONDS)) {
                start.countDown()
                fail("FIRST_USE_READY_TIMEOUT")
                return
            }
            start.countDown()
            if (!done.await(5, java.util.concurrent.TimeUnit.SECONDS) || error.get() != null) {
                fail("FIRST_USE_FAILED", error.get())
                return
            }
            System.gc()
            System.runFinalization()
            snapshot("first_use_$round")
            if (stopped) return
        }
        Log.i("OrbJniProbe", "FIRST_USE_PROBE_COMPLETED")
    }
    private fun nextSibling(primaryChannel: MethodChannel) {
        handler.removeCallbacksAndMessages(null)
        siblingIteration++
        handler.postDelayed({ fail("SURVIVOR_TIMEOUT $siblingIteration") }, 30000)
        val next = FlutterEngine(this)
        sibling = next
        MethodChannel(next.dartExecutor.binaryMessenger, "orb.audit/secondary")
            .setMethodCallHandler { call, result ->
                result.success(null)
                if (call.method != "ready") {
                    fail("SECONDARY ${call.arguments}")
                } else {
                    handler.postDelayed({
                        next.destroy()
                        sibling = null
                        System.gc()
                        System.runFinalization()
                        primaryChannel.invokeMethod("verifySurvivor", null, object : MethodChannel.Result {
                            override fun success(result: Any?) {
                                if (result != "ok") { fail("SURVIVOR_INVALID_RESULT"); return }
                                handler.removeCallbacksAndMessages(null)
                                handler.postDelayed({
                                    snapshot("survivor_$siblingIteration")
                                    if (!stopped) {
                                        if (siblingIteration < 30) nextSibling(primaryChannel)
                                        else Log.i("OrbJniProbe", "SURVIVOR_PROBE_COMPLETED")
                                    }
                                }, 1500)
                            }
                            override fun error(code: String, message: String?, details: Any?) {
                                fail("SURVIVOR $code $message")
                            }
                            override fun notImplemented() { fail("SURVIVOR_NOT_IMPLEMENTED") }
                        })
                    }, 100)
                }
            }
        next.dartExecutor.executeDartEntrypoint(DartExecutor.DartEntrypoint(
            FlutterInjector.instance().flutterLoader().findAppBundlePath(), "secondaryMain"))
    }
    override fun onDestroy() {
        stopped = true
        handler.removeCallbacksAndMessages(null)
        sibling?.destroy()
        sibling = null
        engine?.destroy()
        engine = null
        super.onDestroy()
    }
}
