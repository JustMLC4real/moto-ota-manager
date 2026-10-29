# Installation and first run — 1.0.0

[Nederlands](nl/INSTALLATION.md) · [Compatibility](COMPATIBILITY.md) · [Safety](SAFETY.md)

Use Android 13/API 33 or newer with arm64-v8a or x86_64. Obtain the APK and matching checksum from the same reviewed delivery or release. Verify the checksum and signing identity before installation. Debug and production releases have different signing identities; Android cannot update one over the other. Preserve recovery data before a deliberately planned migration. [Release signing](RELEASING.md).

Open the APK through Android's package installer and allow installation from that chosen source if Android asks. The app does not install itself, unlock a bootloader, create the initial Magisk/root setup or grant itself root.

## Setup

The first-run tour introduces the route, root access and evidence limits. Choose English, Dutch or system language. Settings offers system/light/dark appearance and reopening the tour. The tour itself requests no root and starts no update. Android 13+ supplies native [per-app language support](https://developer.android.com/guide/topics/resources/app-languages).

Start with diagnostics. Basic identity works without root where Android exposes it. For protected measurements, request root in the app and grant **Moto OTA Manager** access in Magisk. ADB root is separate from the app's Superuser grant. A denied or missing observation remains unavailable.

Read the capability report. Identity, boot layout, available official updater, root and current evidence determine which operations can proceed. Another Motorola model is not rejected merely because its name differs from the reference phone. Critical operations still require every runtime and provenance gate.

## Find an update

The primary online check uses the source-derived Motorola-CDS provider. Explicitly allow the displayed data sharing before it reads or sends identifiers. It sends the serial/build context and stated device/build information to the production service. No IMEI or OEM credential is used. Revoke consent to stop future requests; ordinary diagnostics do not collect those identifiers. [Provider and privacy](CDS-PROVIDER.md).

An offer is not authenticated installable firmware. Review target and resource kind. Metadata-only cannot become a complete OTA. An authorized complete reference can enter the downloader, with pause/cancel/resume. You can also choose a ZIP through Android's document picker or copy an existing OEM ZIP through the bounded import route.

Use the official Motorola updater when CDS refuses, returns an unknown schema or cannot establish a suitable resource. Changing counters or clearing updater data does not establish a genuine offer.

## Start and follow the workflow

Review the displayed run scope, root-preservation choice, target and evidence before starting. Start consent applies to that reviewed intent; missing proof does not become permission. Unsupported or uncertain transitions must stop. The exact transitions and recovery rules are described in [UPDATE-WORKFLOW](UPDATE-WORKFLOW.md).

Local staging is a distinct OEM handoff that can trigger processing. It retains authenticated channel checks; a download receipt, cache path or valid ZIP signature alone does not satisfy them.

After an update, obtain fresh build/slot/root/boot/snapshot/module/backup observations. User-reported steps remain separate from independent device evidence. A simulation is never a completed phone update.

Background checking starts disabled. Online background checks also require online-sharing consent. Allow Android notification permission if you want notices; a notice starts no installation. [Monitoring and downloads](MONITORING-AND-DOWNLOADS.md).

Self-build instructions: [README](../README.md). Actual delivered files and test logs: [BUILD-REPORT](BUILD-REPORT.md).
