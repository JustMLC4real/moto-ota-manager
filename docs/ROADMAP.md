# Roadmap na 0.3.0

De broncode bevat opt-in WorkManager-monitoring, hervatbare expliciete downloads, private pakketpinning en stockbewijs, een bewaakt stockherstelcontract, een duurzame begeleide workflow, een bestandssandbox en apart bevestigde Motorola-staging met native publicatie. Dit zijn geen toekomstige placeholders. Uitgevoerde tests staan uitsluitend in [BUILD-REPORT.md](BUILD-REPORT.md) en [TEST-MATRIX.md](TEST-MATRIX.md).

## Nieuwe functies op het werkelijke profiel kwalificeren

Houd het uitlezende 0.2.0-bewijs in [DEVICE-VALIDATION.md](DEVICE-VALIDATION.md) gescheiden van nieuwe functies. De huidige fase voert geen ADB-acties op de telefoon uit. Een latere hardwarefase begint pas met afzonderlijk toegestane uitlezende validatie van de nieuwe APK, roottoekenning, actuele build, engine, snapshots, certificaten en private mappen. Echte schrijfacties vragen daarnaast een concreet herstelplan en eigen toestemming; een emulatorresultaat autoriseert die fase niet.

Kwalificeer stockherstel en staging afzonderlijk. Bewijs voor herstel de volledige gepatchte back-up, fsync, tijdsgrenzen, interruptie, writerlock en readback. Onderzoek voor staging de echte gebruikers-0-namespace, FUSE-mount, native bibliotheek, `RENAME_NOREPLACE`, directory-fsync en Motorola's reactie. Behoud vóór iedere lokale publicatie de eis van ondertekend `motorola-channel`/`pre-channel` exact `reteu` én `report.regionVerified`. Onbekend kanaal blijft `STAGING_CHANNEL_UNPROVEN`, ook als de ZIP-handtekening geldig is. Ontbrekende ondersteuning blijft een verklaarbare blokkade.

## Motorola-aanbod en mapgedrag onderbouwen

Er is geen publiek stabiel servercontract aangetoond voor toestelgeschikte OTA-detectie. Houd cachewaarneming, extern releasebericht, downloadreferentie en werkelijk OEM-aanbod uit elkaar. Een nieuwe provider vereist aangetoonde authenticiteit, profiel-/regiobinding, uitrolstatus, versiescope en foutgedrag. Zonder die bron belooft de monitor geen actuele beschikbaarheid.

Leg voor staging vast of, wanneer en bij welke OEM-versie de updater een gepubliceerd bestand ziet, verplaatst, weigert of verwerkt. Maak daarvan geen algemene Android-import-API en publiceer geen pakket met onbekende regio om Motorola de geschiktheid achteraf te laten bepalen. Host, cache en transferreceipt zijn geen kanaalprovenance. Zonder ondertekend kanaalbewijs blijft de normale officiële aanbodroute de fallback; lokale import, download en inspectie blijven beschikbaar. Onderzoek een eventueel gedocumenteerd OEM-contract als alternatief voor het interne mappad.

## Magisk-bewijs aan het authentieke doel binden

De productieflow gebruikt de officiële Magisk-app en onderscheidt handmatige melding van gemeten root na boot. Een toekomstige onafhankelijke imagecontrole moet de gelezen doelslot, volledige bytes, gebruikte Magisk-tool en embedded stockhash aan de authentieke stockimage van de doelbuild koppelen. Twee gelijk ingevulde hashvelden volstaan niet. Houd de stap handmatig zolang die producent/herkomstketen ontbreekt; interne Magisk-installerfuncties zijn geen vervangend publiek contract.

## Bewijsopslag en migraties blijven testen

Test migratie van monitorinstellingen, downloadjournal, Keystore-geheimen, Vault-feiten, workflows en consumptiemarkers. Corruptie, ingetrokken sleutels, lege bestanden, volle opslag en procesonderbreking moeten onbekend, pauze of expliciete herstelbeoordeling opleveren. Appstart herhaalt geen partitieschrijf- of stagingopdracht.

Breid de stockcatalogus alleen uit na onafhankelijke OTA-authenticatie en volledige inhoudsvergelijking. Bewaar hash, omvang, type, bronbewijs en exact meetprofiel. Gebruikersmanifesten en verzonnen fingerprintaliases mogen de vertrouwensbasis niet uitbreiden.

## Achtergrondgedrag en opslag volgen

Onderzoek hoe Motorola-energiebeheer periodiek werk en foregrounddownloads beïnvloedt. Los aangetoonde problemen op zonder stilzwijgend een permanente service, exacte alarmgarantie of batterij-uitzondering te eisen. Behoud stille ongewijzigde controles, expliciete netwerkkeuze, geen automatische download na een melding en beperkte historie.

Meet de tijdelijke ruimte voor grote partials, pinning, back-ups en staging. Opruimen raakt alleen intern beheerde bestanden; afgebroken downloads vervangen nooit een gecommitteerde selectie.

## Nieuwe Android-, Magisk- en toestelvarianten

Behandel Android 18 en nieuwe Magisk/OEM-versies eerst als ongekwalificeerd. Onderzoek frameworkinterfaces, SELinux/namespace, kernel/init_boot, Boot Control, snapshots, engine, back-ups, certificaten, fingerprints, rollback en updateractiviteiten opnieuw. Voeg ondersteuning met bewijs toe; versoepel onbekende waarden niet om een groen scherm te krijgen.

## Releasecriteria

Iedere release vraagt een reproduceerbare APK, betekenisvolle geslaagde checks voor gewijzigde code, actuele functiestatus, concrete logs en documentatie. Een **hardwarebewezen OTA-keten** vraagt een afzonderlijk rapport met bron-/doelfirmware, werkelijk gelezen images, herstelkopie, slotresultaat, root en afgeronde merge. Tot dat bewijs bestaat blijft live implementatie gescheiden van sandboxuitvoering en historisch uitleesbewijs.
