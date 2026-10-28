package nl.moto.ota.rootprobe;

import android.os.Build;
import android.os.Process;
import android.os.RecoverySystem;

import java.io.File;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.security.MessageDigest;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Verifies an existing package with the public Android whole-file verification API.
 * This main runs only in a dedicated root process on the measured Android 17 SDK.
 * It writes no files and never calls an installation, cleanup or reboot API.
 */
public final class FirmwareSignatureProbe {
    public static final long MAX_PACKAGE_BYTES = 12L * 1024 * 1024 * 1024;
    private static final String PRIMARY_PARENT = "/storage/emulated/0/Android/data/com.motorola.ccc.ota/files";
    private static final String SDCARD_PARENT = "/sdcard/Android/data/com.motorola.ccc.ota/files";
    private static final AtomicBoolean EMITTED = new AtomicBoolean();

    private FirmwareSignatureProbe() {}

    /** Pure guard for tests; a normal app or another SDK never reaches verification. */
    public static boolean supportsRuntime(int uid, int sdk) {
        return uid == 0 && sdk == 37;
    }

    public static boolean isAllowedPackageBytes(long bytes) {
        return bytes > 0 && bytes <= MAX_PACKAGE_BYTES;
    }

    /** Both arguments must already be canonical; the alias is derived from the fixed sdcard path. */
    public static boolean isAllowedCanonicalPath(String candidate, String canonicalSdcardParent) {
        if (candidate == null || canonicalSdcardParent == null) return false;
        int slash = candidate.lastIndexOf('/');
        if (slash <= 0) return false;
        String parent = candidate.substring(0, slash);
        String basename = candidate.substring(slash + 1);
        return (PRIMARY_PARENT.equals(parent) || canonicalSdcardParent.equals(parent))
            && basename.matches("[A-Za-z0-9_.-]+[.]zip");
    }

    public static void main(String[] args) {
        if (!supportsRuntime(Process.myUid(), Build.VERSION.SDK_INT) || args.length != 1) {
            emit(new Result(-1, "", false, "UNSUPPORTED_RUNTIME_OR_ARGUMENTS"));
            return;
        }

        Thread deadline = new Thread(() -> {
            try {
                Thread.sleep(180_000);
            } catch (InterruptedException finished) {
                return;
            }
            emit(new Result(-1, "", false, "PROBE_DEADLINE"));
            Runtime.getRuntime().halt(0);
        }, "moto-signature-probe-deadline");
        deadline.setDaemon(true);
        deadline.start();

        long packageBytes = -1;
        String sha256 = "";
        boolean signatureVerified = false;
        String reason = "UNAVAILABLE";
        try {
            File file = new File(args[0]).getCanonicalFile();
            String aliasParent = new File(SDCARD_PARENT).getCanonicalPath();
            if (!isAllowedCanonicalPath(file.getPath(), aliasParent)) {
                reason = "PATH_NOT_ALLOWED";
            } else {
                BasicFileAttributes before = Files.readAttributes(file.toPath(), BasicFileAttributes.class,
                    LinkOption.NOFOLLOW_LINKS);
                if (!before.isRegularFile() || !file.canRead() || !isAllowedPackageBytes(before.size())) {
                    reason = "PACKAGE_NOT_READABLE_OR_SIZE";
                } else {
                    packageBytes = before.size();
                    MessageDigest digest = MessageDigest.getInstance("SHA-256");
                    long total = 0;
                    try (InputStream input = Files.newInputStream(file.toPath())) {
                        byte[] buffer = new byte[1024 * 1024];
                        int count;
                        while ((count = input.read(buffer)) != -1) {
                            total += count;
                            if (total > packageBytes || total > MAX_PACKAGE_BYTES) throw new ChangedPackage();
                            digest.update(buffer, 0, count);
                        }
                    }
                    if (total != packageBytes || !unchanged(before, readAttributes(file))) throw new ChangedPackage();
                    sha256 = hex(digest.digest());

                    // Public API, default on-device OTA certificate store. This
                    // method verifies the existing ZIP and performs no install.
                    RecoverySystem.verifyPackage(file, null, null);
                    if (!unchanged(before, readAttributes(file))) throw new ChangedPackage();
                    signatureVerified = true;
                    reason = "";
                }
            }
        } catch (ChangedPackage changed) {
            signatureVerified = false;
            reason = "PACKAGE_CHANGED";
        } catch (java.security.GeneralSecurityException rejected) {
            reason = "SIGNATURE_NOT_VERIFIED";
        } catch (SecurityException denied) {
            reason = "READ_DENIED";
        } catch (Throwable unavailable) {
            reason = "READ_OR_API_UNAVAILABLE";
        }

        emit(new Result(packageBytes, sha256, signatureVerified, reason));
        deadline.interrupt();
        System.exit(0);
    }

    private static BasicFileAttributes readAttributes(File file) throws java.io.IOException {
        return Files.readAttributes(file.toPath(), BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
    }

    private static boolean unchanged(BasicFileAttributes before, BasicFileAttributes after) {
        return after.isRegularFile() && before.size() == after.size()
            && before.lastModifiedTime().equals(after.lastModifiedTime())
            && before.creationTime().equals(after.creationTime())
            && Objects.equals(before.fileKey(), after.fileKey());
    }

    private static String hex(byte[] bytes) {
        char[] digits = "0123456789abcdef".toCharArray();
        char[] result = new char[bytes.length * 2];
        for (int index = 0; index < bytes.length; index++) {
            result[index * 2] = digits[(bytes[index] & 255) >>> 4];
            result[index * 2 + 1] = digits[bytes[index] & 15];
        }
        return new String(result);
    }

    private static void emit(Result result) {
        if (EMITTED.compareAndSet(false, true)) {
            System.out.println(result.json());
            System.out.flush();
        }
    }

    private static final class ChangedPackage extends java.io.IOException {
        private static final long serialVersionUID = 1L;
    }

    private static final class Result {
        private final long packageBytes;
        private final String sha256;
        private final boolean verified;
        private final String reason;

        Result(long packageBytes, String sha256, boolean verified, String reason) {
            this.packageBytes = packageBytes;
            this.sha256 = sha256;
            this.verified = verified;
            this.reason = reason;
        }

        String json() {
            // Only bounded numbers, a hex digest and fixed constants are emitted.
            return "{\"schema\":1,\"packageBytes\":" + packageBytes + ",\"sha256\":\"" + sha256
                + "\",\"signatureVerified\":" + verified + ",\"reason\":\"" + reason
                + "\",\"source\":\"SystemOTACerts\"}";
        }
    }
}
