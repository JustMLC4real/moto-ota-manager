# Roadmap na 0.2.0-safe

## 1. De bevestigde uitleespaden blijven kwalificeren

De [toestelvalidatie](DEVICE-VALIDATION.md) bevestigt uitlezende metingen voor `XT2607-1 / merc / reteu`, Android 17, build `A171WMH.44-25`, slot `_b` en Magisk `31100`. Ontbrekende commandotools zijn op dit toestel opgevangen met begrensde AOSP-metadata en een actuele rootcallback. Herhaal [de handmatige checklist](MANUAL-TESTS.md) bij nieuwe firmware, helperwijzigingen en root-/OEM-versies. Bewaar bronconflicten en verwijder onbekende statusblokkades alleen met nieuw uitleesbewijs. Het [buildrapport](BUILD-REPORT.md) houdt APK- en instrumentatieresultaten gescheiden van de forensische metingen.

## 2. De geauditeerde stockcatalogus gecontroleerd uitbreiden

De huidige stock17-back-up is inhoudelijk gekoppeld aan het manifest van de onafhankelijk geauthenticeerde historische OTA. De ingebouwde catalogus vereist het exacte gemeten profiel, de build, de fingerprint en de passende toestel-OTA-sleutel. Voeg een toekomstige referentie pas toe na dezelfde onafhankelijke authenticiteitscontrole en volledige imagevergelijking. Bewaar omvang, SHA-256, partitietype, bronbewijs en meetprofiel; neem de imagebytes niet als testfixture op. Een geïmporteerd JSON-record of ingetypte buildnaam mag geen vertrouwensbron worden.

## 3. Motorola-integratie bewijzen

Het openen van de officiële updater is nu begrensd tot een ingeschakelde geëxporteerde Motorola-systeemactivity met positieve resolverprioriteit en een expliciet component. Dit bewijst geen discovery- of installatiecontract. Onderzoek toekomstige OEM-contracten uitsluitend met toegestane middelen en leg versiescope, authenticiteit, timeouts en foutgedrag vast. De huidige private downloadmetadata hoort bij de al geïnstalleerde OTA; zij rechtvaardigt geen nieuwe updateclaim of algemene installatiefunctie.

## 4. Begeleiding versterken

Verbeter onafhankelijke detectie van werkelijk afgeronde stappen: imagecorrespondentie, update-engine gereed voor reboot, juiste voorbereide slot, nieuwe buildboot, rootterugkeer en mergeafronding. Handmatige bevestigingen blijven aantekeningen. Ontbreekt een betrouwbaar extern Magisk-resultaat, houd de stap begeleid en toon de onzekerheid expliciet.

## 5. Downloads en meldingen

Een toekomstige achtergrondcontrole kan alleen een bewezen, geschikte officiële bron gebruiken. Notificeer op een nieuwe bevestigde kandidaat, geen oude URL of generieke indexmelding. Geef onbetrouwbare of externe meldingen hun eigen bewijsniveau. Achtergrondwerk mag geen update installeren, batterij-/netwerkkeuze overrulen of gevoelige diagnostiek uploaden.

## 6. Eventuele schrijffuncties als apart veiligheidsproject

Voeg pas na onafhankelijke review, versiegebonden integratiebewijs, herstelstrategie, hardwaretests en expliciete gebruikerstoestemming een muterende adapter toe. Houd die adapter gescheiden van de leeslaag. Vereis alle voorafcontroles en vlak vóór de write nieuwe waarnemingen; verifieer daarna echte bytes en systeemstatus. Hergebruik een gesimuleerde geslaagde workflow nooit als toestelautorisatie.

## 7. Android 18 en nieuwe Magisk-versies

Android 18 is ongetest en moet eerst als onbekend platform met uitlezende tests worden onderzocht. Controleer SDK/API-wijzigingen, de versiegebonden enginecallback, init_boot/kernelvoorwaarden, OTA-manifest en antirollback, Boot Control HAL, snapshotformaat, rootbeleid, Magisk-back-ups en Motorola-updatercomponenten opnieuw. Test migratie van opgeslagen sessies en nieuwe enumwaarden. De bestaande defaults en stockcatalogus mogen geen ondersteuning suggereren totdat nieuw bewijs is toegevoegd.

## Releasecriteria

Een nieuwe release vereist een reproduceerbare APK-build, geslaagde betekenisvolle unit- en platformtests, bijgewerkte bronreferenties, changelog en exacte functiestatus. Een toekomstige OTA-release vereist daarnaast een hardwaretestrapport dat toestel, firmware, images, slotresultaat, rootbehoud en volledig afgeronde merge benoemt. Een APK-build alleen is daarvoor onvoldoende.
