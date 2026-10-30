# Release engineering — 1.0.1

## Nederlands

`versionName=1.0.1`, `versionCode=5`, application ID `nl.moto.ota`. `release` is expliciet niet-debuggable. Versie 1.0.0 bevat de volledige runtime: code- en resourceverkleining staan standaard uit. Echte release-instrumentatie toonde bij R8 gedeelde-library-ABI-fouten aan, waaronder verwijderde Kotlin-, Compose- en WorkManager-methoden/classes. De grotere APK behoudt dezelfde functies en wordt als release getest. `-Pmoto.minifyRelease=true` blijft een optionele onderzoeksbuild die opnieuw volledig moet worden gekwalificeerd. De behoudregels blijven aanwezig voor die build. `tools/verify_release_apk.py` controleert de daadwerkelijke DEX-entrypoints, het manifest, ABI's en ondertekening van de gekozen APK.

Voor een lokale getekende release zijn exact vier omgevingsvariabelen vereist:

| Naam | Inhoud |
|---|---|
| `MOTO_RELEASE_STORE_FILE` | Absoluut pad naar een private keystore buiten de checkout |
| `MOTO_RELEASE_STORE_PASSWORD` | Keystorewachtwoord |
| `MOTO_RELEASE_KEY_ALIAS` | Alias van de releasesleutel |
| `MOTO_RELEASE_KEY_PASSWORD` | Sleutelwachtwoord |

Gebruik `./gradlew :app:assembleSignedRelease :app:lintRelease :app:writeReleaseDependencyInventory --no-daemon --no-configuration-cache`. Op Windows: `gradlew.bat`. Het getekende APK-pad is `app/build/outputs/apk/release/app-release.apk`. Zonder signingvariabelen kan gewone CI `:app:assembleRelease` bouwen; die uitvoer is expliciet unsigned en is geen installeerbare release. Een gedeeltelijke signingconfiguratie stopt de configuratie. Er is geen terugval naar de debugkey en de build genereert zelf geen sleutel.

Stel voor onafhankelijke verificatie daarnaast de **publieke** `MOTO_RELEASE_CERT_SHA256` in, afgeleid van het vooraf bewaarde releasecertificaat. Voer `python tools/verify_release_apk.py <apk> --build-tools <sdk>/build-tools/35.0.0` uit. Het script vereist de verwachte sleutel, weigert een debuggable APK en de bekende Android-debugondertekening, controleert v2-signing en bevestigt versie, permissions, back-up/cleartextinstellingen, native extractie, twee ABI's, 16KiB-ELF-layout en dynamische interfaces. De controle installeert of start niets. Een correcte app-signature bewijst geen firmwaregeschiktheid.

De bestaande 0.3.0-debugkey blijft onaangeroerd. Een nieuwe releasesleutel heeft een andere identiteit: dezelfde package ID kan daarmee niet als gewone update over de oude debuginstallatie worden geplaatst. Exporteer belangrijke herstelback-ups en bewijzen vóór een zelf gekozen migratie; het verwijderen van appdata verwijdert ook de Vault en Keystore-geheimen. De releaseprocedure wist, installeert of migreert niets automatisch. Bewaar de releasesleutel en de wachtwoorden voor volgende updates; publiceer alleen het certificaat/de fingerprint.

De GitHub-workflow `Build signed release` draait uitsluitend na handmatige dispatch vanaf de standaardbranch, voor een bestaande gereviewde tag die bereikbaar is vanaf die branch. Gebruik een beschermd environment `release-signing`; plaats daarin de vier wachtwoord/alias/base64secrets en de publieke certificaatvariabele. De binary keystore heet in GitHub Secrets `MOTO_RELEASE_KEYSTORE_BASE64`; de workflow decodeert die naar een bestand met mode0600 onder RUNNER_TEMP, buiten de checkout. De lokale `MOTO_RELEASE_STORE_FILE` wordt daaruit afgeleid. Base64 is geen encryptie: dit hoort uitsluitend in Secrets, nooit in broncode. Signing-buildcaches zijn uitgeschakeld en de tijdelijke sleutel wordt altijd opgeruimd. De workflow uploadt uitsluitend APK, mapping, dependency-inventory en verificatieresultaat; zij publiceert geen GitHub Release of tag.

