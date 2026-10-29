# Moto OTA Manager 1.0.0

First public release · Android13/API33+ · arm64-v8a / x86_64 · English / Nederlands

Moto OTA Manager combines measured Motorola diagnostics, official firmware detection and a persistent update workflow in one Compose app, with a setup tour, native app languages, light/dark appearance and adaptive navigation.

- Read device, root, Magisk backups, A/B slots and snapshots; compare available independent boot evidence when properties differ.
- Check Motorola CDS after its data-sharing disclosure, validate complete packages and use resumable downloads or local import.
- Evaluate other Motorola models from actual layout and evidence. Critical adapters cover reviewed API33–37 and exact official Magisk31.1; unknown combinations stop safely.
- Start one scoped update covering current stock restore, Android OTA application, inactive-slot Magisk and controlled reboot. Interrupted operations are observed rather than automatically replayed.
- Explore the complete route with a visibly labelled regular-file simulation that leaves the live journal unchanged.

Both APKs were built from clean committed source. **609 unique Kotlin/Android tests passed, 6 deliberately skipped, zero failures.** Seven evidence-tool tests and four file integration cases passed separately. The exact signed release APK was tested on a disposable emulator. [Build logs, hashes and qualification boundaries](BUILD-REPORT.md).

Real write/OTA/reboot adapters are implemented, but a complete physical Motorola update and root preservation have not been qualified for this release. No phone was used for release verification. Emulator success does not establish physical flash behavior, retained data, Wallet/attestation or module compatibility. [Compatibility](COMPATIBILITY.md) · [Known limitations](KNOWN-LIMITATIONS.md).

Verify the delivery checksums and [release certificate](RELEASE-CERTIFICATE.md). The new release signer cannot directly update an older debug-signed installation. Preserve recovery data and critical workflow evidence before a deliberate signer migration. Source includes no firmware, Magisk binary or private signing key. [Installation](INSTALLATION.md) · [Changelog](../CHANGELOG.md).

## Nederlands

De eerste publieke release bevat EN/NL, een introductietour, lichte/donkere weergave, gemeten Motorola-ondersteuning en één begrensde start voor de updateketen. De APK is werkelijk gebouwd en de genoemde tests zijn uitgevoerd. De volledige simulatie gebruikt gewone bestanden. Echte OTA-installatie, partitieschrijfacties en rootbehoud na een echte herstart vragen nog toestelkwalificatie. [Nederlandse handleiding](../README.nl.md).
