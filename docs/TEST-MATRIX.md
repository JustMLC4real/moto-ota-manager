# Testmatrix en bewijsgrenzen — 0.3.0

Deze matrix koppelt de twintig gevraagde situaties aan de echte productieonderdelen en tests. Een test met fixtures bewijst softwaregedrag binnen die fixture; hij bewijst geen toekomstige fysieke Motorola-OTA. De daadwerkelijk uitgevoerde builds, aantallen, XML en logs staan in [BUILD-REPORT](BUILD-REPORT.md).

## De twintig gevraagde situaties

| # | Situatie | Geautomatiseerde dekking |
|---|---|---|
| 1 | Geen update | `FirmwareDiscoveryTest.noCachedMetadataDoesNotAssertThatMotorolaHasNoUpdate`, `alreadyInstalledCachedBuildIsNotNewUpdate`; monitoring meldt geen oude/afwezige kandidaat als nieuwe OTA. |
| 2 | Nieuwe geschikte kandidaat | `FirmwareValidatorTest.compatiblePackageStillRequiresActualDeviceSignatureVerification`, `ReadinessTest.observedCompatibleDeviceAndAuthenticatedBackupPassPreflight`, `UpdateCoordinatorTest.officialRouteDoesNotRequireGenericPackageMetadataCompatibility`. Authenticated package en officiële OEM-begeleiding blijven aparte routes. |
| 3 | Verkeerd model | `FirmwareValidatorTest.differentModelIsRejected`, `differentDeviceCodenameIsRejected`, `MotorolaStagePolicyTest.wrongProfileRuntimeBootAndChangedSourceAreBlocked`. |
| 4 | Verkeerde regio | `FirmwareValidatorTest.differentChannelIsRejected`, `deviceWrongRegionIsRejected`, `absentRegionMetadataIsExplicitlyUnproven`; `MotorolaStagePolicyTest.signedWrongOrConflictingChannelCannotBeOverriddenByRegionFlag` en `missingSignedChannelBlocksEvenOfficialRouteCorrectDeviceAndExplicitWarningConsent`. Een ontbrekend ondertekend reteu-kanaal blokkeert lokale publicatie met STAGING_CHANNEL_UNPROVEN. |
| 5 | Verkeerde bronbuild | `FirmwareValidatorTest.wrongSourceIncrementalIsRejected`, `wrongSourceFingerprintIsRejected`; `MotorolaStagePolicyTest.matchingProductSuffixDoesNotMakeWrongBuildOrIncrementalAcceptable`; `StockRestoreTest.wrongSourceNeverReachesWriteBoundary`. |
| 6 | Geen originele back-up | `ReadinessTest.missingOrUnprovenBackupBlocks`; `StockRestorePolicy` vereist actuele geauthenticeerde stockbytes, niet alleen een bestaand bestand. |
| 7 | Beschadigde back-up | `BackupValidatorTest.shellOutputOf83ExtraBytesIsRejected`, `truncatedImageIsRejected`, `gzipWithDamagedCrcIsRejected`, `gzipWithTrailingShellOutputIsRejected`, `patchedContentMismatchRejectsEvenWellFormedHeader`. |
| 8 | Te weinig ruimte | `ReadinessTest.insufficientStorageBlocks`, `ResumableTransferTest.insufficientSpaceAndHashMismatchNeverPublish`, `VerifiedStageTransferTest.corruptedSourceOrInsufficientSpaceCannotPublish`. |
| 9 | Geen root | `ReadinessTest.noRootBlocksEvenWithValidBackup`, `StockRestoreTest.rootSecurityAndFreshnessAreIndependentlyRequired`, monitoringrootgevallen en echte emulatorguardtests. |
| 10 | Actieve/ongekende merge | `ReadinessTest.activeOrUnknownSnapshotStatesAlwaysBlock`, `UpdateCoordinatorTest.mergeAndUnknownStatesCannotBeComplete`, `MotorolaStagePolicyTest.staleMergeEngineAutomaticUpdateAndUnknownSafetyFailClosed`. |
| 11 | Download mislukt | `ResumableTransferTest.interruptedNetworkRetainsOnlyDurablePrefixThenResumesExactEntity`, `changedEntityCannotBeAppendedToOldPrefix`, `processDeathUncheckpointedTailIsTrimmedBeforeResume`; echte app-private store-/workerhersteltests in `DownloadRuntimeTest`. HTTP-responses zijn geïnjecteerd, geen claim over een toekomstige Motorola-response. |
| 12 | Ongeldige ZIP | `FirmwareValidatorTest.nonZipInputIsRejectedWithoutException`, `emptyInputIsRejectedWithoutException`, `absentPayloadEntryIsRejected`, `absentPropertiesEntryIsRejected`, `absentAndroidMetadataIsRejected`. |
| 13 | Verificatiefout | `FirmwareValidatorTest.payloadHashMismatchNeverCountsAsVerified`, `metadataHashMismatchIsRejected`; `PlatformSafetyTest.unsignedPackageCannotPassActualDeviceCertificateVerification` voert de echte Android-verifier negatief uit. Positief fysiek signaturebewijs hoort uitsluitend bij de afzonderlijke historische 0.2.0-validatie. |
| 14 | Enginefout | `ObservationParsersTest.payloadFailureCannotBeHiddenByFollowingIdleStatus`; `UpdateFailureTest.currentCoordinatorKeepsEngineAndMergeFailureFatalAfterLaterIdleOrExplicitRetries` controleert de werkelijk gebruikte nieuwe coordinator en behoud van fatale fouten bij idle/retry/herstartmelding. |
| 15 | Magisk-doelslotinstallatie mislukt | De productieflow kan een mislukte externe Magisk-stap expliciet registreren en blokkeert herstartbegeleiding. Alleen een afzonderlijke verse controle van dezelfde bronboot met `UPDATED_NEED_REBOOT` kan handmatige retry vrijgeven. Dit test de begeleiding, geen echte Magisk-patching. |
| 16 | Root na boot verdwenen | `UpdateCoordinatorTest.rootHasToBeMeasuredAfterBoot`, `rootPermissionFailureCanBeRetriedOnlyByAnExplicitFreshTargetRead`. Niet gemeten, geweigerde toestemming en werkelijk bevestigde root worden onderscheiden; toestemmingweigering bewijst geen verwijderde Magisk-installatie. |
| 17 | App halverwege dood | `StockRestoreTest.processDeathAfterConsentIsDurableAndDoesNotWriteDuringRecovery`, `processDeathAfterReadbackRecoversByObservationWithoutAnotherWrite`, `UpdateRunStoreInstrumentedTest.recreatedControllerLoadsCriticalJournalWithoutDispatchingAnything`, downloadcommitcrashherstel. |
| 18 | Onverwachte herstart | `UpdateCoordinatorTest.sourceBuildAfterNewBootIsPossibleRollback`, `unobservedOtaCannotBecomeACompleteGuidedRun`; herstel-/consentbinding aan boot-ID en monotone klok. |
| 19 | Andere slot | `UpdateCoordinatorTest.actualTargetBuildAndSlotAreRequired`, `changedSourceFingerprintOrStockHashInvalidatesHandoff`; `StockRestoreTest.changedPartitionHashOrBootRequiresANewPlan`. |
| 20 | Externe release maar Motorola niets | `FirmwareDiscoveryTest.externalReleaseDoesNotOverrideUpToDateMotorolaResponse`, `externalReportAloneCannotSupplyTrustedDownload`. Een externe claim geeft geen vertrouwde download of installatie. |

