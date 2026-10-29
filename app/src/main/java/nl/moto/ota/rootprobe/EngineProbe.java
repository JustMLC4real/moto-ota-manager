package nl.moto.ota.rootprobe;

import android.os.Build;
import android.os.IBinder;
import android.os.IUpdateEngine;
import android.os.IUpdateEngineCallback;
import android.os.Process;
import android.os.SystemClock;

import java.lang.reflect.Method;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * A bounded diagnostic main class for a separate, explicitly requested root process.
 * It subscribes to UpdateEngine callbacks and unsubscribes; no update operation is
 * invoked. The generated interface retains the complete pinned AOSP method order
 * so that no Binder transaction numbers are guessed.
 *
 * Source: AOSP system/update_engine 4591637f51644f5429f9e2fe589ab22b92f61842,
 * binder_bindings/android/os/IUpdateEngine*.aidl and aosp/binder_service_android.cc.
 * Reflective ServiceManager access is confined to this root main. No hidden-API
 * exemptions, VM policy changes, files, properties or settings are written.
 * Reflection, SELinux, OEM incompatibility and missing callbacks yield UNKNOWN.
 */
public final class EngineProbe {
    private static final String SERVICE = "android.os.UpdateEngineService";
    private static final String DESCRIPTOR = "android.os.IUpdateEngine";
    private static final String SOURCE = "UpdateEngine.bind(callback)";
    private static final long CALLBACK_TIMEOUT_MILLIS = 3_000;
    private static final long PROCESS_DEADLINE_MILLIS = 4_500;
    private static final AtomicBoolean EMITTED = new AtomicBoolean();

    private EngineProbe() {}

    /** Pure guard, independently testable without Android process APIs. */
    public static boolean supportsRuntime(int uid, int sdk) {
        return uid == 0 && sdk >= 33 && sdk <= 37;
    }

    /** AOSP UpdateStatus numeric values, never a guessed transaction number. */
    public static String statusName(int code) {
        switch (code) {
            case 0: return "IDLE";
            case 1: return "CHECKING_FOR_UPDATE";
            case 2: return "UPDATE_AVAILABLE";
            case 3: return "DOWNLOADING";
            case 4: return "VERIFYING";
            case 5: return "FINALIZING";
            case 6: return "UPDATED_NEED_REBOOT";
            case 7: return "REPORTING_ERROR_EVENT";
            case 8: return "ATTEMPTING_ROLLBACK";
            case 9: return "DISABLED";
            case 10: return "NEED_PERMISSION_TO_UPDATE";
            case 11: return "CLEANUP_PREVIOUS_UPDATE";
            default: return "UNKNOWN";
        }
    }

    /** A nonzero fresh completion is evidence requiring review, even after IDLE. */
    public static String classifyStatus(int code, float progress, Integer completionCode) {
        if (completionCode != null && completionCode != 0) return "ERROR";
        if (!Float.isFinite(progress) || progress < 0 || progress > 1) return "UNKNOWN";
        return statusName(code);
    }

