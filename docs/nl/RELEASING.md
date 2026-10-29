# Releasehandleiding — 1.0.0

[English](../RELEASING.md)

De eerste publieke release is `1.0.0`, Android-versiecode 4, package `nl.moto.ota`. De geleverde release is niet-debuggable en behoudt de volledige runtime; code- en resourceverkleining staan standaard uit. R8 is een optionele onderzoeksconfiguratie die volledige kwalificatie vereist. Een lokale build en het publiceren van een GitHub Release zijn afzonderlijke handelingen.

De verwachte publieke appsigner staat in [RELEASE-CERTIFICATE](../RELEASE-CERTIFICATE.md). Dit is geen firmware- of AVB-sleutel.

1. Beoordeel commit, Engelse/Nederlandse gidsen, screenshots en functiegrenzen. Voer repositoryscanner, core/app-tests, lint en wegwerpemulatortests uit. Noteer alleen echte resultaten in [BUILD-REPORT](../BUILD-REPORT.md); houd historisch toestelbewijs historisch.
2. Bouw uit een schoon bronarchief. Vergelijk de compileerinputs, controleer werkelijke APK-versie/ABI/dynamische interfaces en bewaar buildlogs. Maak checksums van exact de geleverde bestanden.
3. Bewaar een private releasekeystore buiten de checkout. Stel alle vier onderstaande signingvariabelen in, bouw en controleer de APK onafhankelijk. De build valt niet terug op een debugkey.
4. Maak een beoordeelde versietag nadat de controles slagen. De handmatig gestarte [signingworkflow](../../.github/workflows/signed-release.yml) accepteert een bestaande tag op de standaardbranch en uploadt artefacten; zij publiceert geen release of tag.
5. Publiceer de gecontroleerde getekende APK, SHA256-checksums, bronarchief en releasenotities. Onderscheid implementatie, synthetisch/emulatorbewijs en fysieke kwalificatie per functie. Bewaar de sleutel voor volgende updates.

| Lokale omgevingsvariabele | Waarde |
|---|---|
| `MOTO_RELEASE_STORE_FILE` | Absoluut keystorepad buiten de checkout |
| `MOTO_RELEASE_STORE_PASSWORD` | Keystorewachtwoord |
| `MOTO_RELEASE_KEY_ALIAS` | Releasesleutelalias |
| `MOTO_RELEASE_KEY_PASSWORD` | Sleutelwachtwoord |

```sh
./gradlew :app:assembleSignedRelease :app:lintRelease :app:writeReleaseDependencyInventory --no-daemon --no-configuration-cache
```

Gebruik op Windows `gradlew.bat`. Uitvoer: `app/build/outputs/apk/release/app-release.apk`. Stel de onafhankelijk bekende publieke certificaatfingerprint in als `MOTO_RELEASE_CERT_SHA256` en gebruik `tools/verify_release_apk.py`; zie [RELEASE-ENGINEERING](../RELEASE-ENGINEERING.md).

Configureer op GitHub een beschermd environment `release-signing`. Secrets: `MOTO_RELEASE_KEYSTORE_BASE64`, `MOTO_RELEASE_STORE_PASSWORD`, `MOTO_RELEASE_KEY_ALIAS`, `MOTO_RELEASE_KEY_PASSWORD`; publieke variabele: `MOTO_RELEASE_CERT_SHA256`. Private signinggegevens horen niet in broncode, logs, issues, artefacten of buildcaches. Reguliere pull-request-CI krijgt geen signingsecrets.

Zonder signinggegevens maakt gewone `:app:assembleRelease` unsigned CI-uitvoer, geen installeerbare getekende release. Een andere signingidentiteit kan een installatie met dezelfde package-ID niet bijwerken. Exporteer herstelgegevens vóór een bewust geplande migratie en behoud de bestaande app tot die migratie is voorbereid. Android beschrijft [signing en updatevereisten](https://developer.android.com/studio/publish/app-signing).

Publiceren maakt van een buildresultaat geen fysieke toestelkwalificatie. Releasenotities mogen gesimuleerde OTA/Magisk/herstart of historische uitlezing niet voorstellen als een geslaagde actuele toestelupdate.