## Aanvullende tests voor de uiteindelijke architectuur

| Onderdeel | Bewijs en grens |
|---|---|
| Volledige bestandsworkflow | `SandboxUpdateSessionTest.fullOfflineUpdateChangesTwoRegularFilesAndDurablyCompletes` en `SafeUiTest.fullFileBackedSimulationUsesEveryGuidedStepWithoutTouchingLiveJournal`: echte kleine bestanden en echt journaal, maar synthetische toestel-, OTA-, Magisk-, boot- en mergeobservaties. De UI-test vergelijkt het echte journaal vóór/na. |
| Partitieschrijverbeleid | `StockRestoreTest`: volledige voorafgaande gepatchte back-up, HMAC, monotone afloop, one-use-marker, source-/slot-/modebinding, partial-writefout en locked read-only recovery. De schrijfacties zijn op reguliere bestanden. De daadwerkelijke Android-blockdescriptor is niet op de telefoon beschreven. |
| Actuele Magisk-back-upkoppeling | `MagiskRuntimeConfigTest` toetst het officiële v31.1-formaat, ontbrekende/dubbele/ongeldige SHA1, pad- en groottebegrenzing, authentieke oude bytes bij gespoofde build en het behoud van onafhankelijke stockauthenticiteit. De live helper gebruikt dezelfde parser en comparator in beide verse observe-rondes; SDK37-bestandstoegang blijft fysiek ongetest. |
| Herstel zonder replay | Recovery onder writerlock; ongewijzigde bron vereist een nieuwe transactie en nieuwe toestemming. `explicitRecoveryOfUnchangedSourceRecapturesBackupInvalidatesOldTokenAndNeedsANewPlan`, `partialChangedSourceCannotBeReleasedForAnotherAttempt`, `recoveryCannotClaimSuccessWhileAnotherWriterHoldsTheLock`. |
| Actuele verwachtingbinding | `UpdateCoordinatorTest.lateUiCallbacksCannotRegressTargetBootOrCompletedRun`, `staleSourceObservationCannotAcknowledgeRebootOrLaunchWorkflowStep`; `UpdateRunStoreInstrumentedTest.oldConfirmationCannotClaimANewerPreparedTransaction`. |
| Werkelijke Android-persistentie | `UpdateRunStoreInstrumentedTest`, `MonitorRuntimeTest`, `DownloadRuntimeTest`, `VaultRuntimeTest`: AtomicFile, reload, corruptie, rollback en echte eigen bestanden op API35. |
| Firmwarekluis | `PayloadManifestTest`, `StockEvidenceTest`, `StockEvidenceStoragePolicyTest`, `VaultRuntimeTest`: bounds, onbekende/duplicate protobufvelden, verkeerde build/FP/key, verse werkelijke targetbinding, stabiele pakketbytes en cleanup. Het Android-runtimepad gebruikt aantoonbaar de duurzame kopiefallback wanneer hardlinks door toegangsbeleid worden geweigerd. |
| Historische echte payload | `PayloadManifestTest.realHistoricalSignedPackageContainsKnownStock17Target` en `FirmwareValidatorTest.observedHistoricalPackageIsCorrectlyDiagnosedWhenExplicitlyProvided` gebruiken uitsluitend de expliciet aangewezen bestaande host-ZIP. In CI zonder deze private fixture worden ze overgeslagen. |
| Downloads | `ResumableTransferTest`, `DownloadManifestCodecTest`, `DownloadRuntimeTest`: 206/Content-Range/ETag, restart bij200, hash/grootte, cancellation, pause, atomische commit, selectieconflict, versleutelde URL, AAD en crash na rename. Geen echte OTA-download gestart in deze offline fase. |
| Monitoring | `MonitoringTest`, `MonitorRunnerTest`, `MonitorRuntimeTest`: opt-in, root-/freshness-/engineguards, deduplicatie, URL-tokenrotatie, stille identieke resultaten, Android-notificatierechten, uitzetten tijdens werk, unieke WorkManager-planning en opslagfouten. Android bepaalt uitvoeringstijd en daadwerkelijke notificatieweergave. |
| Motorola copy-only staging | `MotorolaStagePolicyTest`, `VerifiedStageTransferTest`, `MotorolaStagingRuntimeTest`: geauthenticeerde bytes, expliciete dubbele bevestiging, OEM-namespacebewijs zonder aliasregel, wipe/rollback, bronwissel, no-clobber en unsupported-publicationfout. De positieve JNI-test gebruikt dezelfde `renameat2(RENAME_NOREPLACE)`-kern op emulatorcachebestanden; hij bewijst geen fysieke FUSE- of Motorola-herkenning. |
| Bestaand Motorola-pakket importeren | `MotorolaImportPolicyTest` en importerplatformtests toetsen begrensde lokale input, canonical containment, bestandidentiteit en veilige byteoverdracht. Roothelperruntime en herkenning van toekomstige OEM-opslag blijven echte-toestelvalidatiepunten. |
| Diagnoseprivacy | `PrivacyTest`, metadata-/downloadcodec- en encrypted-secret-tests. Rapportexport gebruikt een veldallowlist; geen ruwe XML, tokens, URL's, serienummers, IMEI of persoonlijke bestandsnamen. |
| Geen bootctl-initialisatiemutatie | `UpdateObservationsTest.bootControlToolInitializersAreNeverLaunchedEvenForGetterProbes` controleert de daadwerkelijke vroege weigering in de productie-ReadOnlyShell; geen rootproces wordt gestart. |

