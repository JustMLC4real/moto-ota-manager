# Direct official OTA route

[Nederlands](nl/DIRECT-OTA.md) · [Workflow](UPDATE-WORKFLOW.md)

One start covers the displayed source restore, official OTA, inactive-slot Magisk and controlled restart for one exact source boot and target. A foreground service retains the active authorization in memory. Process recreation reads the journal and cannot replay a writer. The post-update worker only observes boot, root, modules, slot and snapshot completion.

The automatic route requires an opaque selection from actual production CDS HTTPS exchanges, its complete package download, the device's OEM whole-ZIP signature, payload hashes, exact target, source compatibility and anti-rollback checks. An imported ZIP, filename or entered URL cannot manufacture live origin evidence. A live selection can establish Motorola's model/channel selection when standard metadata omits channel fields. It remains origin evidence, separate from a signed channel assertion. Explicit model/channel conflicts, downgrade, wipe and unproved source remain blocking.

A vendor/system namespace bridge requires the full signed independently observed vendor fingerprint and matching system source scalars. Similar product names alone are insufficient.

## Android handoff

Android's [update_engine policy](https://android.googlesource.com/platform/system/sepolicy/+/refs/heads/android16-release/private/update_engine.te) allows the normal OTA package file type. App-private file access and incoming root-owned Binder descriptors are not assumed.

Before source restore, the fixed root helper authenticates the original ZIP and publishes its verified payload as a root-owned read-only file in a unique child of the **existing** `/data/ota_package`. It applies normal system file labeling only to this new directory/file and verifies `ota_package_file` on the base, directory and file. It does not change SELinux policy or enforcement. Missing or unsupported staging blocks before source restore. Storage accounts for the temporary second payload copy.

A root HMAC binds the complete publication hash, signed manifest facts, source boot, package/target, live trust anchors and inode/device/mtime/ctime. Dispatch rechecks the immutable root-owned file and fresh source/engine/slot state. Multi-GB preparation stays outside the short one-use permit. Android verifies the actual payload during normal application; verification is never disabled.

The [AOSP implementation](https://android.googlesource.com/platform/system/update_engine/+/b90a7161b79a5145c8c27fc65da6243e3bc945c2/aosp/update_attempter_android.cc) is the API reference. The running boot framework's Binder interfaces are required. Success needs both completion callback and actual pending-restart status, followed by boot-control and inactive-image checks. Only this app's temporary input is removed after completed application. Interrupted preparation can retain evidence for investigation.

## Root and completion

The visible installed official Magisk 31.1 APK must match the pinned release hash. Its tools and configuration are checked before source restore. After actual OTA completion, the inactive image must match the OEM-signed manifest target. The official file patcher creates a private patched image. Embedded stock SHA1, configuration, original backup and full write/readback are checked.

Each privileged transition consumes a short action from the same initial scope. Restart requires authenticated Magisk evidence, full inactive readback, unchanged source, pending engine and boot-control priorities. Startup and observation-only recovery cannot dispatch it. Unknown or error evidence stops the route and retains diagnostics.

## Qualification

APK builds, simulated workflows, emulator filesystem checks and actual Magisk file-tool checks prove different boundaries. They do not certify an OEM OTA, boot-ROM acceptance, root persistence or data preservation on every Motorola. [BUILD-REPORT](BUILD-REPORT.md) lists executed checks. No physical OTA, partition write or phone restart was performed during this release work.
