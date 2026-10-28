# Testmatrix en grenzen van bewijs

Deze matrix koppelt de twintig gevraagde situaties aan geautomatiseerde tests. De tests gebruiken synthetische firmware, bytefixtures en geïnjecteerde observaties. Geen test flasht een partitie, installeert een OTA of herstart een echt toestel. Een groene test bewijst het beschreven softwaregedrag; hij bewijst geen geslaagde Motorola-OTA op een fysieke XT2607-1.

De werkelijk uitgevoerde taken, aantallen en uitslagen staan in `BUILD-REPORT.md` en de meegeleverde buildlogs/XML-testresultaten. De tabel alleen is geen claim dat een test al is uitgevoerd.

| # | Vereist scenario | Concreet geautomatiseerd bewijs |
|---|---|---|
| 1 | Geen update beschikbaar | `FirmwareDiscoveryTest.noCachedMetadataDoesNotAssertThatMotorolaHasNoUpdate` en `WorkflowTest.noUpdateKeepsIdleAndDoesNotClaimNoServerOffer`: geen lokale metadata/idle veroorzaakt geen fictieve update en vormt geen bewijs over Motorola's serveraanbod. |
| 2 | Nieuwe compatibele update beschikbaar | `FirmwareDiscoveryTest.cachedAllowedUrlIsOnlyCandidateUntilPackageValidation`, `FirmwareValidatorTest.compatiblePackageStillRequiresActualDeviceSignatureVerification`, `WorkflowTest.compatibleUpdateStartsGuidanceWithoutClaimingStockRestored` en `ReadinessTest.observedCompatibleDeviceAndAuthenticatedBackupPassPreflight`: een passende synthetische kandidaat opent alleen begeleiding na de afzonderlijke controles. |
| 3 | Firmware van verkeerd model | `FirmwareValidatorTest.differentModelIsRejected`, `differentDeviceCodenameIsRejected` en `ReadinessTest.wrongModelAndRegionAreBlockedIndependently`: verkeerd model/codenaam blokkeert. |
| 4 | Firmware van verkeerde regio | `FirmwareValidatorTest.differentChannelIsRejected`, `deviceWrongRegionIsRejected`, `absentRegionMetadataIsExplicitlyUnproven` en `BackupValidatorTest.referenceForWrongRegionOrPartitionIsRejected`: verkeerd of onbewezen kanaal/partitietype blokkeert. |
| 5 | Incrementele OTA met verkeerde bronbuild | `FirmwareValidatorTest.wrongSourceIncrementalIsRejected`, `wrongSourceFingerprintIsRejected`, `missingSourceIncrementalCannotProveCompatibility` en `BackupValidatorTest.differentCurrentBuildRejectsOldBackup`: bronmetadata en stockreferentie worden apart getoetst. |
| 6 | Originele init_boot-back-up ontbreekt | `ReadinessTest.missingOrUnprovenBackupBlocks`: ontbreken én alleen structurele validiteit zijn onvoldoende. |
| 7 | Beschadigde imageback-up | `BackupValidatorTest.shellOutputOf83ExtraBytesIsRejected`, `truncatedImageIsRejected`, `gzipWithDamagedCrcIsRejected`, `gzipWithTrailingShellOutputIsRejected` en `patchedContentMismatchRejectsEvenWellFormedHeader`. |
| 8 | Onvoldoende opslag | `ReadinessTest.insufficientStorageBlocks`: onvoldoende ruimte blokkeert ongeacht andere geldige controles. |
| 9 | Geen rootrechten | `ReadinessTest.noRootBlocksEvenWithValidBackup`. |
| 10 | Actieve snapshot-merge | `ReadinessTest.activeOrUnknownSnapshotStatesAlwaysBlock`, `WorkflowTest.activeMergeOrUnknownMergeCannotComplete` en `ObservationParsersTest.conflictingIdleAndMergeEvidenceFailsClosed`. |
| 11 | OTA-download mislukt | `PackageTransferTest.networkFailureMidStreamPreservesPreviousPackageAndRemovesPartial`, `truncatedDownloadNeverBecomesCompletedFile`, `oversizeOrMisdeclaredDownloadIsRejected` en `coroutineCancellationLeavesNoCompletedDownload`: echte streamfout/mislukte transportvalidatie laat geen half bestand of verwijderde eerdere versie achter. `WorkflowTest.missingDownloadOrPackageVerificationBlocks` controleert de vervolgstap. |
| 12 | Ongeldige ZIP | `FirmwareValidatorTest.nonZipInputIsRejectedWithoutException`, `emptyInputIsRejectedWithoutException`, `absentPayloadEntryIsRejected`, `absentPropertiesEntryIsRejected`, `absentAndroidMetadataIsRejected`: ongeldige ZIP en onvolledige structuur worden geweigerd. |
| 13 | Verificatiefout | `FirmwareValidatorTest.payloadHashMismatchNeverCountsAsVerified`, `metadataHashMismatchIsRejected`, `compatiblePackageStillRequiresActualDeviceSignatureVerification`: eigen hashes en labels bewijzen geen officiële signature. `BackupValidatorTest.unauthenticatedManifestCannotDeclareImageStock` dekt onafhankelijk de stockimage-vertrouwensgrens. |
| 14 | OTA-engine meldt fout | `WorkflowTest.otaEngineErrorIsTerminalAndNotHiddenByLaterIdle`, `ObservationParsersTest.payloadFailureCannotBeHiddenByFollowingIdleStatus`. |
| 15 | Magisk-installatie inactieve slot mislukt | `WorkflowTest.failedInactiveSlotMagiskStepIsRecordedAsFailure`; dit is een geïnjecteerde externe foutmelding, geen echte Magisk-patchtest. |
| 16 | Root na herstart verdwenen | `WorkflowTest.lostRootAfterRebootCannotBeSuccess`: ontbrekende rootbevestiging blokkeert succes; weigering van roottoestemming kan dezelfde waarneming geven. |
| 17 | App halverwege afgesloten | `WorkflowTest.processDeathRestoresJournalThenReconcilesLiveObservations`, `persistedManualNotesAndLineBreaksRoundTrip`: opgeslagen verwachtingen worden opnieuw getoetst aan actuele observaties. |
| 18 | Onverwachte herstart | `WorkflowTest.unexpectedRebootBeforeOtaRequiresRecovery`, `oldSlotAfterPendingOtaIsPossibleRollback`: herstelbeoordeling en mogelijke rollback blijven onderscheiden. |
| 19 | Andere actieve slot dan verwacht | `WorkflowTest.wrongActiveSlotIsNotSuccessEvenOnTargetBuild`, `currentBuildChangeWithoutNewBootIsContradiction`. |
| 20 | Motorola toont niets maar externe bron noemt release | `FirmwareDiscoveryTest.externalReleaseDoesNotOverrideUpToDateMotorolaResponse` en `externalReportAloneCannotSupplyTrustedDownload`: expliciet gemockte discrepantie blijft een niet-bevestigde melding en geeft geen download- of installatietoestemming. |

