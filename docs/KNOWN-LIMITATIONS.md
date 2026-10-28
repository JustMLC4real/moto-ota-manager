# Bekende beperkingen

1. **Geen bewezen volledige éénknops-OTA.** De app verzamelt en controleert gegevens en begeleidt de officiële apps. Deze release schrijft geen partities, installeert geen OTA, wisselt geen bootslot en herstart niet.
2. **Geen hardwarevalidatie in deze ontwikkelsessie.** Werkelijke properties, rootpolicy, Motorola-private metadata en Magisk-back-upinhoud op de Edge 70 Pro zijn nog niet met deze APK getest. Zie [handmatige tests](MANUAL-TESTS.md).
3. **Geen publiek bevestigd Motorola discoverycontract.** Een oude download-URL of externe releasemelding bewijst geen actueel aanbod voor deze telefoon. Afwezige metadata wordt onbekend, niet “zeker geen update”.
4. **Geen geforceerd resetten van Motorola's aanbod.** Lokale fouttellers zijn uitleesinformatie. De app wist geen appdata en verandert geen serverregistratie, kanaal of beveiligingstoestand.
5. **Regio staat niet verplicht in AOSP OTA-metadata.** Zonder onafhankelijk kanaalbewijs kan een pakket niet volledig compatibel worden verklaard. De bestandsnaam mag dit bewijs niet vervangen.
6. **Geen stock-imageprovenance door alleen een Magisk-back-up.** Gzip-integriteit, SHA-1-directory en imageheader zijn controlepunten, geen firmwareherkomst. Een onbekende back-up blijft ongeschikt voor automatisch herstel.
7. **Geen stabiele externe Magisk-installer-API aangetoond.** Interne Magisk-shellfuncties worden niet uitgevoerd. Het openen van Magisk is geen bewijs van een geslaagde installatie.
8. **Beperkte engine- en snapshottoegang.** Ontbrekende tools, OEM-aanpassingen of SELinux kunnen uitlezing blokkeren. Een onleesbare status is een echte blokkade; de app verzint geen nulpercentage of IDLE.
9. **Pakketverificatie kent lagen.** Structuur en hashes tonen integriteit; verifyPackage controleert ZIP-ondertekening tegen toestelcertificaten. Payload-, bronblok-, regio-, rollback- en installercontroles blijven afzonderlijk nodig.
10. **Geen brede externe firmware-indexintegratie.** Er worden geen onbekende firmwarebestanden gescrapet. Dat voorkomt een onbewezen regio-/modelkeuze.
11. **Geen automatische successuggestie voor modules.** De aanwezigheid van een module betekent niet dat Wallet, Play Integrity of de module na een nieuwe Android-versie werkt. Deze app implementeert geen spoofingmethode.
12. **Debug-APK.** De geleverde APK is voor gecontroleerd lokaal testen. Een publieke productie-uitgave vraagt een eigen beheerde releasesleutel, verdere apparaatvalidatie en releasebeheer.

Het concrete buildresultaat en de daadwerkelijk uitgevoerde tests zijn uitsluitend vastgelegd in `BUILD-REPORT.md` en de geleverde buildlogs. Ontwerpmogelijkheden in het onderzoek zijn geen claim dat ieder onderdeel al op hardware werkt.
