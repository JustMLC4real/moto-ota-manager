# Integration decisions — 1.0.1

[Nederlands](nl/INTEGRATION-DECISIONS.md) · [Research](RESEARCH.md)

The selected route keeps the original Motorola-signed update and measures the device that will receive it. Discovery, package authentication, stock restoration, OTA application, inactive-slot root and post-boot completion are separate contracts inside one user-facing workflow. They do not need separate confirmation dialogs when included in the reviewed initial run scope.

## Motorola CDS is the primary detector

[FixeQD/motorola-update](https://github.com/FixeQD/motorola-update/tree/272f1c9bbe930a00ed76fe65d481f5cb108dacdf) supplies useful protocol observations, not an officially supported client SDK. The app independently implements the observed production request and bounded response/resource parser. The online provider is primary; official-updater navigation and local evidence remain fallbacks. The implementation does not copy decompiled OEM code, native libraries or embedded credentials.

The protocol example in that repository is not sufficient to authorize an installation. This client requires measured identity, consent, context/target/resource binding and strict transport limits. A server offer remains distinct from authenticated package bytes and from compatible install authority. [Detailed source pins, schema, privacy and synthetic test boundaries](CDS-PROVIDER.md).

No production request with a real identifier was needed for source review. Acceptance of minimized optional telemetry, actual offers and delivery behavior therefore need a consented runtime qualification. A refusal or `NO_OFFER` must not be presented as proof that the phone is permanently blocked or Motorola has no update.

## Magisk uses a versioned evidence boundary

The [official OTA guide](https://topjohnwu.github.io/Magisk/ota.html) separates restoring original images, installing the OTA without rebooting, installing Magisk on the updated inactive slot, then rebooting. It notes device variation. This is the workflow model, not a promise of a public automation API.

The reviewed implementation boundary is **Magisk 31.1/code31100**. Research is pinned to [commit 0972f67](https://github.com/topjohnwu/Magisk/tree/0972f67d97f9b6302b67c5171283330c74114b4a), including its [file patcher](https://github.com/topjohnwu/Magisk/blob/0972f67d97f9b6302b67c5171283330c74114b4a/scripts/boot_patch.sh). Patching a private image, writing a real inactive partition and selecting the next boot are different operations. A patch script or success message alone cannot prove the last two. The app requires independently authenticated target-stock bytes, the correct measured target, complete image checks and write/readback evidence before accepting a privileged transition.

`MagiskInactiveController`/`MagiskInactiveProbe` admit only the installed official `com.topjohnwu.magisk` APK for code31100, exactly **10,679,283 bytes**, SHA256 **`89cefac0a318afa39fb3880c45c87b036c05bc1ebf02a7a715b7f8d7c26ac6e7`**. This matches the [official v31.1 release asset](https://github.com/topjohnwu/Magisk/releases/tag/v31.1). Hidden/repacked managers, different versions or missing tools stop before this adapter proceeds; official-app guidance remains the fallback.

The helper extracts only the reviewed scripts and five exact ABI tools from that verified installed APK. It reads the completed inactive stock partition into a private file, verifies the signed target digest, patches privately, checks the embedded ramdisk stock-SHA1 independently and preserves a validated new Magisk gzip backup without overwriting an unknown existing one. A separate consumed short action permits the real inactive `boot`/`init_boot` write, with source/next-slot/engine gates and complete same-descriptor readback. It does not call `direct_install`, `post_ota`, `bootctl`, rewrite AVB keys or alter modules. These are implementation facts; current execution and device qualification remain in the build report.

The original package remains signed by Motorola. The app does not disable signature, source-block, payload, verity or rollback verification to make an update pass. A new Android or Magisk version needs adapter review; a matching marketing label is insufficient. See [Compatibility](COMPATIBILITY.md) and [Stock provenance](STOCK-PROVENANCE.md).

## Why avbroot is not the default route

[avbroot](https://github.com/chenxiaolong/avbroot/tree/ddddae031463636f2342157ac29a16490024cdde) repatches and re-signs OTA/AVB components with user-maintained keys. Its locked-bootloader setup introduces a different trust and recovery lifecycle. That does not match this app's default Motorola-signed OTA route on an already rooted unlocked phone. It is a distinct advanced deployment, not an extra download parser to add invisibly.

Motorola is not categorically incompatible: the maintainer's [support list](https://github.com/chenxiaolong/avbroot/issues/299) includes several user-tested models. It does not establish Edge 70 Pro support. We do not experiment with key enrollment, locking, wiping or replacement trust on the user's phone.

## What one start can safely mean

A start can authorize a single bound run including all displayed privileged actions. The actual adapter must still establish each phase's prerequisites at execution time, stop on contradictory facts and preserve recovery evidence. A background preference cannot turn an old package permit into authorization for all future targets.

The code uses independent device capabilities and run/action authority rather than a universal model-name allowlist or an unbounded root shell. A saved journal records progress; it is not a command queue replayed after a crash. Unsupported paths keep a reason and the official-app fallback. [Workflow and recovery](UPDATE-WORKFLOW.md).

The [current build report](BUILD-REPORT.md) is the authority for executed checks. Synthetic transport, file-backed OTA/Magisk/reboot and historical read-only hardware observations are explicitly different evidence levels. No source review can replace a real, separately planned device update qualification.
