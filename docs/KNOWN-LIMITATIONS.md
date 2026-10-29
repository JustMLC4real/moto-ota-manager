# Bekende beperkingen — 0.3.0

1. **De nieuwe volledige OTA-keten is niet op de telefoon bewezen.** Er zijn echte downloads, bewijsopslag, een bewaakte stockpartitieschrijver, begeleide statusreconciliatie en apart bevestigde Motorola-staging. Het eerdere 0.2.0-toestelbewijs betreft uitlezen en bestaande firmwareverificatie. De nieuwe fase gebruikt de telefoon niet via ADB. [BUILD-REPORT.md](BUILD-REPORT.md) beschrijft de uitgevoerde resultaten.

2. **Geen publiek bevestigd Motorola-aanbodcontract.** De monitor leest lokale updatergegevens en vraagt geen release aan een verzonnen server-API. De historische metadata betreft een al geïnstalleerde OTA. Nieuwe metadata kan een informatieve melding opleveren, maar bevestigt geen beschikbaar/installeerbaar pakket. Ontbrekende metadata bewijst niet dat Motorola geen update aanbiedt.

3. **Beperkte achtergronddekking.** Monitoring staat standaard uit, gebruikt na inschakelen app-roottoestemming en volgt Androids planning. Onbekende profielen, ingetrokken root, actieve/onbekende snapshots of engine en parsewaarschuwingen geven geen OTA-melding. De cache zelf heeft geen bewezen serverversheid. Zie [monitoring en downloads](MONITORING-AND-DOWNLOADS.md).

4. **Hervatting hangt van de server af.** Alleen een passende sterke ETag en gecontroleerde `206`/Content-Range rechtvaardigen voortzetten. Zonder passende validator begint de partial opnieuw. Redirects, ontbrekende lengte, verlopen URL's, onvoldoende opslag of onbeschikbare Keystore-/foregrounddiensten stoppen of blokkeren. Verlopen Motorola-aanbod wordt niet automatisch vernieuwd.

5. **Ontvangen bytes zijn nog geen vertrouwde firmware.** De HTTPS-host en bestandshash vervangen toestelcertificaten, bronfingerprint/-incremental, kanaal-, rollback-, payload- en partitiecontroles niet. Een goedgekeurd rapport is geen geslaagde OTA-toepassing.

   De aparte importfunctie kan een al aanwezige Motorola-ZIP via een begrensde lokale lijst naar eigen appopslag halen. Ook die teruggehashte kopie moet opnieuw door de firmwarecontroles; de naam of bronmap maakt haar niet geauthenticeerd. De OEM-bronfile wordt niet gewijzigd.

6. **OEM-fingerprintreview heft de kanaalcontrole niet op.** Android- en payloadmetadata kunnen verschillende namespaces bevatten. Ook voor een copy-only OEM-overdracht zijn ondertekend `motorola-channel`/`pre-channel` exact `reteu` én `report.regionVerified` verplicht. Ontbrekend of onbewezen kanaal blokkeert publicatie met `STAGING_CHANNEL_UNPROVEN`; een geldige ZIP-handtekening, host, cacheverwijzing, downloadreceipt, bestandsnaam, `cid50` of `release-keys` vervangt dat bewijs niet. Importeren, downloaden en onderzoeken blijven mogelijk. Gebruik voor installatie dan Motorola's normale officiële aanbodroute, zonder het lokale pakket klaar te zetten.

7. **Stockherkomst is versiegebonden.** De geauditeerde catalogus is één exacte referentie. De Vault kan nieuwe ondertekende init_boot-targetfeiten na een passende waargenomen doelboot binden. Een geïmporteerd bewijsbestand, SHA-1-directory, gzip, header of ingevulde buildnaam is geen vertrouwensbron. Gewijzigde OTA-trustanchors maken eerdere Vault-feiten onbruikbaar voor een referentie.

