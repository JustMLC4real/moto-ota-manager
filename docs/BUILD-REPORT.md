# Build-, test- en opleverrapport

Datum: **28 oktober 2026**. Release: **0.1.0-safe**. Package: **nl.moto.ota**.

## Resultaat

De echte Kotlin/Jetpack Compose-app is gecompileerd, debug-ondertekend, geïnstalleerd en gestart op een Android-emulator. **180 JVM-tests en 6 Android-instrumentatietests slagen: 186 tests, nul fouten.** Android-lint slaagt met nul fouten en zeven waarschuwingen.

De fysieke Motorola is niet aangesloten, onderzocht, geflasht, bijgewerkt of herstart. Deze oplevering bewijst werkende softwarebouw en de hieronder genoemde tests; zij bewijst geen geslaagde OTA of rootbehoud op XT2607-1.

## Bestanden

Naast de repositorymap worden `moto-ota-manager-debug.apk`, `moto-ota-manager-source.zip` en `SHA256SUMS.txt` opgeleverd. Het bronarchief bevat project, Gradle Wrapper, tests, documentatie, GitHub Actions en bewijsbestanden. Lokale SDK-paden, buildcaches, sleutels en gedownloade toolchains zitten niet in het bronarchief.

Definitieve APK:

- Bestand: `moto-ota-manager-debug.apk`
- Omvang: **26.680.925 bytes**
- SHA-256: **d0adf0f3379fd48fd69e2a1c6cd6561879f8c7f8a873aac576fd56410b30f6a0**
- Ondertekening: Android Debug, APK Signature Scheme v2; `apksigner verify` geslaagd.
- Minimum API 33; target/compile API 35. Native libraries omvatten arm64-v8a, armeabi-v7a, x86 en x86_64.
- Het daadwerkelijk geteste APK-bestand is hetzelfde bestand dat als download wordt opgeleverd.

Bewijs: [APK-signaturecontrole](../evidence/apk-signature.txt), [manifest/badging](../evidence/apk-badging.txt).

## Gebruikte toolchain

| Onderdeel | Versie |
|---|---|
| Java | Eclipse Temurin JDK 17.0.20.1+1, x64 Windows |
| Gradle Wrapper | 8.11.1; distributie en wrapper-JAR vergeleken met officiële SHA-256 |
| Android Gradle Plugin | 8.9.2 |
| Kotlin / Compose compilerplugin | 2.1.20 |
| Compose BOM | 2025.04.01 |
| Android SDK / Build Tools | Platform 35 / 35.0.0 |
| Emulator | Officiële emulator 37.2.12, WHPX |
| Virtueel toestel | Pixel 5, Android 15 / API 35 Google APIs, x86_64, 1080×2340 |

JDK en SDK zijn taaklokaal geïnstalleerd. De oorspronkelijke Java 8 en bestaande Gradlecaches zijn niet als bouwomgeving gebruikt. Er is geen Magisk-code of Motorola-firmware meegeleverd.

## Buildopdrachten en resultaat

Definitieve build:

```text
gradlew.bat :core:test :app:testDebugUnitTest :app:assembleDebug :app:lintDebug :app:assembleDebugAndroidTest --console=plain --no-daemon
BUILD SUCCESSFUL in 55s
84 actionable tasks: 17 executed, 67 up-to-date
```

[Definitieve volledige buildlog](../evidence/build-final.log).

Een zelfstandige lokale Git-clone is daarnaast zonder `local.properties`, bestaande buildoutputs of IDE-projectbestanden gebouwd: **84 van 84 taken uitgevoerd, BUILD SUCCESSFUL in 1m 14s**. De SDK is via de procesomgeving gevonden. Dit bevestigt dat de repository zelfstandig bouwt; er wordt geen bit-identieke APK-reproductie beloofd. [Build van zelfstandige clone](../evidence/source-clone-build.log).

De overige `build-01` tot en met `build-06`-logs tonen de ontwikkeling, waaronder gecorrigeerde compileer- en testfouten. De definitieve build en uiteindelijke XML-resultaten zijn leidend. De UI-correctie en de instrumentatietests zijn opgenomen in de definitieve build.

## Uitgevoerde tests

| Suite | Tests | Fouten |
|---|---:|---:|
| BackupValidatorTest | 15 | 0 |
| FirmwareDiscoveryTest | 7 | 0 |
| FirmwareValidatorTest | 53 | 0 |
| PackageTransferTest | 8 | 0 |
| PrivacyTest | 4 | 0 |
| ReadinessTest | 14 | 0 |
| WorkflowTest | 28 | 0 |
| FirmwareSecurityReviewTest | 5 | 0 |
| InspectionMapperTest | 25 | 0 |
| ObservationParsersTest | 21 | 0 |
| PlatformSafetyTest, echte Android-runtime | 3 | 0 |
| SafeUiTest, echte Compose-runtime | 3 | 0 |
| **Totaal** | **186** | **0** |

