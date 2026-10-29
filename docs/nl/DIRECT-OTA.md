# Directe officiële OTA-route

[English](../DIRECT-OTA.md) · [Workflow](UPDATE-WORKFLOW.md)

Eén start omvat voor één bronboot en doel de getoonde stockrestore, officiële OTA, Magisk op de inactieve slot en gecontroleerde herstart. De foregroundservice houdt de actieve autorisatie alleen in het geheugen. Een opnieuw gestart proces leest het journaal en kan een schrijfactie niet opnieuw activeren. De worker na de update leest uitsluitend boot, root, modules, slot en snapshotstatus.

De automatische route vereist echte productie-CDS-HTTPS-uitwisselingen, een daaraan gebonden volledige download, de OEM-ondertekening van de complete ZIP, payloadhashes, de juiste bron en het juiste doel en anti-rollbackcontrole. Een geïmporteerde ZIP of ingevoerde URL levert geen live serverselectie op. De live selectie kan het door Motorola gekozen model/kanaal aantonen wanneer ondertekende standaardmetadata geen kanaal bevat. Zij wordt apart gehouden van een kanaalhandtekening in de ZIP. Expliciete conflicten, downgrade, wipe en onbewezen bron blijven geblokkeerd.

Vóór stockherstel plaatst de roothelper de gecontroleerde payload in een unieke eigen map binnen de **bestaande** `/data/ota_package`. Het bestand is van root en alleen leesbaar. Normale systeemlabeling wordt uitsluitend op die nieuwe map en dat bestand toegepast. De `ota_package_file`-labels van basis, map en bestand moeten overeenkomen. SELinux-regels en enforcement worden niet gewijzigd. Ontbrekende of afwijkende staging stopt de route vóór de eerste partitieschrijfactie. De tijdelijke extra kopie vereist voldoende ruimte.

Het rootbewijs bindt de complete hashmeting, ondertekende manifestgegevens, bronboot, pakket en doel, OTA-trustankers en bestandsidentiteit aan een HMAC. Direct vóór dispatch worden het onveranderlijke rootbestand en verse toestelstatus opnieuw gecontroleerd. Android voert de normale payloadverificatie uit. Toegang tot een app-private map of aangeleverde root-FD wordt niet verondersteld.

De originele zichtbare Magisk 31.1-app moet exact bij de gecontroleerde releasehash passen. Tools en configuratie worden vóór stockherstel gecontroleerd. Na OTA wordt de echte inactieve image aan de ondertekende doeldigest getoetst. De officiële bestandspatcher maakt een privé-image. Stock-SHA1, configuratie, originele back-up en complete schrijf- en terugleescontrole zijn vereist. De initiële toestemming levert per actie een korte, eenmalige autorisatie op.

De herstarthelper controleert het geauthenticeerde Magisk-bewijs, de complete targetimage, ongewijzigde bronboot, engine en bootmetadata. App-opstart, herstelcontroles en oude toestemmingsbestanden voeren geen herstart of schrijfactie uit. Ontbrekend bewijs stopt de route en bewaart diagnostiek.

Zie de [technische bronnen](../DIRECT-OTA.md), [beperkingen](KNOWN-LIMITATIONS.md) en het [actuele buildrapport](../BUILD-REPORT.md). Bestandstests en simulaties bewijzen geen succesvolle OTA op iedere echte Motorola. Tijdens dit releasewerk zijn geen echte telefoon-OTA, partitieschrijfactie of telefoonherstart uitgevoerd.