8. **Pinning vraagt soms extra opslag.** Zonder hardlink gebruikt de Vault een volledige gecontroleerde kopie. Download, huidige pin en tijdelijke nieuwe kopie kunnen tegelijk ruimte innemen. Oude reguliere UUID-pins worden pas na een geslaagde selectiecommit opgeruimd. Een mislukte overdracht/selectie behoudt de voorgaande complete selectie.

9. **Stockherstel is een echte risicovolle schrijfactie.** De helper vereist UID 0, SDK 37, exact Android-17-profiel, Magisk 31100 en een huidige init_boot van 8 MiB. Verdere eisen zijn een succesvolle ontgrendelde boot, snapshot `NONE`, idle-engine, minstens 50% accu, 512 MiB vrije ruimte, stockherkomst en handmatig uitgeschakelde automatische updates. Een 60-secondenbevestiging, tweede scan, procesdeadline en tijdreserve garanderen geen foutloze opslaghardware. Onderbreking vraagt uitlezende beoordeling; geen automatische replay, rollback of reboot.

10. **Geen atomair interlock met Motorola.** De eigen writerlock beschermt tegen dubbele schrijvers en gelijktijdige herstelcontrole. Hij vergrendelt Motorola/UpdateEngine niet. Automatische updates uitzetten en momentopnamen verkleinen de race, maar vormen geen OEM-transactiegarantie. De app wijzigt die instelling niet zelf.

11. **Staging kan OEM-gedrag activeren.** Alleen het vaste gebruikers-0-pad `/storage/emulated/0/Android/data/com.motorola.ccc.ota/files` wordt gebruikt, na ondertekend bewezen `reteu`-kanaal en alle overige veiligheidscontroles. Publicatie vereist native `RENAME_NOREPLACE` en directoryduurzaamheid; er is geen overschrijvende of geleidelijk zichtbare kopiefallback. Een fout na publicatie kan betekenen dat het complete ZIP al zichtbaar is terwijl duurzaamheid/receipt onbewezen is. Motorola kan meteen reageren; een onbekende regio mag daarom niet ter latere OEM-beoordeling worden gepubliceerd. De app roept geen OTA-installer aan en claimt op basis van het receipt geen herkenning, geschiktheid of installatie.

12. **Magisk op de andere slot blijft handmatig.** Er is geen ondersteunde externe installer-API aangetoond; interne shellfuncties worden niet uitgevoerd. Gebruikersmeldingen zijn `USER_REPORTED`. Het pure proofcontract in tests is geen productieprobe die targetpatching zelfstandig bewijst. Nieuwe build, juiste slot, root en merge worden daarna afzonderlijk gemeten.

13. **Modules en Wallet vragen eigen controle.** Aanwezigheid/inschakelstatus toont inventaris, geen functionele compatibiliteit of Play Integrity. De app implementeert geen spoofing. Ontbrekende nieuwe stockherkomst geeft een reviewstatus en blokkeert volgende updatevoorbereiding.

14. **Smalle runtimegrenzen.** Android 18, andere Magisk-codes/SKU's/kanalen/gebruikerprofielen en onbekende boot-, snapshot-, ABI- of mountconfiguraties zijn niet als live updateketen gekwalificeerd. Diagnostiek kan beschikbaar blijven; onbekend wordt niet als veilig ingevuld.

15. **Debuguitgave en lokale gegevens.** Een publieke release vraagt een beheerde releasesleutel en releasebeheer. Appopslag en Keystore beschermen gegevens, maar vormen geen verdediging tegen volledige beheersing van het roottoestel. Diagnostiek wordt niet geüpload. Een expliciete download deelt wel normale verbindingsgegevens en de tijdelijke referentie met de toegestane server.

Zie [COMPATIBILITY.md](COMPATIBILITY.md), [SAFETY.md](SAFETY.md) en [UPDATE-WORKFLOW.md](UPDATE-WORKFLOW.md). Een succesvolle simulatie of APK-build vervangt geen hardwaretestrapport.
