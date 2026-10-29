# Handmatige validatie op de Edge 70 Pro

Deze checklist is een uitlezend testplan. Eerdere werkelijk uitgevoerde diagnosemetingen staan apart in [DEVICE-VALIDATION](DEVICE-VALIDATION.md). De nieuwe productie-stockwriter en een volledige toekomstige echte OTA zijn niet fysiek getest. Voer voor deze checklist alleen uitlezende stappen uit: geen OTA-installatie, stockrestore, slotwijziging of telefoonherstart. Een USB-verbinding of handmatig ADB is niet de normale appworkflow.

## Voorbereiding

- Controleer de SHA-256 van de geleverde debug-APK tegen het opleverrapport en installeer de APK zelf via Androids pakketinstaller.
- Open Moto OTA Manager en controleer dat echte gegevens en simulatie duidelijk onderscheiden worden.
- Vergelijk Android-versie, buildnummer, model en beveiligingspatch met Instellingen → Over de telefoon. De huidige waarden uit de specificatie zijn een vergelijking, geen hardcoded waarheid.
- Bekijk vóór roottoegang de foutmelding voor ontbrekende rootrechten. De app mag niet vastlopen of de fictieve doelwaarden tonen.
- Verleen bij Magisk 31.1 desgewenst vooraf toegang in Magisk → Superuser aan Moto OTA Manager. In deze versie verschijnt niet vanzelf een reactieve rootdialoog. Verander geen andere Magisk-instellingen.

## Uitlezende toestelcontrole

- Voer “Controleer en bereid update voor” uit. Dat moet uitlezen en blokkades tonen; de knop mag geen update starten.
- Vergelijk werkelijk gemeten `XT2607-1`, `merc` en `reteu`. Als Motorola de modelnaam anders opslaat, noteer de precieze property en laat ondersteuning pas na bewijs aanpassen.
- Bekijk systeem- en vendor-fingerprint afzonderlijk. De productnaam `merc_g_syseq` mag niet stilzwijgend naar `merc_g_sys` worden herschreven.
- Controleer huidige en inactieve slot. De oude verwachting `_b` is geen bewijs van de huidige slot. Onbekende of tegenstrijdige metingen moeten blokkeren.
- Controleer of de relevante `init_boot`-partities worden gevonden en of de metingen van partitieomvang consistent zijn. Doe geen schrijfactie om dit te “testen”.
- Bekijk snapshotstatus. Een echte merge, ontbrekende tool, ontbrekend percentage of permissiefout mag nooit worden weergegeven als gereed.
- Vergelijk de status opnieuw na enkele minuten normaal gebruik. Een oud logpercentage moet niet als live percentage terugkeren.
- Bekijk engine-status en bron. Als er geen uitleesinterface beschikbaar is, moet de app dat onbekend noemen. Zet geen Android-service aan/uit om een groene controle af te dwingen.
- Controleer Magisk-versie en rootstatus apart. De aanwezigheid van de Magisk-app alleen mag geen werkende root bewijzen.

## Back-updiagnostiek

- Bekijk de geïnventariseerde Magisk-back-ups. De app moet compressiegrootte onderscheiden van uitgepakte bytes.
- Laat een onbenoemde of niet aan de actuele build gekoppelde back-up als onbewezen zien, ook als de gzip en hash geldig zijn.
- Controleer dat de oude Android 16-image nooit als stock voor de huidige Android 17-build wordt voorgesteld.
- Gebruik een aparte onschuldige testkopie voor corruptietests; wijzig geen originele Magisk-bestanden. Een afgekapt gzipbestand of image met extra bytes moet worden afgewezen.
- Controleer dat een vermelde module alleen “aanwezig” heet. De app mag daaruit geen Android 17- of Android 18-compatibiliteit afleiden.
- Open eventueel alleen de Magisk-app om de appoverdracht te testen; tik niet op Restore Images, Install of Uninstall.

## Motorola en firmware

- Test het openen van de officiële updatepagina. Controleer dat het echt een systeemcomponent is.
- Gebruik alleen de normale handmatige updatecontrole als je dat zelf wilt. Start geen aangeboden download/installatie binnen deze validatie.
- Controleer dat nul fouttellers als lokale diagnose worden gepresenteerd; ze bewijzen geen serveraanbod.
- Indien oude downloadmetadata bestaat: de app mag een kandidaat tonen, maar geen complete ondertekende URL of token exporteren en geen actualiteit claimen.
- Selecteer via de bestandskiezer een reeds beschikbaar, onafhankelijk verkregen OTA-testbestand. Het bestand mag niet naar de Motorola-appmap of naar een partitie worden gekopieerd.
- Controleer structurele foutafhandeling met een onschadelijk tekstbestand dat `.zip` heet.
- Controleer dat een vals testpakket met intern consistente hashes alsnog geen “officieel geverifieerd” resultaat krijgt.
- Controleer dat model, bronbuild, kanaal en authenticiteit afzonderlijk worden getoond. Een officieel ondertekend maar verkeerd pakket blijft geblokkeerd.
- Een onbekend kanaal in AOSP-metadata moet een blokkade geven, ook als de bestandsnaam `reteu` of `cid50` suggereert.