## Historisch fysieke bewijs apart houden

De vier opt-in `ReadOnlyDeviceValidationTest`-gevallen en drie toenmalige UI-gevallen zijn tijdens de **0.2.0-fase** op de XT2607-1 uitgevoerd. Zij bevatten echte uitlezende root-, snapshot-, bootmetadata-, enginecallback-, back-up-, Motorola-cache- en positieve platform-signaturemetingen. [DEVICE-VALIDATION](DEVICE-VALIDATION.md) en [het bewaarde 0.2.0-buildrapport](BUILD-REPORT-0.2.0.md) leggen dat vast.

De huidige 0.3.0-fase gebruikt **geen telefoon-ADB, geen echte partitieschrijfacties, geen OTA-installatie en geen telefoonherstart**. Bouwen, JVM-/emulatortests en bronreview zijn geen bewijs van een volledig uitgevoerde fysieke update. Alle hardwaretests vereisen een later afzonderlijk geautoriseerd testplan uit [MANUAL-TESTS](MANUAL-TESTS.md).

## Wat de groene suite niet kan bewijzen

- Een nieuwe Motorola-serveraanbieding, gefaseerde uitrol of de historische oorzaak van `ALREADY_UP_TO_DATE`.
- Veilige fysieke stock-I/O onder alle firmware-, stroom- en opslagfouten.
- De daadwerkelijke herkenning/installatie van een nieuw pakket uit de interne Motorola-map.
- Werkelijke Magisk-patching van de inactieve slot; het productiepad gebruikt de officiële app en handmatige melding.
- Een toekomstige OTA met root-/gegevensbehoud, Android18- of andere Magisk-compatibiliteit, functionele modulecompatibiliteit, Wallet of Play Integrity.

Deze grenzen worden in de interface en documentatie vermeld. Zij worden niet verhuld door het aantal tests of door een gebouwde APK.
