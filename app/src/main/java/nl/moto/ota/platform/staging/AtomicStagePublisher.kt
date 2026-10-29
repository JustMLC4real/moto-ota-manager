package nl.moto.ota.platform.staging

import android.system.Os
import android.system.OsConstants
import nl.moto.ota.core.staging.*
import java.io.File

/** JNI exposes one fixed-directory no-replace publication operation only. */
internal object AtomicStagePublisher {
    /** Load from the application's classloader, not the separate instrumentation APK. */
    internal fun loadForSandboxTests() {
        if (android.os.Process.myUid() < 10_000 || android.os.Build.HARDWARE !in setOf("ranchu", "goldfish"))
            throw StageFailure(StageError.STAGING_RUNTIME_UNSUPPORTED)
        System.loadLibrary("moto_stage_publish")
    }

    fun loadForRoot(nativeLibraryDirectory: String, apkPath: String) {
        if (android.os.Process.myUid() != 0) throw StageFailure(StageError.STAGING_RUNTIME_UNSUPPORTED)
        val apk = File(apkPath)
        val directory = File(nativeLibraryDirectory)
        val file = File(directory, "libmoto_stage_publish.so")
        if (!apkPath.matches(Regex("/data/app/[A-Za-z0-9/._~=+\\-]+/base[.]apk")) ||
            apk.canonicalPath != apk.path || directory.canonicalPath != directory.path ||
            directory.parentFile?.name != "lib" || directory.parentFile?.parentFile != apk.parentFile ||
            directory.name !in setOf("arm64", "x86_64") || file.canonicalFile != file || !file.isFile)
            throw StageFailure(StageError.STAGING_PATH_REJECTED)
        val stat = Os.lstat(file.path)
        if (!OsConstants.S_ISREG(stat.st_mode) || stat.st_uid !in setOf(0, 1000) || stat.st_mode and 0x012 != 0)
            throw StageFailure(StageError.STAGING_PATH_REJECTED)
        try { System.load(file.path) }
        catch (_: LinkageError) { throw StageFailure(StageError.STAGING_ATOMIC_PUBLISH_UNSUPPORTED) }
    }

    fun publish(temporary: File, destination: File, bytes: Long) {
        if (temporary.parentFile?.canonicalPath != MotorolaStagePolicy.TEMPORARY_DIRECTORY ||
            destination.parentFile?.canonicalPath != MotorolaStagePolicy.DESTINATION_DIRECTORY)
            throw StageFailure(StageError.STAGING_PATH_REJECTED)
        when (publishNative(temporary.name, destination.name, bytes)) {
            0 -> Unit
            1 -> throw StageFailure(StageError.STAGING_DESTINATION_EXISTS)
            2 -> throw StageFailure(StageError.STAGING_ATOMIC_PUBLISH_UNSUPPORTED)
            4 -> throw StageFailure(StageError.STAGING_PATH_REJECTED)
            5 -> throw StageFailure(StageError.STAGING_DURABILITY_UNPROVEN)
            else -> throw StageFailure(StageError.STAGING_IO)
        }
    }

    /** Native layer also refuses all callers without real UID 0. */
    @JvmStatic private external fun publishNative(temporaryName: String, destinationName: String, bytes: Long): Int
    @JvmStatic private external fun sandboxPublishNative(directory: String, bytes: Long): Int
}
