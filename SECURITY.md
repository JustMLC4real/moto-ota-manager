# Veiligheidsmeldingen en wijzigingen

Deze release bevat uitlezende diagnostiek en een afzonderlijke, expliciet bevestigde stockhersteladapter. `ReadOnlyProbe` begrenst de diagnostische shell; de stockhelper heeft een eigen vast protocol en geen vrije shellinvoer. Alleen de actuele init_boot van het onderzochte XT2607-1/merc/reteu Android17/SDK37/Magisk31100-profiel, exact 8 MiB, kan na verse gates en eenmalig HMAC-consent worden hersteld. Er is geen OTA-installer, Magisk-installer, slotwissel of rebootfunctie. Zie [het veiligheidsmodel](docs/SAFETY.md) en [het runbook](docs/UPDATE-WORKFLOW.md).

De nieuwe live schrijver is niet op de telefoon uitgevoerd. De volledige schrijf-/crash-/readbacktests gebruiken gewone sandboxbestanden en expliciet gesimuleerde toestelinteracties. Verse engine/snapshotmetingen vormen geen atomair interlock met Motorola; een I/O-stall kan een write onderbreken. Recovery leest onder dezelfde writerlock en herhaalt geen write. Een expliciete nieuwe voorbereiding vereist volledig onveranderde gepatchte bron, volledige backup en duurzame invalidatie van het oude consent.

De bestaande-ZIP-importhelper leest alleen Motorola's bron en schrijft unieke app-private staging. De aparte optionele copy-only staginghelper kan een geauthenticeerde ZIP in Motorola's map klaarzetten, na eigen expliciete waarschuwing voor mogelijke automatische herkenning. Dat is geen installer-API of bewijs van compatibiliteit; de nieuwe live kopieerhelpers zijn evenmin fysiek getest.

Meld parserfouten, onbedoelde acceptatie van firmware of het exporteren van gevoelige informatie met een minimaal synthetisch voorbeeld. Voeg geen IMEI, serienummer, accountgegevens, getekende OTA-URL's of ongeschoonde updater-XML toe aan openbare issues. Gebruik het ingebouwde geanonimiseerde rapport.

Automatische tests zijn geen toestelcertificering. Updates aan Android, Magisk, Motorola's updater of het toestelprofiel kunnen nieuwe blokkades opleveren. Maak een onbekende toestand niet kunstmatig groen om verder te kunnen.

Geïmporteerde OTA's, images en het workflowjournaal staan in app-private opslag. Cloudback-up en device transfer zijn uitgesloten. Bij deïnstalleren kunnen deze lokale kopieën verdwijnen; bewaar je oorspronkelijke, onafhankelijk geverifieerde firmwarebestanden zelf veilig. Er is geen automatische upload.
