# Fixed names are invoked by a separate app_process, not by normal Java edges.
# Keeping these bounded adapters is deliberate; do not replace with keepnames only.
-keep class nl.moto.ota.rootprobe.** { *; }

# These form the app's structured engine/platform API, including observation-only
# recovery and the integration-test boundary. Retaining them also prevents a
# separately compiled signed instrumentation APK calling inlined-away methods.
-keep class nl.moto.ota.core.** { *; }
-keep class nl.moto.ota.platform.** { *; }
-keep class nl.moto.ota.ui.AppSettings { *; }

# AndroidJUnitRunner uses this shared runtime API. R8 otherwise fully inlines it
# into the app, while the separate test APK still needs its real public class.
-keep class androidx.tracing.Trace { *; }

# The separately linked Kotlin test/runtime code must share the same real stdlib
# and coroutine API, not references to methods R8 has inlined out of the app.
-keep class kotlin.** { *; }
-keep class kotlinx.coroutines.** { *; }

# Compose exposes these interfaces to accessibility/test root discovery.
-keep interface androidx.compose.ui.node.RootForTest { *; }
-keep interface androidx.compose.ui.platform.ViewRootForTest { *; }

# Root helpers must resolve the actual platform boot-classpath Binder interfaces.
# Renaming the bundled compile-time AIDL copies changes that resolution contract.
-keep class android.os.IUpdateEngine** { *; }
-keep class android.os.IUpdateEngineCallback** { *; }
-keep interface android.os.IUpdateEngine** { *; }
-keep interface android.os.IUpdateEngineCallback** { *; }

# JNI exports use this exact binary class/method name, also under app_process.
-keep class nl.moto.ota.platform.staging.AtomicStagePublisher { *; }

# Existing WorkManager databases contain these exact worker names. Keep the
# public two-argument constructor used by the default reflective WorkerFactory.
-keep class nl.moto.ota.platform.download.OtaDownloadWorker {
    public <init>(android.content.Context, androidx.work.WorkerParameters);
}
-keep class nl.moto.ota.platform.monitoring.MotorolaMonitorWorker {
    public <init>(android.content.Context, androidx.work.WorkerParameters);
}
-keep class nl.moto.ota.platform.update.PostUpdateObserverWorker {
    public <init>(android.content.Context, androidx.work.WorkerParameters);
}

-keepattributes InnerClasses,EnclosingMethod,Signature,*Annotation*