## Duurzame workflow en procesherstel

- Gebruik de expliciete simulatie om de negen fasen en foutscenario's te bekijken. Verwacht een zichtbaar simulatielabel.
- Gebruik de volledige bestandssimulatie: stockrestore → officiële mock-OTA → gerapporteerde mock-Magisk → mockreboot/merge → eindcontrole. Controleer dat bron- en doelpartitie gewone app-private bestanden zijn en dat de volledige gepatchte bronbackup en nieuwe stockbackup bestaan. Geen rootopdracht mag nodig zijn.
- Sluit alleen Moto OTA Manager via de recente-appslijst of force stop en open opnieuw. Er mag geen toestelherstart nodig zijn.
- Controleer dat opgeslagen stappen blijven bestaan maar actuele controles opnieuw worden uitgevoerd.
- Verander in een simulatie de bootslot of rootstatus en controleer dat succes wordt ingetrokken.
- Een gesimuleerde onverwachte herstart moet tot hercontrole leiden, niet tot automatisch verdergaan met de volgende schrijfstap.
- Verlaat de simulatie en controleer dat fictieve toestelwaarden, pakketgegevens en succesmeldingen niet als echte diagnose terugkeren.
- Controleer een bestaand leeg of beschadigd fixturejournaal: het moet herstel vereisen, geen nieuwe lege echte workflow starten.
- Laat in een sandbox een processtop vóór write en een gedeeltelijke write simuleren. Recovery mag niets opnieuw schrijven. Een volledig onveranderde gepatchte bron kan alleen na expliciete controle plus oude-tokeninvalidatie een nieuw plan vrijgeven; een werkelijk afwijkende gedeeltelijke image moet geblokkeerd blijven.
- Controleer dat late knoppen/callbacks na targetboot geen oude OTA- of rebootfase opnieuw activeren.

## Voorbereidingsscherm zonder live schrijfvalidatie

- Bekijk de uitleg van de actuele partitie, bronbuild, volledige stockhash, bewaarde gepatchte backup en de regel om niet tussentijds te herstarten. Druk in dit uitlezende testplan niet op de daadwerkelijke schrijfbevestiging.
- Verifieer in sandbox dat beide expliciete bevestigingen vereist zijn en dat 60 seconden verstreken monotonic-tijd ook bij een teruggezette wallclock blijft verlopen.
- Controleer dat een onbekende instelling voor automatische updates de live voorbereiding blokkeert; de app mag de systeeminstelling niet zelf wijzigen.
- Gebruik de officiële Motorola-route met een uit de updater overgenomen doelbuild en zonder ZIP. Eventuele generieke metadata-/regioblokkades mogen niet worden omgezet in `compatible=true`; Motorola blijft de installatiemachtiging beoordelen.
- Controleer dat Magisk vóór boot slechts als gebruikersmelding verschijnt. Er is momenteel geen productieprobe voor onafhankelijk preboot-imagebewijs.

## Rapport en privacy

- Exporteer naar een zelfgekozen locatie en open het rapport lokaal.
- Zoek op `https://`, `token`, `auth`, `imei`, `serial`, `account`, e-mailadres, persoonlijke bestandsnamen en boot-ID. Gevoelige waarden mogen er niet in staan.
- Vergelijk technische statuscategorieën en foutcodes met het scherm. Het rapport moet het verschil tussen echte en gesimuleerde data behouden.
- Controleer dat niets automatisch naar GitHub of een andere dienst wordt geüpload.

## Resultaten vastleggen

Noteer APK-hash, appversie, Android-build, Magisk-versie, datum en een lokaal geanonimiseerd rapport. Leg per test `geslaagd`, `afwijking` of `niet beschikbaar` vast. Stuur geen ondertekende downloadlinks of volledige private updaterbestanden mee.

Een echte OTA-validatie is een afzonderlijk toekomstig testplan waarvoor firmwarebewijs, herstelvoorbereiding en expliciete toestemming nodig zijn. Deze checklist is daar geen toestemming voor en een geslaagde read-only test bewijst nog geen veilige OTA-installatie.
