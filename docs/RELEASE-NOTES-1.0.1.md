# Moto OTA Manager 1.0.1

Android 13/API 33+ · versionCode 5 · arm64-v8a / x86_64 · English / Nederlands

This update fixes local checks that could report a missing build GUID or OEM product before contacting Motorola, and simplifies the main screens.

- Accept the measured `ro.mot.build.guid` when it contains exactly 15 or 40 hexadecimal characters. Preserve the original value without padding, hashing or guessed property aliases.
- Prefer the measured `ro.vendor.product.display`; when it is empty, use the measured `ro.product.name`. Missing values and conflicting product/fingerprint, manufacturer, device, serial, channel or technical model evidence still block the online check.
- Give the device summary, Motorola result and main action more space. Move detailed diagnostics, monitoring, imports, local firmware and recovery controls into expandable sections. Online errors and missing fields remain visible in the main firmware card.
- Add finite transitions for results, workflow stages, disclosures, status colors and measured progress. Compose animations follow Android's animator duration scale.

Synthetic regression fixtures cover the accepted GUID formats, product fallback, missing inputs and conflicting identities, including conflicts caused by property spoofing. They do not establish live Motorola acceptance. The CDS client uses an internal protocol observed in OEM sources; server behavior and future schema compatibility remain outside the app's guarantee. [Provider details and sources](CDS-PROVIDER.md).

Consult the [current build report](BUILD-REPORT.md) for the actual executed checks, artifact hashes, signing evidence and qualification limits. Historical 1.0.0 results remain historical and are not 1.0.1 test results.

A complete physical Motorola OTA, partition writes, target boot, root retention, module compatibility and data preservation still require device qualification. Emulator and ordinary-file tests cannot establish those outcomes. [Compatibility](COMPATIBILITY.md) · [Known limitations](KNOWN-LIMITATIONS.md).

## Nederlands

Versie 1.0.1 herstelt de lokale invoercontrole die een aanwezige build-GUID of een bruikbaar OEM-product kon afwijzen voordat Motorola werd benaderd. Een gemeten `ro.mot.build.guid` van exact 15 of 40 hexadecimale tekens is toegestaan. Bij een lege `ro.vendor.product.display` gebruikt de app de gemeten `ro.product.name`. Ontbrekende of tegenstrijdige identiteitsgegevens blijven blokkerend; de app verzint geen waarden.

De hoofdschermen leggen de nadruk op status en hoofdactie. Details voor diagnose, monitoring, import, lokale firmware en herstel staan in uitklapbare onderdelen. Onlinefouten en ontbrekende velden blijven direct zichtbaar. Korte animaties voor status, voortgang en uitklappen volgen de Android-instelling voor animatieduur.

De synthetische regressiegevallen bewijzen geen acceptatie door Motorola. De [actuele buildrapportage](BUILD-REPORT.md) bevat de werkelijk uitgevoerde controles en het bijbehorende bewijs. Een volledige fysieke OTA en rootbehoud na een echte herstart blijven ongekwalificeerd. [Nederlandse providerdocumentatie](nl/CDS-PROVIDER.md) · [Nederlandse handleiding](../README.nl.md).
