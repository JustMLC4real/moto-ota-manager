# Installatie en eerste gebruik — 0.2.0-safe

## De debug-APK op de telefoon

1. Controleer de bestandsnaam en SHA-256 tegen het opleverrapport. De APK is een echte Android-debugbuild; de APK is geen firmwarebestand.
2. Kopieer de APK naar de telefoon en open haar met Androids normale pakketinstaller. Geef alleen de benodigde toestemming voor deze lokale installatie.
3. Open Moto OTA Manager. De eerste controle leest het toestel; een niet uitgevoerd rootonderzoek moet als onbekend zichtbaar blijven.
4. Wil je rootdiagnostiek gebruiken, open dan zelf Magisk 31.1 en geef Moto OTA Manager vooraf toegang in de Superuser-tab. Magisk 31.1 gebruikt geen reactieve root-pop-up. Toegang weigeren moet een begrijpelijke blokkade geven.
5. Voer de controle opnieuw uit en bekijk de blokkades. Voor het exact geauditeerde Android 17-profiel kan de app de huidige stockback-up koppelen aan de ingebouwde referentie en de gelezen toestel-OTA-sleutel. Een andere build, fingerprint of sleutel krijgt die koppeling niet automatisch. Onbekende herkomst of status blijft een blokkade.
6. Gebruik [de read-only checklist](MANUAL-TESTS.md) om de echte toestelruntime te controleren.

De app installeert zelf geen firmware en herstart de telefoon niet. De bestandskiezer dient om een bestaand OTA- of imagebestand lokaal te onderzoeken. Openen van Motorola of Magisk is een expliciete overdracht; de gebruiker bedient die apps zelf. De Motorola-knop kiest uitsluitend een ingeschakelde, geëxporteerde systeemactivity van de Motorola-updater met positieve resolverprioriteit en opent een expliciet component. Bij een ontbrekende geschikte activity navigeer je handmatig. Test geen restore of OTA-installatie zonder een afzonderlijk veilig testplan.

De [toestelvalidatie](DEVICE-VALIDATION.md) legt de daadwerkelijk gemeten `XT2607-1 / merc / reteu`, Android 17, build `A171WMH.44-25`, slot `_b` en Magisk `31100` vast. De actuele metadata hoort bij de reeds geïnstalleerde OTA; zij bewijst geen nieuwe update. Het [buildrapport](BUILD-REPORT.md) bevat afzonderlijk de definitieve APK- en instrumentatieresultaten.

## Android Studio

Open de repository als Gradle-project met een JDK 17-installatie. Gebruik de meegeleverde Gradle Wrapper. Installeer Android SDK Platform 35 en Build Tools 35.0.0 via de SDK Manager. Stel de lokale SDK-locatie in Android Studio of een eigen niet ingecheckte `local.properties` in.

De huidige app gebruikt minSdk 33 en compile/targetSdk 35. De uitlezende runtime is onderzocht op het genoemde Android 17 / SDK 37-toestel. Bij ontbrekende commandotools gebruikt de app begrensde, gecontroleerde AOSP-boot-/snapshotmetadata en een roothelper die uitsluitend een versiegebonden enginecallback registreert en afmeldt. Deze paden geven geen algemene compatibiliteitsgarantie voor andere firmware of Android 18.

Ontbrekende allowlisted properties worden na bevestigde roottoegang nogmaals met root gelezen; op het gemeten toestel was dit nodig voor Virtual A/B. Alleen de twee vaste Motorola-preferencebestanden worden via de tijdelijke globale Magisk-mountnamespace (`su --target 0`) gelezen. Hiervoor worden geen permissies, rootpolicy of permanente namespace-instellingen gewijzigd.

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

De core- en parsertests werken op de JVM en gebruiken gecontroleerde fixtures en mocks. De gewone UI- en platformtests kunnen op een emulator draaien. Tests die het werkelijk geauditeerde profiel, root of private Motorola-metadata nodig hebben, vereisen het afzonderlijk gekozen fysieke toestel en expliciete testautorisatie. Controleer vóór iedere instrumentatierun de beschikbare ADB-targets en kies het bedoelde target expliciet.

## Opslag, export en verwijderen

Geïmporteerde bestanden en het echte workflowlogboek worden in app-private opslag bewaard. De app gebruikt geen brede opslagtoegang. Diagnostiek exporteer je alleen zelf via Androids bestandskiezer; er is geen automatische upload. Bekijk het rapport vóór delen.

Verwijderen van de app of wissen van haar appgegevens verwijdert het lokale logboek en app-private kopieën. Het verwijdert geen Magisk-modules, originele Motorola-prefs of toestelpartities. Bewaar een gewenst diagnostisch rapport daarom vooraf op een zelfgekozen plek.

## Een eigen release uitbrengen

De debugkeystore is geen productiereleasesleutel. Maak voor een publieke uitgave een eigen ondertekende releasebuild en beheer de sleutel buiten de repository. Beperk hardwareclaims tot de daadwerkelijk onderzochte uitleesfuncties en het gedocumenteerde profiel; een geslaagde diagnostische meting bewijst geen veilige toekomstige OTA-installatie. Deze repository en zijn CI publiceren niet automatisch in jouw GitHub-account of een appwinkel.
