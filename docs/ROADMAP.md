# Roadmap na de veilige uitlezende release

## 1. Het echte toestel kwalificeren

Verzamel met deze app een lokaal rapport voor `XT2607-1 / merc / reteu` en voer [de handmatige checklist](MANUAL-TESTS.md) uit. Leg de exacte properties, bootctl/HAL-versie, snapshotdump, Magisk 31.1 daemoncode en de geïnstalleerde Motorola-updater-versie vast. Verwijder onbekende statusblokkades alleen op basis van een betrouwbare uitleesbron.

## 2. Huidige stock-image aantoonbaar koppelen

Verkrijg een officiële image van exact de huidige build met controleerbare herkomst. Vergelijk die met de uitgepakte Magisk-back-up, type, omvang en SHA-256. Bewaar een lokaal provenance-record met toestelprofiel, volledige buildidentiteit, partitietype, hash, gemeten partitiegrootte en bron. Een dergelijke registratie heeft alleen waarde wanneer de bron zelf authentiek is; een door de gebruiker ingetypte buildnaam is geen attestatie.

## 3. Motorola-integratie bewijzen

Onderzoek de lokaal geïnstalleerde APK en geëxporteerde componenten uitsluitend met toegestane, uitlezende middelen. Zoek een gedocumenteerd OEM-contract of laat een gecontroleerd eigen testtoestel aantonen welke interface werkt. Versiescope, authenticiteit, timeouts en foutgedrag moeten worden vastgelegd. Een private preferencesleutel of één succesvolle handmatige ZIP-import rechtvaardigt nog geen algemene installatiefunctie.

## 4. Begeleiding versterken

Verbeter onafhankelijke detectie van werkelijk afgeronde stappen: imagecorrespondentie, update-engine gereed voor reboot, juiste voorbereide slot, nieuwe buildboot, rootterugkeer en mergeafronding. Handmatige bevestigingen blijven aantekeningen. Ontbreekt een betrouwbaar extern Magisk-resultaat, houd de stap begeleid en toon de onzekerheid expliciet.

## 5. Downloads en meldingen

Een toekomstige achtergrondcontrole kan alleen een bewezen, geschikte officiële bron gebruiken. Notificeer op een nieuwe bevestigde kandidaat, geen oude URL of generieke indexmelding. Geef onbetrouwbare of externe meldingen hun eigen bewijsniveau. Achtergrondwerk mag geen update installeren, batterij-/netwerkkeuze overrulen of gevoelige diagnostiek uploaden.

## 6. Eventuele schrijffuncties als apart veiligheidsproject

Voeg pas na onafhankelijke review, versiegebonden integratiebewijs, herstelstrategie, hardwaretests en expliciete gebruikerstoestemming een muterende adapter toe. Houd die adapter gescheiden van de leeslaag. Vereis alle voorafcontroles en vlak vóór de write nieuwe waarnemingen; verifieer daarna echte bytes en systeemstatus. Hergebruik een gesimuleerde geslaagde workflow nooit als toestelautorisatie.

## 7. Android 18 en nieuwe Magisk-versies

Voeg Android 18 eerst toe als onbekend platform met uitlezende tests. Controleer SDK/API-wijzigingen, init_boot/kernelvoorwaarden, OTA-manifest en antirollback, Boot Control HAL, snapshotformaat, rootbeleid, Magisk-back-ups en Motorola-updatercomponenten opnieuw. Test migratie van opgeslagen sessies en nieuwe enumwaarden. De bestaande defaults moeten blijven blokkeren totdat bewijs is toegevoegd.

## Releasecriteria

Een nieuwe release vereist een reproduceerbare APK-build, geslaagde betekenisvolle unit- en platformtests, bijgewerkte bronreferenties, changelog en exacte functiestatus. Een toekomstige OTA-release vereist daarnaast een hardwaretestrapport dat toestel, firmware, images, slotresultaat, rootbehoud en volledig afgeronde merge benoemt. Een APK-build alleen is daarvoor onvoldoende.