## Bronbestanden

- `core/src/test/kotlin/nl/moto/ota/core/ReadinessTest.kt`
- `core/src/test/kotlin/nl/moto/ota/core/BackupValidatorTest.kt`
- `core/src/test/kotlin/nl/moto/ota/core/WorkflowTest.kt`
- `core/src/test/kotlin/nl/moto/ota/core/FirmwareValidatorTest.kt`
- `core/src/test/kotlin/nl/moto/ota/core/PackageTransferTest.kt`
- `core/src/test/kotlin/nl/moto/ota/core/FirmwareDiscoveryTest.kt`
- `app/src/test/java/nl/moto/ota/platform/ObservationParsersTest.kt`

## Extra veiligheidsgevallen

De tests toetsen ook onbekende toekomstige Magisk-versies, onbekende snapshot-enums, verlopen/toekomstgedateerde diagnostiek, ontbrekende Virtual A/B-bevestiging, te lage accu, ontbrekende partities, gewijzigde fingerprints, geen boot-successbevestiging, ontbrekende modules, gewijzigde bootsecurity, beschadigde statusopslag en vermenging van simulatie met echte observaties.

`WorkflowTest.fullObservedOutcomeIncludesSuccessfulBootRootAndMerge` simuleert de noodzakelijke eindcontroles samen. Een door de gebruiker gemelde stockrestore of Magisk-installatie wordt apart opgeslagen en kan de eindstatus niet zelfstandig op geslaagd zetten. `WorkflowTest.magiskCannotBeReportedBeforeObservedOta` voorkomt bovendien dat een handmatige melding de volgorde omzeilt.

## Wat deze suite bewust niet bewijst

### Aanvulling: daadwerkelijke 0.2.0-toesteltests

Op de aangesloten XT2607-1 zijn vier opt-in tests uit `ReadOnlyDeviceValidationTest` geslaagd: `realDiagnosticsMatchTheIndependentReadOnlyBaseline`, `cachedMotorolaMetadataDoesNotOfferTheInstalledUpdateAgain`, `actualBackupMatchesTheAuthenticatedStockAndUpdaterResolvesExplicitly` en `existingHistoricalOtaPassesTheActualPlatformSignatureVerifier`. Zij meten echte root, bootmetadata, snapshots, callbacks, trustanchor, back-up, Motorola-cache en positieve Android-signatureverificatie; geen mockwaarden of schrijfacties. De drie `SafeUiTest`-gevallen slagen ook op dit toestel.

Daarnaast is `FirmwareValidatorTest.observedHistoricalPackageIsCorrectlyDiagnosedWhenExplicitlyProvided` op de host uitgevoerd met de echte bestaande 3,42 GB-ZIP. In CI zonder expliciete `MOTO_OTA_PATH` is dit opt-in-geval overgeslagen. De live toestelklasse vereist eveneens expliciete autorisatie via het runnerargument; zij draait niet automatisch op een andere telefoon. [Alle actuele aantallen en logbestanden](BUILD-REPORT.md).

- Werkelijke Motorola-serveraanbiedingen, gefaseerde uitrol of de oorzaak van `ALREADY_UP_TO_DATE`.
- De betrouwbaarheid van een interne Motorola-map als toekomstige installatie-interface.
- Werkelijk herstel van een stockimage of Magisk-patching in een andere slot.
- Een succesvolle fysieke OTA, Android 18-compatibiliteit, functionele modulecompatibiliteit, Wallet of Play Integrity.

Die controles vereisen de afzonderlijke handmatige checklist en, voor schrijfacties, een later expliciet geautoriseerde toesteltest. De geleverde app bevat geen eigen uitvoerpad voor dergelijke schrijfacties.