    public static void main(String[] args) {
        if (args.length != 0 || !supportsRuntime(Process.myUid(), Build.VERSION.SDK_INT)) {
            emit(Result.unknown("UNSUPPORTED_RUNTIME"));
            return;
        }
        // Prefer the running firmware's hidden interface bindings, never a
        // guessed OEM transaction layout. Parent-first loading resolves these
        // from the boot framework; an APK-only definition is not sufficient.
        if (IUpdateEngine.class.getClassLoader() != Build.class.getClassLoader()
                || IUpdateEngineCallback.class.getClassLoader() != Build.class.getClassLoader()) {
            emit(Result.unknown("FRAMEWORK_BINDINGS_UNAVAILABLE"));
            return;
        }

        // Binder operations themselves are synchronous and can hang in an OEM
        // service. The watchdog bounds the entire dedicated process, including
        // getService, bind and unbind. Death removes any surviving callback.
        Thread deadline = new Thread(() -> {
            try {
                Thread.sleep(PROCESS_DEADLINE_MILLIS);
            } catch (InterruptedException finished) {
                return;
            }
            emit(Result.unknown("PROBE_DEADLINE"));
            Runtime.getRuntime().halt(0);
        }, "moto-engine-probe-deadline");
        deadline.setDaemon(true);
        deadline.start();

        IUpdateEngine engine = null;
        Callback callback = null;
        boolean bindAttempted = false;
        boolean bound = false;
        boolean unbound = false;
        String failure = null;
        Result result;
        try {
            // ServiceManager is not in the public SDK. A denied lookup is an
            // unavailable diagnostic source, never a reason to change VM policy.
            Class<?> serviceManager = Class.forName("android.os.ServiceManager");
            Method getService = serviceManager.getMethod("getService", String.class);
            Object value = getService.invoke(null, SERVICE);
            if (!(value instanceof IBinder)) {
                failure = "SERVICE_UNAVAILABLE";
            } else {
                IBinder binder = (IBinder) value;
                if (!DESCRIPTOR.equals(binder.getInterfaceDescriptor())) {
                    failure = "INTERFACE_MISMATCH";
                } else {
                    engine = IUpdateEngine.Stub.asInterface(binder);
                    callback = new Callback();
                    bindAttempted = true;
                    // These are the only UpdateEngine calls in this helper.
                    bound = engine.bind(callback);
                    if (!bound) failure = "BIND_REJECTED";
                    else if (!callback.await()) failure = "NO_CALLBACK";
                }
            }
        } catch (ReflectiveOperationException | SecurityException inaccessible) {
            failure = "LOOKUP_DENIED";
        } catch (Throwable unavailable) {
            failure = "BINDER_UNAVAILABLE";
        } finally {
            if (bindAttempted && engine != null && callback != null) {
                try {
                    unbound = engine.unbind(callback);
                } catch (Throwable unavailable) {
                    unbound = false;
                }
            }
        }

        if (failure != null) result = Result.unknown(failure);
        else if (!bound || !unbound || callback == null) result = Result.unknown("UNBIND_UNCONFIRMED");
        else result = callback.result();
        emit(result);
        deadline.interrupt();
        // Binder pool threads may otherwise keep app_process alive. Exit only
        // this dedicated probe process after flushing its single JSON record.
        System.exit(0);
    }

    private static void emit(Result result) {
        if (EMITTED.compareAndSet(false, true)) {
            System.out.println(result.json());
            System.out.flush();
        }
    }

    private static final class Callback extends IUpdateEngineCallback.Stub {
        private final CountDownLatch received = new CountDownLatch(1);
        private int code = -1;
        private float progress = Float.NaN;
        private Integer completionCode;
        private long checkedNanos;
        private boolean malformed;

        @Override public synchronized void onStatusUpdate(int statusCode, float percentage) {
            if ("UNKNOWN".equals(statusName(statusCode)) || !Float.isFinite(percentage) || percentage < 0 || percentage > 1) {
                malformed = true;
            } else {
                code = statusCode;
                progress = percentage;
            }
            checkedNanos = SystemClock.elapsedRealtimeNanos();
            received.countDown();
        }

        @Override public synchronized void onPayloadApplicationComplete(int errorCode) {
            completionCode = errorCode;
            checkedNanos = SystemClock.elapsedRealtimeNanos();
            received.countDown();
        }

        boolean await() throws InterruptedException {
            return received.await(CALLBACK_TIMEOUT_MILLIS, TimeUnit.MILLISECONDS);
        }

        synchronized Result result() {
            if (malformed || checkedNanos <= 0) return Result.unknown("MALFORMED_CALLBACK");
            String status = classifyStatus(code, progress, completionCode);
            if ("UNKNOWN".equals(status)) return Result.unknown("NO_STATUS_CALLBACK");
            return new Result(status, code, Float.isFinite(progress) ? (double) progress * 100.0 : null,
                completionCode, checkedNanos, "");
        }
    }

    private static final class Result {
        private final String status;
        private final int code;
        private final Double percent;
        private final Integer completionCode;
        private final long checkedNanos;
        private final String reason;

        Result(String status, int code, Double percent, Integer completionCode, long checkedNanos, String reason) {
            this.status = status;
            this.code = code;
            this.percent = percent;
            this.completionCode = completionCode;
            this.checkedNanos = checkedNanos;
            this.reason = reason;
        }

        static Result unknown(String reason) {
            return new Result("UNKNOWN", -1, null, null, SystemClock.elapsedRealtimeNanos(), reason);
        }

        String json() {
            // All text fields are fixed allowlisted constants. No error messages,
            // arguments, URLs, user data or service dumps reach stdout.
            return "{\"schema\":1,\"status\":\"" + status + "\",\"statusCode\":" + code
                + ",\"percent\":" + percent + ",\"completionCode\":" + completionCode
                + ",\"source\":\"" + SOURCE + "\",\"checkedNanos\":" + checkedNanos
                + ",\"reason\":\"" + reason + "\"}";
        }
    }
}