Reguliere PR/push-CI gebruikt geen signingsecrets. Zij bouwt debug en unsigned release en valideert de echte release-DEX-contracten. De emulatorjob is uitsluitend een wegwerp-API35/x86_64-emulator. Voor een lokaal getekende release-instrumentatietest kan `-Pmoto.testBuildType=release :app:connectedReleaseAndroidTest` gebruikt worden op een apart gekozen emulator. Dit kan niet over een anders ondertekende debuginstallatie worden bijgewerkt; gebruik hiervoor een eigen wegwerpemulator en raak een telefoon niet aan.

`tools/check_public_repository.py` controleert de huidige tracked plus niet-genegeerde nieuwe bestanden op gegenereerde/private artefacten, private-keymarkers en herkenbare gevoelige tekst. De scanner toont geen verdachte waarden. Hij controleert geen Gitgeschiedenis of beeldpixels en geeft geen volledige privacygarantie. De releasebron wordt pas verpakt na een aparte schone-checkoutbuild en een handmatige controle van de geselecteerde evidence.

`writeReleaseDependencyInventory` schrijft werkelijk opgeloste release-runtimecoördinaten en SHA-256 van de gedownloade dependencyartefacten naar `app/build/reports/dependencies/release-runtime.json`, zonder lokale paden. Dependabot stelt wekelijkse Gradle- en Actions-updates voor; er is geen automerge. De Dependency Review-workflow blokkeert op publieke GitHub-PR's nieuwe high/critical advisories en de expliciet geweigerde licenties. Dit is geen claim dat alle transitieve dependencies kwetsbaarheidsvrij of juridisch volledig beoordeeld zijn; de concrete inventory en upstream notices blijven reviewmateriaal.

`python tools/audit_dependencies.py --inventory app/build/reports/dependencies/release-runtime.json --output <rapport.json>` bevraagt OSV voor precies deze opgeloste Maven-versies. Alleen publieke coördinaten worden verstuurd. Een mislukte/onvolledige query is expliciet UNKNOWN en faalt; geretourneerde advisories vereisen beoordeling en falen eveneens. Geen geretourneerde match is geen garantie dat kwetsbaarheden ontbreken. Apache2- en Skia-BSD-licentieteksten/notices zijn ook in de APK gebundeld; test-/buildtools behouden hun afzonderlijke upstreamlicenties.

## English

The release is **1.0.1 / code5**, package `nl.moto.ota`, non-debuggable, and retains the complete runtime. Code/resource shrinking is off by default after actual optimized release instrumentation exposed shared Kotlin/Compose/WorkManager ABI failures. The app's functions are unchanged; the APK is larger. `-Pmoto.minifyRelease=true` remains an opt-in research build requiring full qualification. Supply all four `MOTO_RELEASE_*` signing variables above; keep the keystore outside the checkout. `:app:assembleSignedRelease` requires private release signing. Plain `:app:assembleRelease` without secrets produces unsigned CI output and never falls back to debug signing.

Pin the independent public signing fingerprint in `MOTO_RELEASE_CERT_SHA256` and run `tools/verify_release_apk.py`. This verifies the actual APK contract without installing, invoking root or accessing a phone. It does not certify firmware safety. Preserve the new private key for future updates; a release signed by it cannot directly replace the older debug-signed installation. Export recovery data before a deliberate migration. No automatic uninstall or data deletion is performed.

The manually dispatched signing workflow accepts an existing reviewed default-branch tag, uses environment secrets, deletes its temporary key and uploads build artefacts only. PR/push CI never receives signing secrets. Public-source scanning, resolved dependency hashes, Dependabot and public PR dependency review provide evidence with explicit limits; they do not replace manual privacy, upstream-license or physical-device review.

## Primary references

- [Android app signing](https://developer.android.com/studio/publish/app-signing): signing identity, update compatibility and keeping signing material private.
- [Android R8 keep rules](https://developer.android.com/topic/performance/app-optimization/keep-rules-overview): reflection/JNI entrypoints require explicit retention.
- [GitHub Actions secrets](https://docs.github.com/en/actions/how-tos/write-workflows/choose-what-workflows-do/use-secrets): secret storage and environment usage.
- [GitHub Dependency Review](https://docs.github.com/en/code-security/concepts/supply-chain-security/dependency-review): advisory/license review and repository availability.

Action refs were resolved from each upstream GitHub tag to the full commit on 2026-10-29. All executable workflow actions use full commit hashes; Dependabot proposes updates for review.
