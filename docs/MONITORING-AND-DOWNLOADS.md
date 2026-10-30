# Monitoring, discovery and downloads — 1.0.1

[Nederlands](nl/MONITORING-AND-DOWNLOADS.md)

## Discovery

The primary online detector is the independent source-derived CDS client. It uses the measured identity after explicit online-sharing consent and makes a real HTTPS check, followed by a resource request only for a recognized offer. The protocol, data fields, bounds and lack of live identifier testing are documented in [CDS-PROVIDER](CDS-PROVIDER.md). It is an internal integration, not a public Motorola SDK.

The official updater remains the normal fallback. Local updater preferences and imported files are separate leads. A cached reference can be old; absence of metadata does not prove absence of an update. Metadata-only, complete OTA ZIP and unknown resources have separate types. A server offer, received bytes, authenticated ZIP, compatibility and actual installation are separate results.

## Background checking

Monitoring is off by default. Opt-in stores the interval and schedules WorkManager 2.10.5; default 6 hours, supported controller range 1–24 hours, initial delay 15 minutes and battery-not-low constraint. Android decides execution time; this is not an exact alarm. [WorkManager periodic scheduling](https://developer.android.com/develop/background-work/background-tasks/persistent/getting-started/define-work).

With separate online-sharing consent, the worker first attempts CDS. Without that consent, or if the online route is unavailable, the bounded local cache route remains. The local root-cache parser is qualified for the historical exact reference profile; it does not guess unknown schemas or device identities. Revoking online consent prevents future identity reads/requests. Disabling monitoring cancels scheduled work and prevents stale generation results from committing; an already sent request cannot be recalled.

New, fresh server information is labelled as a reported release, not verified installable firmware. Local metadata and package references use their own informational labels. Repeated target references, current builds, unknown/failed observations and non-actionable results stay quiet. A notice opens Firmware; it starts no download, root write or installation. Android 13+ notification permission is required for notices. [Notification permission](https://developer.android.com/develop/ui/views/notifications/notification-permission).

The atomic local history retains at most 12 entries with time, outcome, safe current/target build identifiers and notification status. Deduplication excludes signed URLs, serials, GUIDs, boot IDs and module names. Notification permission denial is recorded without bypassing Android. Corrupt settings disable monitoring instead of enabling it.

## Downloads and import

A transfer requires an authorized complete reference from the exact allowed HTTPS host `dlmgredg-vz.gtm.svcmot.com` and consistent measured Motorola identity. The downloader does not accept an arbitrary URL or broaden trust to all `appspot.com` hosts. CDS itself is a check endpoint, not an allowed package host. Nonempty arbitrary CDS resource headers are not forwarded.

WorkManager runs the transfer with a foreground progress notification and pause/cancel actions. Only an opaque job ID enters worker input. The sensitive URL is encrypted with an Android-Keystore-backed key; it is absent from logs, reports and the ordinary manifest. [Android Keystore](https://developer.android.com/privacy-and-security/keystore).

Checkpointed partial bytes remain separate from the selected package. Resume requires a matching strong ETag and valid 206/Content-Range; otherwise the transfer restarts or stops. Redirects, unexpected lengths, expired URLs and insufficient space stop rather than publishing a partial package. Final full length/hash checks and atomic publication precede completion. These rules follow [HTTP range/validator semantics](https://www.rfc-editor.org/rfc/rfc9110.html#name-range-requests).

Pause/resume support depends on the actual server. A fresh CDS check can return a new reference; an expired URL is not silently refreshed by guessing. Android foreground/background quotas can defer or interrupt work. A completed transfer still needs independent firmware authentication and compatibility checks.

Android document import and the bounded OEM-copy route are also byte transfers. OEM discovery lists at most 12 supported filenames while bounding directory traversal; a copy uses a unique private transaction, stable source identity, full hash readback and cancellation-aware cleanup. It does not change the OEM source or bypass signature checks.

## Storage and privacy

Vault pinning uses a hardlink when supported, otherwise a durable complete copy. Pins isolate verification from atomic replacement of a download. The current pin, incoming download and temporary copy can coexist and need substantial space. Obsolete regular UUID pins are pruned only after a successful new selection; failure preserves the prior selection.

No telemetry or automatic diagnostic upload is implemented. Online checking shares the separately disclosed identity with CDS; downloading shares ordinary network data and the temporary request with the allowed host. No IMEI is used. Local reports export an allowlist, never raw responses, signed URLs, serials or GUIDs. Keystore/private storage do not defend against complete control of a rooted device.

See [SAFETY](SAFETY.md), [COMPATIBILITY](COMPATIBILITY.md) and [BUILD-REPORT](BUILD-REPORT.md) for action boundaries and actual test results.
