# Bekende beperkingen — 1.0.1

[English](../KNOWN-LIMITATIONS.md)

1. **Fysieke kwalificatie is specifiek.** Historisch toestelbewijs betreft uitlezende observaties op het referentietoestel. Nieuwe schrijvers, andere modellen en de volledige toekomstige OTA/rootbehoudketen zijn daarmee niet fysiek bewezen. Actuele builds en uitgevoerde tests staan in [BUILD-REPORT](../BUILD-REPORT.md).

2. **CDS is intern.** De primaire detector gebruikt echt HTTPS en een strikt brongebaseerd protocol, maar heeft geen publieke Motorola-SDK-garantie. Serveracceptatie van beperkte optionele telemetrie vereist een apart toegestaan praktijktestverzoek. Geen aanbod bewijst geen ontbrekende uitrol. [Providerbewijs](CDS-PROVIDER.md).

3. **Identificaties vragen toestemming.** Onlinecontrole vereist gemeten serienummer en buildcontext-GUID. Weigering, onleesbare properties, andere regio of tegenstrijdige identiteit stopt het verzoek. Geen IMEI of geraden vervanging. Gewone diagnostiek en officiële updater blijven waar ondersteund beschikbaar.

4. **Achtergrondcontrole is geen exacte wekker.** Android bepaalt planning en permissions. Monitoring staat eerst uit; onlinecontrole vereist ook deeltoestemming. Cache kan oud zijn; metadata-only en herhaalde/huidige targets worden geen nieuw volledig pakket. [Monitoring](MONITORING-AND-DOWNLOADS.md).

5. **Overdracht hangt van de bron af.** Eén exacte HTTPS-downloadhost. Willekeurige resourceheaders, redirects, verlopen verwijzingen, ongeschikte lengte/validators of ontbrekende foreground/Keystore/opslag kunnen stoppen. Hervatten vereist een passende sterke validator. OEM-kopie bewijst overdracht, geen authenticiteit.

6. **Stockbewijs heeft beperkte betekenis.** OEM-ZIP-authenticatie plus exacte huidige imagehash/grootte en verse runtimekoppeling kan huidige partitiebytes bewijzen. Geen nieuw OTA-kanaal of geschiktheid. Expliciete ondertekende tegenspraak blokkeert; gewijzigde trustanchors of toestel/build maken oude koppelingen ongeldig. [Stockherkomst](../STOCK-PROVENANCE.md).

7. **Lokaal klaarzetten blijft smaller.** Het interne OEM-pad kan direct verwerking starten en vereist vóór publicatie ondertekend `reteu`-kanaalbewijs. Ontbrekend bewijs blokkeert met `STAGING_CHANNEL_UNPROVEN`. CDS, host, handtekening of receipt vormt geen uitzondering. Publicatie/readback bewijst geen installatie. [Stagingcontract](../MOTOROLA_STAGING.md).

8. **Schrijven is niet risicoloos.** De onderzochte API 33–37/Magisk 31100-adapter vereist alle verse mogelijkheden-, image-, back-up- en live controles. Writerlocks vergrendelen Motorola niet. Onderbreking vereist uitlezing/herstel, geen replay. Stroom- en opslagfouten blijven mogelijk. Toekomstige runtimes worden niet stilzwijgend goedgekeurd.

9. **Automatisering verzint geen bewijs.** Starttoestemming begrenst de beoordeelde run; zij maakt niet-ondersteunde OEM/Magisk-handelingen of gebruikersmeldingen niet geverifieerd. Ontbrekend bewijs moet met een heldere terugval pauzeren. Root/build/slot/succesvolle boot/snapshots/nieuwe back-up zijn aparte controles.

10. **Modules en betaalapps vereisen eigen controle.** Inventaris bewijst geen werking, Play Integrity of Wallet-gedrag. De app spooft geen attestatie of beveiligingsstatus. Onbewezen nieuwe stockherkomst kan eindcontrole laten openstaan en volgend schrijven blokkeren.

11. **Private opslag heeft praktische grenzen.** Zonder hardlink volgt volledige pinkopie; grote download/pin/tempbestanden kunnen tegelijk bestaan. Atomaire selectie behoudt de vorige volledige file bij mislukking. Keystore/private opslag beschermt niet tegen totale rootcontrole. Onlinecontrole/download deelt de genoemde netwerkgegevens.

12. **Signingidentiteiten tellen.** Unsigned release is CI-uitvoer. Een getekende productie-APK vereist een private bewaarde sleutel en kan een debugapp niet als gewone update vervangen. Plan gegevensbehoud bij migratie bewust. [Releasehandleiding](RELEASING.md).


13. **Automatisch rootbehoud vereist exact onderzochte tools.** Alleen de officiële geïnstalleerde Magisk31.1-APK en ondersteunde ABI worden toegelaten. Verborgen/herverpakte managers, andere versies, recovery/vendor-boot of onbewezen preinit stoppen of gebruiken terugval. Private bestandspatch, echte inactieve-partitiewrite en root na boot blijven apart bewijs. Bestandstests bewijzen geen OEM-Binder/SELinux-toegang of opstartbaarheid. [Integratiekeuzes](INTEGRATION-DECISIONS.md).

Implementatie, synthetische tests en fysieke resultaten blijven apart. [Compatibiliteit](COMPATIBILITY.md) · [Veiligheid](SAFETY.md).
