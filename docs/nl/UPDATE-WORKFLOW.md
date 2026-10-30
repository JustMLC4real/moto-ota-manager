# Updateworkflow en herstel — 1.0.1

[English](../UPDATE-WORKFLOW.md) · [Compatibiliteit](COMPATIBILITY.md) · [Veiligheid](SAFETY.md)

Verwachte toestand en werkelijk bewijs blijven gescheiden. Het doel is één begrijpelijke startactie voor een geschikte update, met de getoonde reikwijdte voor rootbehoud en herstart. Bij iedere handeling blijven technische controles nodig. Een onbekende of niet-ondersteunde stap stopt met de reden en een officiële terugvalroute. Starttoestemming vervangt geen bewijs. [Integratiekeuzes](INTEGRATION-DECISIONS.md).

De ontwikkel- en emulatorsuite beschrijft geen echte partitie, installeert geen telefoon-OTA en herstart geen telefoon. [BUILD-REPORT](../BUILD-REPORT.md) noemt de werkelijk geleverde build en uitgevoerde tests. Historische uitlezing bewijst geen nieuwe automatische update.

## Voorbereiding

Geef deze app Magisk-roottoegang wanneer je beschermde diagnostiek vraagt. ADB-root is een andere toestemming. Zet **Automatische systeemupdates** zelf uit in Ontwikkelaarsopties; de app controleert die instelling en wijzigt haar niet. Lees vóór starten de volledige reikwijdte. Online detectie vraagt ook toestemming voor de genoemde identificatiegegevens; dat kan binnen de eerste starttoestemming en vereist geen nieuw dialoogvenster per updatefase.

Verse metingen moeten Motorola-identiteit, bronbuild/fingerprint, boot-ID, A/B-slots, succesvolle boot, kernelbeveiliging, root/Magisk, modules, accu, opslag, idle-engine en veilige snapshots vaststellen. Bij aantoonbaar traditionele A/B kan snapshotstatus niet van toepassing zijn; onbekende of tegenstrijdige Virtual A/B-toestand blokkeert.

De werkelijke indeling en geauthenticeerde image bepalen `boot` of `init_boot`. De stockschrijver onderzoekt API 33–37 en Magisk 31.1/code31100, streamt begrensde images tot 512 MiB en vereist de exact gemeten partitiegrootte. De 8 MiB van het referentietoestel is geen universele aanname. Enkele of onbekende slots kunnen de inactieve-slotroute niet gebruiken.

## Pakket- en stockbewijs

De primaire Motorola-CDS-controle levert na deeltoestemming een kandidaat. Een aanbod of downloadlink is nog geen goedgekeurde installatie. De complete private ZIP wordt onderzocht op payload, hashes, bron/doel, wipe/downgrade en echte OEM-ondertekening. Overdracht en HTTPS vervangen de handtekening niet.

De huidige originele `boot`/`init_boot` vereist onafhankelijk geauthenticeerde manifesthash/grootte, passende huidige identiteit en een verse Magisk-runtime-SHA1-koppeling. Alleen `boot.img.gz`, de SHA1-directory of een geldige header volstaat niet. Zo kan een oude, op zichzelf authentieke image bij een gespoofde build toch niet worden hersteld. De runtimeconfig wordt begrensd met stabiele no-follow-bestandscontrole gelezen, uitsluitend uit bekende Magisk-roots; de app voert die config niet als shellcode uit.

Stockbytebewijs bewijst geen toekomstig OTA-kanaal. De aparte bestaande Motorola-stagingroute houdt haar verplichte ondertekende kanaalbewijs. [Stockherkomst](../STOCK-PROVENANCE.md), [CDS-provider](CDS-PROVIDER.md), [stagingcontract](../MOTOROLA_STAGING.md).

## Eén starttoestemming, verse actiecontroles

Runautorisatie bindt UUID, toestel/kanaal, bronbuild/fingerprint/boot/slot, doelbuild/fingerprint, pakket-SHA256, handelingen en zowel klok- als elapsed-timegrenzen. Een aanvankelijk onbekende pakkethash mag alleen tot een geverifieerd pakket voor hetzelfde doel worden vernauwd. Later een ander pakket invoegen mag niet.

Korte actietoestemmingen worden alleen voor de actieve geverifieerde run in het huidige proces uitgegeven. App en root bewaren aparte duurzame eenmalige verbruiksmarkers. Root controleert toestemming en actuele toestelvoorwaarden opnieuw vóór uitvoering. Opgeslagen autorisatie is na procesherstart uitleesbaar, maar activeert geen oude opdracht of replay. Een klokwijziging verlengt elapsed-timebevoegdheid niet.

