# Build- en testrapport — 0.3.0

**29 oktober 2026 · nl.moto.ota · versionCode 3**

De app is werkelijk gecompileerd voor **arm64-v8a en x86_64** en de geleverde debug-APK is op de wegwerpemulator geïnstalleerd en getest. **480 tests slagen, nul fouten.** Twee host-JVM-symlinkgevallen zijn op Windows overgeslagen; de overeenkomstige source-/destinationgevallen zijn vervolgens zonder skip op Android getest. Geen telefoon is tijdens deze 0.3.0-afronding gebruikt, beschreven, bijgewerkt of herstart.

## Artefact en ondertekening

| Veld | Gemeten waarde |
|---|---|
| APK | `moto-ota-manager-debug.apk` |
| Pakket / versie | `nl.moto.ota` / `0.3.0` / code `3` |
| Omvang | 29,284,877 bytes |
| SHA-256 | `01ee72a7b707141ec77afa2d5d2176dc26b49903deb8b693a8b8b38ec77f1084` |
| Minimaal / target / compile | SDK 33 / 35 / 35 |
| Native ABI's | arm64-v8a, x86_64 |
| Signing | Android Debug, APK Signature Scheme v2 daadwerkelijk geverifieerd |
| Certificaat SHA-256 | `21e80a8850a8f89759b7fba9e6ce693f3fdbe53de921d1a584a3bca130f92865` |

[APK-verificatie](../evidence/release-0.3.0/apk-signature.txt) · [Werkelijke APK-metadata](../evidence/release-0.3.0/apk-badging.txt) · [Machineleesbaar resultaat](../evidence/release-0.3.0/verification-summary.json).

De top-level `SHA256SUMS.txt` en `ARTIFACTS.json` horen bij de uiteindelijke APK en bron-ZIP. De `.git`-map, lokale SDK-configuratie, buildcache, echte firmwarebytes en private signing-/rootkeys horen niet in het bronarchief. De eerdere 0.2.0-artefacten zijn afzonderlijk bewaard; hun hashes gelden niet voor deze APK.

## Daadwerkelijk uitgevoerde controles

| Controle | Uitslag | Direct bewijs |
|---|---|---|
| Kotlin/JVM core | 312 geslaagd; 2 Windows-symlinkskips; 0 fouten | [JUnit-XML per coreklasse](../evidence/release-0.3.0/jvm/core/), [uitgevoerde testbuild](../evidence/release-0.3.0/build-core-and-app-tests.log) |
| Android JVM/parsers/beleid | 117 geslaagd; 0 skips/fouten | [JUnit-XML per appklasse](../evidence/release-0.3.0/jvm/app/) |
| Android API35-instrumentatie | 51 geslaagd; 0 skips/fouten | [Volledige runneruitvoer](../evidence/release-0.3.0/instrumentation-final.log), [uit de runnerrecords afgeleid JUnit-XML](../evidence/release-0.3.0/junit-emulator.xml) |
| App-APK en test-APK | BUILD SUCCESSFUL | [Definitieve buildlog](../evidence/release-0.3.0/build-final.log) |
| Native bibliotheken | Beide ABI's daadwerkelijk gecompileerd; positieve JNI no-clobber-test op emulator | [Native layout](../evidence/release-0.3.0/native-alignment.json), instrumentatieklasse MotorolaStagingRuntimeTest |
| APK-uitlijning | `zipalign -c -P 16 4` geslaagd | [Uitvoer](../evidence/release-0.3.0/apk-zipalign.txt) |
| Lint | 0 fouten, 24 waarschuwingen | [HTML](../evidence/release-0.3.0/lint-results-debug.html), [XML](../evidence/release-0.3.0/lint-results-debug.xml) |
| Schermen en notificatieroute | Echte overzicht-/firmwareschermen; CLEAR_TOP/SINGLE_TOP met firmware-extra gecontroleerd | [Overzicht](../evidence/release-0.3.0/ui-overview-api35.png), [Firmware](../evidence/release-0.3.0/ui-firmware-api35.png), [routecheck](../evidence/release-0.3.0/ui-navigation-check.json) |

