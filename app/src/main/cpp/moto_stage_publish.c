/* Narrow root-only publication primitive. No arbitrary paths, partition access,
 * subprocess, OTA API, reboot, settings change, replacement or fallback copy. */
#include <jni.h>
#include <errno.h>
#include <fcntl.h>
#include <linux/fs.h>
#include <stdbool.h>
#include <string.h>
#include <sys/stat.h>
#include <sys/syscall.h>
#include <sys/system_properties.h>
#include <unistd.h>

#define MAX_PACKAGE_BYTES (12LL * 1024 * 1024 * 1024)

static bool uuid(const char *name) {
    for (int i = 0; i < 36; i++) {
        if (i == 8 || i == 13 || i == 18 || i == 23) {
            if (name[i] != '-') return false;
        } else if (!((name[i] >= '0' && name[i] <= '9') || (name[i] >= 'a' && name[i] <= 'f'))) return false;
    }
    return true;
}

static bool source_name(const char *name) {
    return strlen(name) == 53 && !strncmp(name, ".moto-stage-", 12) && uuid(name + 12) && !strcmp(name + 48, ".part");
}

static bool target_name(const char *name) {
    if (strlen(name) != 77 || strncmp(name, "moto-ota-", 9) || strcmp(name + 73, ".zip")) return false;
    for (int i = 9; i < 73; i++) if (!((name[i] >= '0' && name[i] <= '9') || (name[i] >= 'a' && name[i] <= 'f'))) return false;
    return true;
}

/* Open every component relative to a held directory fd. Never follow symlinks. */
static int fixed_directory(bool destination) {
    const char *const common[] = {"storage", "emulated", "0", "Android", "data"};
    int fd = open("/", O_RDONLY | O_DIRECTORY | O_CLOEXEC | O_NOFOLLOW);
    if (fd < 0) return -1;
    for (unsigned int i = 0; i < sizeof(common) / sizeof(common[0]); i++) {
        int next = openat(fd, common[i], O_RDONLY | O_DIRECTORY | O_CLOEXEC | O_NOFOLLOW);
        close(fd); fd = next;
        if (fd < 0) return -1;
    }
    const char *package_name = destination ? "com.motorola.ccc.ota" : "nl.moto.ota";
    int next = openat(fd, package_name, O_RDONLY | O_DIRECTORY | O_CLOEXEC | O_NOFOLLOW);
    close(fd); fd = next;
    if (fd < 0) return -1;
    next = openat(fd, "files", O_RDONLY | O_DIRECTORY | O_CLOEXEC | O_NOFOLLOW);
    close(fd); fd = next;
    if (fd < 0 || destination) return fd;
    next = openat(fd, "staging", O_RDONLY | O_DIRECTORY | O_CLOEXEC | O_NOFOLLOW);
    close(fd);
    return next;
}

static int unsupported_or_io(void) {
    return errno == ENOSYS || errno == EINVAL || errno == EOPNOTSUPP || errno == EXDEV ? 2 : 3;
}

/* Shared by the production fixed-directory entry point and the qemu-only,
 * non-root app-cache test. The actual syscall and durability logic are identical. */
static int publish_fds(int source_dir, const char *source, int target_dir, const char *target, jlong bytes) {
    int result = 4, fd = -1;
    struct stat source_fs, target_fs, original, published;
    if (fstat(source_dir, &source_fs) || fstat(target_dir, &target_fs)) return 3;
    if (source_fs.st_dev != target_fs.st_dev) return 2;
    fd = openat(source_dir, source, O_RDONLY | O_CLOEXEC | O_NOFOLLOW);
    if (fd < 0 || fstat(fd, &original) || !S_ISREG(original.st_mode) || original.st_size != bytes) goto done;
    if (fsync(fd) || fsync(source_dir) || fsync(target_dir)) { result = unsupported_or_io(); goto done; }
    if (syscall(SYS_renameat2, source_dir, source, target_dir, target, RENAME_NOREPLACE)) {
        result = errno == EEXIST ? 1 : unsupported_or_io();
        goto done;
    }
    result = 5;
    if (fsync(target_dir) || fsync(source_dir)) goto done;
    if (fstatat(target_dir, target, &published, AT_SYMLINK_NOFOLLOW) || !S_ISREG(published.st_mode) ||
        published.st_dev != original.st_dev || published.st_ino != original.st_ino || published.st_size != bytes) goto done;
    result = 0;
done:
    if (fd >= 0) close(fd);
    return result;
}

