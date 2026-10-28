# Installatie en eerste gebruik

## De debug-APK op de telefoon

1. Controleer de bestandsnaam en SHA-256 tegen het opleverrapport. De APK is een echte Android-debugbuild; de APK is geen firmwarebestand.
2. Kopieer de APK naar de telefoon en open haar met Androids normale pakketinstaller. Geef alleen de benodigde toestemming voor deze lokale installatie.
3. Open Moto OTA Manager. De eerste controle leest het toestel; een niet uitgevoerd rootonderzoek moet als onbekend zichtbaar blijven.
4. Wil je rootdiagnostiek gebruiken, open dan zelf Magisk 31.1 en geef Moto OTA Manager vooraf toegang in de Superuser-tab. Magisk 31.1 gebruikt geen reactieve root-pop-up. Toegang weigeren moet een begrijpelijke blokkade geven.
5. Voer de controle opnieuw uit en bekijk de blokkades. Het ontbreken van stock-imageherkomst of een uitleesinterface mag niet worden weggeklikt als “veilig”.
6. Gebruik [de read-only checklist](MANUAL-TESTS.md) om de echte toestelruntime te controleren.

De app installeert zelf geen firmware en herstart de telefoon niet. De bestandskiezer dient om een bestaand OTA- of imagebestand lokaal te onderzoeken. Openen van Motorola of Magisk is een expliciete overdracht; de gebruiker bedient die apps zelf. Test daarbij geen restore of OTA-installatie zonder een afzonderlijk veilig testplan.

## Android Studio

Open de repository als Gradle-project met een JDK 17-installatie. Gebruik de meegeleverde Gradle Wrapper. Installeer Android SDK Platform 35 en Build Tools 35.0.0 via de SDK Manager. Stel de lokale SDK-locatie in Android Studio of een eigen niet ingecheckte `local.properties` in.

De huidige app gebruikt minSdk 33 en compile/targetSdk 35. Android 17 is het onderzochte doelplatform voor begeleiding; compileren tegen SDK 35 levert geen claim dat nieuwe Android 17-API's worden gebruikt of dat runtimegedrag op API 37 al is getest. De app gebruikt bestaande publieke API's en uitleesinterfaces die op het echte toestel opnieuw moeten worden gekwalificeerd.

Op Windows:

```powershell
.\gradlew.bat :core:test :app:testDebugUnitTest :app:assembleDebug :app:lintDebug
```

Op Linux/macOS:

```sh
./gradlew :core:test :app:testDebugUnitTest :app:assembleDebug :app:lintDebug
```

De standaard debug-APK staat na een geslaagde build in `app/build/outputs/apk/debug/app-debug.apk`. Unit-testresultaten staan bij `core/build/reports/tests/test` en `app/build/reports/tests/testDebugUnitTest`; lint bij de Android-buildrapporten. Het geleverde [buildrapport](BUILD-REPORT.md) benoemt de daadwerkelijk uitgevoerde opdrachten en artefacten voor deze ontwikkelsessie.

## Tests zonder een echte telefoon

De core- en parsertests werken op de JVM en gebruiken gecontroleerde fixtures en mocks. Android-instrumentatietests vereisen een emulator. Als je die lokaal uitvoert, controleer eerst de beschikbare ADB-targets en gebruik expliciet uitsluitend een emulator-ID; laat nooit een algemene installopdracht toevallig op een aangesloten echte telefoon landen.

## Opslag, export en verwijderen

Geïmporteerde bestanden en het echte workflowlogboek worden in app-private opslag bewaard. De app gebruikt geen brede opslagtoegang. Diagnostiek exporteer je alleen zelf via Androids bestandskiezer; er is geen automatische upload. Bekijk het rapport vóór delen.

Verwijderen van de app of wissen van haar appgegevens verwijdert het lokale logboek en app-private kopieën. Het verwijdert geen Magisk-modules, originele Motorola-prefs of toestelpartities. Bewaar een gewenst diagnostisch rapport daarom vooraf op een zelfgekozen plek.

## Een eigen release uitbrengen

De debugkeystore is geen productiereleasesleutel. Maak voor een publieke uitgave een eigen ondertekende releasebuild en beheer de sleutel buiten de repository. Publiceer pas na hardwarevalidatie een claim over het doeltoestel. Deze repository en zijn CI zijn een uitgangspunt voor verdere ontwikkeling; zij publiceren niet automatisch in jouw GitHub-account of een appwinkel.