De 51 instrumenttests bestaan uit 7 importer-, 12 download-, 9 Vault-, 5 monitor-, 5 staging/JNI-, 5 duurzame-store-, 5 UI- en 3 Androidplatformgevallen. De volledige UI-simulatie doorloopt stock, OTA, Magisk, nieuwe boot en merge en controleert dat het echte workflowjournaal niet verandert.

De nieuwe importer-opruimtests gebruiken echte eigen bestanden en symlinks: cancellation en een latere selectie laten geen grote transactie achter, verwijderen geen buitentarget en behouden selectie/backups/andere transacties. De downloadtests controleren ook dat een corrupt journaal veilig kan worden geïsoleerd zonder een eerder geselecteerde ZIP te verwijderen.

De core-suite heeft de expliciet aangewezen bestaande historische host-OTA werkelijk opnieuw onderzocht. De target-init_boot-manifesthash is `9972073a2fdc3bd63ea3b33b3e26ec39f78ddea82ed3be274acc086a88c7c5ea`; de sourcehash is de oorspronkelijke stock16-hash. Dit zijn gelezen pakketbytes, geen nieuwe telefoonmeting of installatie. Zonder die optionele private fixture slaat CI deze twee historische-pakketgevallen over.

## Buildopdrachten en omgeving

```sh
./gradlew :core:test :app:testDebugUnitTest :app:assembleDebug :app:assembleDebugAndroidTest :app:lintDebug --console=plain --no-daemon
python3 tools/verify_apk_layout.py app/build/outputs/apk/debug/app-debug.apk
```

Windows gebruikt `gradlew.bat`. Toolchain: JDK17, Gradle8.11.1, AGP8.9.2, Kotlin/Compose-plugin2.1.20, compile/targetSDK35, NDK27.2.12479018 en CMake3.22.1. De tools/cache staan taakgebonden onder de werkmap. De primaire Androidtests zijn uitsluitend naar `emulator-5556` gestuurd nadat `ro.kernel.qemu=1` is gecontroleerd; de fysieke opt-in validatieklasse is niet gestart.

