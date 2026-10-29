# Achtergrondcontrole, downloads en pakketopslag — 0.3.0

De monitor leest lokale Motorola-informatie. Een download haalt na een gebruikersactie bytes op. Firmwareverificatie, stockherstel, OEM-overdracht en installatie zijn afzonderlijke stappen. Uitgevoerde controles staan in [BUILD-REPORT.md](BUILD-REPORT.md); dit document beschrijft de broncode.

## Opt-in lokale metadatacontrole

De schakelaar staat standaard uit. Inschakelen bewaart toestemming en plant met WorkManager 2.10.5 één unieke periodieke taak: standaard zes uur, eerste minimumvertraging vijftien minuten, niet bij lage accu. De controller ondersteunt één tot en met 24 uur; de UI gebruikt de standaard. Een knop plant een eenmalige lokale controle zolang de monitor aanstaat.

Het interval is geen exacte afspraak. Android kan werk uitstellen door energiebeheer, belasting of constraints. WorkManager documenteert een minimumherhaalinterval van vijftien minuten; het systeem bepaalt het uitvoeringsmoment. [Android: periodiek werk](https://developer.android.com/develop/background-work/background-tasks/persistent/getting-started/define-work#schedule_periodic_work). De 2.10-reeks is met SDK 35 gebouwd. [WorkManager-releases](https://developer.android.com/jetpack/androidx/releases/work#version_2105).

De worker leest eerst het gewone profiel. Alleen `XT2607-1 / merc / reteu` met Android 17 krijgt daarna de ingeschakelde rootuitlezing. Magisk moet de app zelf al toestemming hebben gegeven. De worker wijzigt geen Superuser-beleid, vraagt geen Motorola-serveraanbod aan en zet geen pakket klaar. Er is geen netwerkconstraint: de bron is de lokale updatercache.

Een melding vereist:

- Echt profiel, gemeten root en diagnostiek van maximaal twee minuten oud.
- Snapshot `NONE`, engine `IDLE` en herkende metadata zonder andere parsewaarschuwingen dan de expliciete metadataresource-aanduiding.
- Veilige niet-lege huidige en doelbuild die verschillen.
- Een verwijzing op de vaste toegestane HTTPS-host en een bekend resourcetype.
- Een identiteit die niet de laatst succesvol gemelde referentie is.

Bij `METADATA_ONLY` vermeldt de melding dat **geen installeerbaar pakket is bevestigd**. Een volledige ZIP-verwijzing blijft een kandidaat waarvoor beschikbaarheid, ondertekening en geschiktheid gecontroleerd moeten worden. De identiteit bevat profiel, huidige build, doelbuild en resourcetype; een gewijzigd URL-token veroorzaakt geen nieuwe melding. De serverouderdom van de cache is hiermee niet bewezen.

Een geïnstalleerde of herhaalde referentie, ontbrekende root, onbekende toestand of leesfout blijft stil. De app bewaart wel beperkte resultaathistorie. Geen metadata betekent niet zeker geen update. Een melding opent het firmwarescherm; zij start geen download, staging of installatie.

Android 13 en hoger vereisen `POST_NOTIFICATIONS` voor gewone meldingen; nieuwe installaties hebben die toestemming standaard niet. Zonder toestemming wordt geen identiteit als succesvol gemeld opgeslagen. [Android: meldingspermission](https://developer.android.com/develop/ui/compose/notifications/notification-permission).

Uitschakelen sluit eerst de opt-inpoort, annuleert periodiek en eenmalig werk en verwijdert eigen monitoralerts. Generaties blokkeren late resultaten van oude workers. Bij opslagfouten worden cancelacties toch geprobeerd en wordt oude opt-inopslag best-effort verwijderd. Een crash tussen publicatie en opslag kan geen exact-oncegarantie bieden; vaste notificatietags en `onlyAlertOnce` beperken herhaalde alerts.

## Expliciete downloads

De UI downloadt alleen een herkende volledige OTA-ZIP-kandidaat voor een andere doelbuild. `payload_metadata.bin` en onbekende schema's zijn geen volledige pakketten. De productieclient accepteert HTTPS op `dlmgredg-vz.gtm.svcmot.com`, zonder userinfo, fragment of afwijkende poort. Redirects worden niet gevolgd; contentencoding moet `identity` blijven.

De UI gebruikt standaard WorkManagers `UNMETERED`-constraint: een ongemeten Android-netwerk, geen garantie dat iedere verbinding letterlijk wifi is. De job draait als `dataSync`-foregroundwerk met voortgang, pauzeren en annuleren. Android-permission- en foregroundbeperkingen blijven gelden. Gewone meldingen weigeren heft de verplichting van een foregroundnotification niet op. [Android: notificaties en foregroundservices](https://developer.android.com/develop/ui/compose/notifications/notification-permission).

| Situatie | Gedrag |
|---|---|
| Nieuwe response | Positieve `Content-Length`, maximaal 12 GiB, geen onverwachte encoding |
| Hervatten | Alleen gefsyncte checkpointbytes, dezelfde URL-identiteit en passende sterke ETag; `Range` met `If-Range` |
| `206` | Exact passende begin-/eindoffset, totale lengte en ETag; anders stoppen |
| `200` na hervatverzoek | Private partial vanaf nul; complete selectie blijft behouden tot commit |
| Geen sterke validator | Geen samenvoeging van mogelijk verschillende entities |
| Voortgang | Duurzame checkpoints na circa 4 MiB en bij voltooiing |
| Voltooid | Exacte omvang, volledige SHA-256, eventuele verwachte hash en atomische commit |
| Pauze/annulering/import | Eerst jobstatus bewaren; een late worker mag de selectie niet overschrijven |

Deze entitycontroles gebruiken het HTTP-contract voor sterke validators, `If-Range` en `Content-Range`. [RFC 9110, If-Range](https://www.rfc-editor.org/rfc/rfc9110.html#section-13.1.5), [Content-Range](https://www.rfc-editor.org/rfc/rfc9110.html#section-14.4).

De transfer bewaart minimaal 256 MiB vrije reserve. Tijdelijke netwerk-/serverfouten kunnen begrensd opnieuw worden geprobeerd; verlopen URL's worden niet vernieuwd. Range-, entity- en hashfouten kunnen een nieuwe partial vereisen. Een handmatig vervangen selectie wordt niet door de oude job teruggezet.

Na een crash rond de rename wordt eerst de geselecteerde file opnieuw gehasht. Een bewezen reeds voltooide commit heeft geen nieuwe HTTP-aanvraag nodig. De download roept geen root, Magisk, bootcontrole of firmware-installatie aan. Voltooide bytes krijgen daardoor nog geen firmwareondertekenings- of compatibiliteitsbewijs.

## Opslag en privacy

Een al aanwezige ZIP uit Motorola's vaste gebruikers-0-map kan ook expliciet worden geïmporteerd. De bronhelper is begrensd tot UID 0/SDK 37 en het onderzochte profiel. De lijst toont maximaal twaalf reguliere kandidaten uit een directory met maximaal 256 entries; namen zijn begrensde ASCII-ZIP-basenamen, geen vrij pad. De gekozen bron blijft ongemoeid. De helper maakt een private unieke transactiefile, controleert bronidentiteit en volledige hashes, waarna de app pas na receipt- en annuleringscontrole de eigen selectie publiceert. Een achtergebleven rootkopie selecteert geen firmware uit zichzelf. Bestandsnamen en importhashes worden niet aan de diagnose-export toegevoegd. Firmwareauthenticatie blijft een volgende stap.

| Gegevens | Opslag |
|---|---|
| Monitor | Private atomische instellingen, interval, generatie, resultaat, builds, laatste notificatiehash en maximaal twaalf historie-items |
| Monitorbron | URL alleen tijdelijk voor hostcontrole; geen signed URL, ruwe XML, boot-id of modulelijst in het monitorbestand |
| Downloadjournal | Opaque job-id, toestand, builds/fingerprint, lengtes, hashes en hervatvalidator; geen plaintext-URL |
| WorkManager-data | Job-id, byteaantallen en foutcode; geen URL/credential |
| Downloadreferentie | AES-GCM met Android Keystore, gebonden aan job-id; verwijderd na afronden/annuleren |
| FirmwareVault | Huidige pakketpin en maximaal zestien ondertekende init_boot-targetrecords; geen URL of importeerbaar vertrouwensmanifest |
| Diagnose-export | Gereduceerde probe-/workflow-, download- en monitorstatus; geen raw URL, boot-id of module-identifiers |

Keystore houdt sleutelmateriaal buiten gewone export. De app belooft geen hardware-backed sleutel op ieder toestel en geen bescherming tegen volledige beheersing van het roottoestel. Een onleesbaar geheim geeft `SECRET_UNAVAILABLE`, geen plaintextfallback. [Android Keystore](https://developer.android.com/privacy-and-security/keystore).

De Vault fixeert bytes via hardlink of een volledige gecontroleerde kopie. Na een geslaagde nieuwe selectiecommit worden oude reguliere UUID-pins opgeruimd; symlinks, mappen en andere namen blijven ongemoeid. Een herladen pad bewijst geen ondertekening. Opgeslagen targetfeiten worden pas na een verse passende waargenomen doelboot aan de gemeten systeemfingerprint gebonden.

Er is geen diagnose-uploadclient. Een expliciete download verstuurt zijn HTTPS-aanvraag aan de toegestane server, die normale verbindingsgegevens en de tijdelijke referentie ziet. Lokale diagnostiek blijft lokaal, behalve een door de gebruiker zelf gedeelde geredigeerde export.

## Motorola-overdracht is een aparte actie

Een gewone app krijgt geen algemene toegang tot de externe appmap van een andere app. De staginghelper gebruikt daarom expliciete roottoegang en vaste gebruikers-0-mappen. Dit is een intern versiegebonden OEM-pad. [Android opslaggrenzen](https://developer.android.com/about/versions/11/privacy/storage#other-apps-data).

Lokale publicatie vereist ondertekend `motorola-channel`/`pre-channel` exact `reteu` én `report.regionVerified`, naast alle andere pakket-, bron- en veiligheidscontroles. `verifyPackage` bewijst de ZIP-ondertekening, niet het kanaal; host, cachemetadata en download-/importreceipts zijn daarvoor ook onvoldoende. Onbekend kanaal geeft `STAGING_CHANNEL_UNPROVEN`. Importeren, downloaden en onderzoeken blijven beschikbaar; gebruik dan Motorola's normale officiële aanbodroute zonder het lokale pakket te publiceren.

Alleen bij toegestane overdracht wordt het pakket buiten Motorola's map voorbereid, gehasht en gefsynct. De native laag publiceert via `renameat2(RENAME_NOREPLACE)`, met directorycontroles en zonder een bestaande file te vervangen. De primitive en het filesystem moeten deze operatie ondersteunen; ontbreken daarvan blokkeert publicatie. [Linux renameat2](https://man7.org/linux/man-pages/man2/rename.2.html).

Motorola kan op een volledig gepubliceerd ZIP meteen reageren. Daarom wordt een pakket met onbekende regio niet voor latere OEM-beoordeling klaargezet. De UI vraagt voor een toegestane overdracht afzonderlijke bevestiging, na stockherstel en nieuwe veiligheidsmetingen. Het receipt bewijst hoogstens de bestandsoverdracht; geen herkenning, aanbod, OTA-installatie, Magisk of nieuwe boot. Zie [UPDATE-WORKFLOW.md](UPDATE-WORKFLOW.md) en [KNOWN-LIMITATIONS.md](KNOWN-LIMITATIONS.md).