Een geschikte route kan zo zonder losse bevestigingen voor stock, OTA, Magisk en herstart doorgaan. Een eventuele voorkeur voor toekomstige updates is afzonderlijk expliciet beleid: een oude actietoestemming geldt nooit voor een ander pakket, toestel of nieuwe boot. Gewone monitoring en meldingen starten zelf geen installatie.

| Fase | Vereist bewijs |
|---|---|
| Stockherstel | Volledige huidige gepatchte partitie veiligstellen en hashen, autorisatie verbruiken, bron en gates opnieuw meten, alleen de gemeten actieve bootpartitie schrijven, synchroniseren en volledig teruglezen. |
| OTA | Androids handtekening-, payload-, rollback- en bronblokcontroles behouden. De engine moet toepassing bewijzen; een processtart of ZIP-kopie doet dat niet. |
| Root op doelslot | Doelslot na OTA en onafhankelijk geauthenticeerde nieuwe stockbytes vaststellen. Een geschikte Magisk-adapter moet de patch en koppeling bewijzen; een gebruikersmelding blijft `USER_REPORTED`. |
| Herstart | Passende voltooide voorbereiding en doelbewijs binnen de eerste reikwijdte vereisen. Pending-toestand eerst bewaren. Een appherstart is geen nieuwe boot. |
| Doelboot | Nieuwe boot-ID, werkelijke doelbuild en verwachte slot, succesvolle boot, root/Magisk, kernelbeveiliging en modules lezen. |
| Merge/eindcontrole | Werkelijke snapshotvoltooiing en idle-engine afwachten. Nieuwe huidige stockback-up apart authentiseren; ontbrekend bewijs laat review open. |

Dit zijn bewijscontracten, geen certificering van ieder toestel. [Compatibiliteit](COMPATIBILITY.md) beschrijft adaptergrenzen. Officiële Motorola- en Magisk-navigatie blijft de terugval als de voorwaarden niet bewezen kunnen worden.

## Onderbreking en herstel

- Na procesherstart leest de app journaal en toestel opnieuw; hij voert geen opgeslagen opdracht uit.
- Een actieve writerlock verhindert dat recovery succes bevestigt.
- Een onbekende of afwijkende actieve hash na onderbreking geeft `RECOVERY_REQUIRED`. Back-up en diagnostiek blijven behouden. Geen automatische replay, rollback of reboot.
- Een passende actieve stockhash is onvoldoende: ook de volledige bewaarde gepatchte back-up en exacte broncontext moeten opnieuw kloppen.
- Alleen wanneer de bron nog exact de oorspronkelijke gepatchte image bevat, kan expliciete uitlezende recovery alle gates meten, een ontbrekende back-up vastleggen en oude toestemming ongeldig maken vóór een nieuw plan.
- Een bronbuild/slot na een echte nieuwe boot duidt op mogelijke rollback. Geweigerde roottoestemming kan met een verse uitlezende eindcontrole worden onderzocht en bewijst op zichzelf geen verwijderde Magisk.
- Engine-/snapshotfouten blijven zichtbaar. Geen reset, force-merge, slotwissel of verbergen na een latere idle-meting.
- Een anderszins geslaagde waargenomen update zonder nieuwe stockherkomst eindigt met `COMPLETE_REVIEW`; een volgende onbewezen stockwrite blijft geblokkeerd.
- Een corrupt of te groot bestaand journaal vereist herstel. Alleen een ontbrekend journaal betekent idle.

De volledige gepatchte bronback-up staat onder `files/update/transactions/<uuid>/patched-backup.img`. Exporteer die bewust als dat nodig is. Hij wordt nooit automatisch teruggeflasht. De juiste herstelroute hangt af van actuele boot, slot, engine en snapshots; daarom staat hier geen universeel flashcommando.

De writerlock vergrendelt Motorola's eigen updater niet atomair. Watchdogs en fsync garanderen geen succes bij stroom- of opslaguitval. Back-up en volledige readback maken de handeling controleerbaar, niet risicoloos.

## Oefenen met bestanden

`SandboxUpdateSession` gebruikt gewone bestanden onder een nieuwe `moto-ota-sandbox-`-directory, een apart journaal en `simulated=true`. OTA, Magisk, slots, root, herstart en merge zijn expliciete mocks; back-up, eenmalige toestemming, readback en foutherstel zijn echte bestandstransacties. De simulatie wijzigt het live journaal niet en kan geen productieactie toestaan. Succes bewijst het bestandsscenario, geen opstartbaarheid van een echte image. [Testmatrix](../TEST-MATRIX.md).