De emulator draait API35/x86_64/ranchu met 4096-byte kernelpages. Alle verpakte native LOAD-segmenten zijn voor 16384 bytes uitgelijnd; de eigen JNI-bibliotheek heeft ook 16KiB-RELRO-uitlijning. Dit is gecontroleerde ELF/ZIP-layout, geen bewezen runtime op een fysieke 16KiB-kernel. De NDK27-build gebruikt de expliciete linkerflags uit [Androids page-size-documentatie](https://developer.android.com/guide/practices/page-sizes).

De Vault-runtimetest vond `AccessDeniedException` voor hardlinks in deze Androidcontext en bewees vervolgens de geïmplementeerde duurzame kopiefallback, eigen inode en byte-isolatie. De oude aanname dat hardlinks altijd werken is verwijderd. [Werkelijke runtimewaarneming](../evidence/release-0.3.0/vault-runtime-strategy.txt).

De opgenomen logs behouden de opdrachten, resultaten en foutmeldingen. Werkmappaden en JUnit-hostnamen zijn geanonimiseerd; regeleinden en afsluitende witruimte zijn genormaliseerd.

## Iteratieve foutanalyse

De opgenomen [iteratielogs](../evidence/release-0.3.0/iterations/) bewaren eerdere mislukte builds en correcties. Onder andere de afwijkende uitzondering bij ongeldige protobuf-wiretypes, een fixtureklokrace, ontbrekende publieke O_DIRECTORY-constanten, een bestaande verkeerde stagingbestemming, de hardlinkaanname, downloadjournaalcorruptie lokale importcleanup en een door de laatste scan gevonden onduidelijke engine-inspringing zijn daadwerkelijk onderzocht en gecorrigeerd. De definitieve groene log en XML zijn leidend; een oudere foutlog wordt niet als de huidige uitslag gepresenteerd.

## Wat werkt, wat gesimuleerd is en wat nog toestelvalidatie vraagt

| Categorie | Afgebakend resultaat |
|---|---|
| Werkelijke lokale appfuncties | Compose, filepicker/import, veilige bestandsoverdracht, pakketparsing/hash/signature-API, private opslag, duurzame workflow, Keystore, opt-in WorkManager-planning, export en expliciete officiële-apphandoffs. Testdekking is hierboven gespecificeerd. |
| Eerder echt toestelbewijs | De 0.2.0-fase mat op XT2607-1 Android17 de root, slots, succesvolle boot, snapshotNONE, engineIDLE, Motorola-cache, stock17-backup en positieve platformsignature. Dit bewijst de toenmalige uitlezende adapters; [DEVICE-VALIDATION](DEVICE-VALIDATION.md) en [het bewaarde rapport](BUILD-REPORT-0.2.0.md) staan apart. |
| Gesimuleerd | OTA-toepassing, Magisk-patching, reboot, slotovergang en merge in de volledige sandbox. Alleen kleine reguliere eigen bestanden worden werkelijk geschreven. HTTP/engine/toestelresponses in pure tests zijn fixtures/mocks. |
| Nog niet fysiek uitgevoerd | Nieuwe live stockwriter, SDK37-import-/staginghelpers, toekomstige echte OTA en rootbehoud, werkelijke Motorola-FUSE-herkenning, functionele modules, Wallet/PlayIntegrity en Android18. |

De acht nieuwe Magisk-configtests controleren de actuele back-upassociatie, inclusief oude authentieke stockbytes bij gespoofde buildproperties. Vóór schrijven leest de helper de begrensde runtimeconfig opnieuw; de SHA1 moet overeenkomen met de geauthenticeerde stock-image. Dezelfde gate draait in beide observe-rondes. [Bron en voorwaarden](UPDATE-WORKFLOW.md).

De beperkte stockwriter heeft een concrete bevestiging, volledige gepatchte preimage-backup, korte boot-/slot-/tijdgebonden one-use-toestemming, dubbele verse gates, readback en observation-only recovery. De block-I/O zelf is niet op de telefoon uitgevoerd. Er is geen bewezen atomair interlock met een tegelijk startende Motorola-updater; een I/O-stall of stroomuitval kan herstelbeoordeling vereisen. Een groene sandbox maakt die grens niet kleiner.

Motorola's normale officiële aanbodroute blijft de voorkeursroute. Er is geen bewezen publieke catalogus/check-API voor dit profiel. **Lokale staging stopt zonder ondertekend reteu-bewijs**, ook bij een geldige ZIP-signature, een vertrouwde host of een downloadreceipt. De historische ZIP zonder kanaalveld wordt via deze automatisering niet gepubliceerd. Magisk op de inactieve slot blijft via de officiële app en is vóór de echte nieuwe boot handmatig gemeld, niet onafhankelijk gecertificeerd. De app installeert geen OTA, wijzigt geen bootslot, voert geen Magisk-installer uit en herstart niet zelf.

Lintwaarschuwingen: acht nieuwere-dependencyberichten, acht vaste profielpadberichten, drie versiegebonden PrivateApi-reflecties, één beperkt laden van de eigen geïnstalleerde native bibliotheek, twee conservatieve ruimtechecks en twee KTX-stijlmeldingen. De compiler meldt daarnaast nullabilitywaarschuwingen bij door constructie vaste File-parents; de build faalt daar niet op. Er is geen lintfout genegeerd of veiligheidscheck uitgeschakeld om groen te verkrijgen.

[Alle eisen tegenover bewijs en grenzen](REQUIREMENTS-AUDIT.md) · [Scenario-/testmatrix](TEST-MATRIX.md) · [Handmatige checklist](MANUAL-TESTS.md).