De JVM-testresultaten staan in [junit-core](../evidence/junit-core) en [junit-app](../evidence/junit-app). [De testmatrix](TEST-MATRIX.md) koppelt de twintig verlangde situaties aan concrete testnamen.

De instrumentatietests zijn met expliciete emulatorselectie uitgevoerd, zodat een later aangesloten telefoon geen installatiedoel kan worden:

```text
adb -s emulator-5556 shell getprop ro.kernel.qemu
1
adb -s emulator-5556 install -r app-debug.apk
Success
adb -s emulator-5556 install -r app-debug-androidTest.apk
Success
adb -s emulator-5556 shell am instrument -w -r nl.moto.ota.test/androidx.test.runner.AndroidJUnitRunner
Time: 14.061
OK (6 tests)
```

[Ruwe instrumentatieresultaten](../evidence/instrumentation-results.log), [appinstallatie](../evidence/emulator-apk-install.log), [test-APK-installatie](../evidence/emulator-test-apk-install.log), [geslaagde appstart](../evidence/emulator-apk-launch.log).

De drie platformtests testen echte Android-bestandsimport, een atomair Android-journaal en afwijzing van een onondertekend pakket door `RecoverySystem.verifyPackage`. De drie UI-tests controleren het uitlezende beginscherm, expliciete rootbevestiging met annuleren, en de afzonderlijk gelabelde simulatie. Er is geen root verleend en geen externe updater gestart door deze tests.

### Lint

Nul fouten, zeven waarschuwingen: zes meldingen over nieuwere dependencyversies en één melding dat `StorageManager` reclaimbare cache kan meenemen bij ruimteberekening. De app gebruikt bewust een conservatieve controle van werkelijk vrije ruimte en wist geen gebruikerscache. Dependencies zijn vastgezet op de in deze build geteste combinaties.

[Lint HTML](../evidence/lint/lint-results-debug.html), [lint XML](../evidence/lint/lint-results-debug.xml). De GitHub Actions-workflow is opgenomen; deze sessie heeft geen externe GitHub-run gestart.

## Visuele controle

De echte APK is gestart, de uitlezende scan is voltooid, het firmwaretabblad is geopend en beide schermen zijn als screenshot vastgelegd. De statusbalkiconen zijn na visuele inspectie gecorrigeerd; de hoofdknop is direct zichtbaar. De screenshots tonen echte emulatorgegevens, geen nagebouwde mockup en geen fysieke Motorola.

[Overzicht](../evidence/app-overview-api35.png) · [Firmware](../evidence/app-firmware-api35.png)

## Werkend, gesimuleerd en nog te bewijzen

**Werkende implementaties:** echte Android-properties en accu/ruimte, begrensde uitleesprobes, strikte observatieparsing, image- en OTA-validatie, expliciete HTTPS-overdracht met atomisch opslaan, Android-certificaatverificatie, duurzaam journaal, lokale privacybeperkte export en officiële-appoverdracht met foutafhandeling.

**Gesimuleerd:** volledige OTA-/Magisk-/rollbacktrajecten in unit-tests, synthetische firmware en back-ups, externe discoverymeldingen en de expliciete demo. Dit wordt nergens als een echte geslaagde installatie gepresenteerd.

**Op het echte toestel te testen:** getprops/SKU, Magisk 31.1-rootpolicy en -binaries, SELinux, bootctl/snapshotctl/enginecallback, Motorola's private metadataschema, de bestaande back-up en de vertrouwde OTA-certificaten. Een werkelijk geldig officieel OTA-pakket is in deze sessie niet geleverd of positief op de Motorola geverifieerd. De emulator test de negatieve signaturecontrole.

**Veilige fallback:** geen aangetoonde stabiele externe Motorola-/Magisk-installatie-API, geen bewezen huidige stock-imageherkomst en geen universeel kanaalbewijs. Daarom ontbreken partitieschrijven, automatische stockrestore, OTA-toepassing, Magisk-patching, slotwissels en herstart in de uitvoeradapter. Zonder onafhankelijk actuele stockherkomst en geschikt pakket blijft updatevoorbereiding geblokkeerd. De handleiding en uitleesdiagnostiek blijven beschikbaar.

Er is ook geen automatische achtergrondcontrole of OTA-notificatie. Lokale metadata levert uitsluitend een mogelijke kandidaat. De app kan Motorola's serveraanbod of de historische oorzaak van `ALREADY_UP_TO_DATE` niet bewijzen of resetten.

Dit is de aantoonbaar bouwbare veilige oplevering, met de expliciet toegestane fallbacks voor integraties die zonder verder toestelbewijs niet verantwoord te automatiseren zijn. Volg voor fysieke validatie de [read-only checklist](MANUAL-TESTS.md); deze ontwikkelsessie heeft geen toestemming voor een destructieve toesteltest gebruikt.