/* 0=published, 1=already exists, 2=atomic primitive unavailable, 3=I/O,
 * 4=guard rejected, 5=published but directory durability not proven.
 * Any nonzero result must not be reported as a successful stage. */
JNIEXPORT jint JNICALL
Java_nl_moto_ota_platform_staging_AtomicStagePublisher_publishNative(
    JNIEnv *env, jclass type, jstring temporary, jstring destination, jlong bytes) {
    (void)type;
    if (getuid() != 0 || !temporary || !destination || bytes < 1 || bytes > MAX_PACKAGE_BYTES) return 4;
    const char *source = (*env)->GetStringUTFChars(env, temporary, NULL);
    if (!source) return 4;
    const char *target = (*env)->GetStringUTFChars(env, destination, NULL);
    if (!target) { (*env)->ReleaseStringUTFChars(env, temporary, source); return 4; }
    int result = 4, source_dir = -1, target_dir = -1;
    if (!source_name(source) || !target_name(target)) goto done;
    source_dir = fixed_directory(false); target_dir = fixed_directory(true);
    if (source_dir < 0 || target_dir < 0) { result = 3; goto done; }
    result = publish_fds(source_dir, source, target_dir, target, bytes);
done:
    if (target_dir >= 0) close(target_dir);
    if (source_dir >= 0) close(source_dir);
    (*env)->ReleaseStringUTFChars(env, destination, target);
    (*env)->ReleaseStringUTFChars(env, temporary, source);
    return result;
}

/* Positive instrumentation test entry: only a non-root qemu app may use its own
 * fixed-package cache/staging-native-test-UUID/source.bin -> target.bin. A real
 * phone, root, arbitrary directory, symlink directory or another UID is rejected.
 * There is no production UI or exported Android component for this method. */
JNIEXPORT jint JNICALL
Java_nl_moto_ota_platform_staging_AtomicStagePublisher_sandboxPublishNative(
    JNIEnv *env, jclass type, jstring directory, jlong bytes) {
    (void)type;
    char qemu[PROP_VALUE_MAX] = {0}, hardware[PROP_VALUE_MAX] = {0};
    __system_property_get("ro.kernel.qemu", qemu);
    __system_property_get("ro.hardware", hardware);
    if (getuid() < 10000 || strcmp(qemu, "1") || (strcmp(hardware, "ranchu") && strcmp(hardware, "goldfish")) ||
        !directory || bytes < 1 || bytes > MAX_PACKAGE_BYTES) return 4;
    const char *path = (*env)->GetStringUTFChars(env, directory, NULL);
    if (!path) return 4;
    const char *prefix1 = "/data/user/0/nl.moto.ota/cache/staging-native-test-";
    const char *prefix2 = "/data/data/nl.moto.ota/cache/staging-native-test-";
    const char *tail = !strncmp(path, prefix1, strlen(prefix1)) ? path + strlen(prefix1) :
        !strncmp(path, prefix2, strlen(prefix2)) ? path + strlen(prefix2) : NULL;
    int result = 4;
    if (tail && strlen(tail) == 36 && uuid(tail)) {
        int fd = open(path, O_RDONLY | O_DIRECTORY | O_CLOEXEC | O_NOFOLLOW);
        struct stat dir;
        if (fd >= 0 && !fstat(fd, &dir) && dir.st_uid == getuid() && !(dir.st_mode & 0077))
            result = publish_fds(fd, "source.bin", fd, "target.bin", bytes);
        if (fd >= 0) close(fd);
    }
    (*env)->ReleaseStringUTFChars(env, directory, path);
    return result;
}
