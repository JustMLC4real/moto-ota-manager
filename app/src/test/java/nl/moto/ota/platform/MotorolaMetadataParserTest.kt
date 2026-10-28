package nl.moto.ota.platform

import nl.moto.ota.core.DiscoveryEvidence
import nl.moto.ota.core.DiscoveryStatus
import nl.moto.ota.core.FirmwareDiscovery
import nl.moto.ota.core.LocalContentKind
import org.junit.Assert.*
import org.junit.Test

class MotorolaMetadataParserTest {
    private val metadataKey = "com.motorola.ccc.otafota.metadata_file"
    private val url = "https://dlmgredg-vz.gtm.svcmot.com/synthetic.zip?temporary=fixture"
    private val targetFingerprint = "motorola/synthetic/synthetic:17/TARGET_17/123:user/release-keys"

    private fun fixture(name: String) = requireNotNull(javaClass.getResourceAsStream("/motorola/$name")).bufferedReader().use { it.readText() }
    private fun jsonString(text: String) = "\"${text.replace("\\", "\\\\").replace("\"", "\\\"")}\""
    private fun resource() = """{"proceed":true,"wifiUrl":"$url"}"""
    private fun request(fileName: String = "synthetic-update.zip", size: Long = 3_417_373_748,
                        offset: Long = 0, resource: String = resource()) =
        """{"downloadRequest":{"contentResource":$resource,"fileName":"$fileName","size":$size,"startingOffset":$offset}}"""
    private fun parse(request: String, metadata: String? = null) = MotorolaMetadataParser.fromPreferences(
        mapOf("downloadRequest" to request), metadata?.let { mapOf(metadataKey to it) }.orEmpty())
    private fun xml(key: String, text: String) = "<map><string name=\"$key\">${text.replace("&", "&amp;").replace("<", "&lt;")}</string></map>"

    @Test fun sanitizedObservedSchemaIsHistoricalMetadataForInstalledTarget() {
        // These fixtures preserve the actual key/type structure, byte range and nested
        // contentResource serialization. Device identities, paths and references are replaced.
        val result = parse(fixture("observed-download-request.json"), fixture("observed-updater-metadata.json"))
        assertTrue(result.observed)
        assertEquals(LocalContentKind.METADATA_ONLY, result.contentKind)
        assertEquals("TARGET_17", result.targetBuild)
        assertTrue(MetadataDiagnostic.METADATA_RESOURCE in result.diagnostics)
        assertNull(result.downloadUrl)
        val assessment = FirmwareDiscovery.assess(DiscoveryEvidence(localTargetBuild = result.targetBuild.orEmpty(),
            localDownloadUrl = result.downloadUrl, localContentKind = result.contentKind, localMetadataObserved = result.observed), "TARGET_17")
        assertEquals(DiscoveryStatus.ALREADY_INSTALLED, assessment.status)
        assertFalse(assessment.canDownloadCandidate)
        assertFalse(assessment.authoritativeUpdateAvailable)
    }

    @Test fun officialMetadataUrlRemainsMetadataOnlyWithNestedStringResource() {
        val result = parse(request("REDACTED/payload_metadata.bin", 1_853_495, 4_744, jsonString(resource())))
        assertEquals(LocalContentKind.METADATA_ONLY, result.contentKind)
        assertEquals(url, result.downloadUrl)
        val assessment = FirmwareDiscovery.assess(DiscoveryEvidence(localDownloadUrl = result.downloadUrl,
            localContentKind = result.contentKind, localMetadataObserved = true), "CURRENT")
        assertEquals(DiscoveryStatus.METADATA_ONLY, assessment.status)
        assertFalse(assessment.canDownloadCandidate)
    }

    @Test fun exactObservedPayloadMetadataNameRequiresPositiveOffsetAndNeverEnablesZipDownload() {
        val metadata = parse(request("REDACTED/PAYLOAD_METADATA.BIN", 1_853_495, 4_744))
        assertEquals(LocalContentKind.METADATA_ONLY, metadata.contentKind)
        listOf(request("REDACTED/payload_metadata.bin", 1_853_495, 0),
            request("REDACTED/other_payload_metadata.bin", 1_853_495, 4_744),
            request("REDACTED/metadata-fixture.bin", 1_853_495, 0)).forEach {
            val unknown = parse(it)
            assertEquals(LocalContentKind.UNKNOWN, unknown.contentKind)
            assertFalse(FirmwareDiscovery.assess(DiscoveryEvidence(localDownloadUrl = unknown.downloadUrl,
                localContentKind = unknown.contentKind, localMetadataObserved = true), "CURRENT").canDownloadCandidate)
        }
    }

    @Test fun typedZipCandidateStillDoesNotProveCurrentServerOffer() {
        val result = parse(request())
        assertEquals(LocalContentKind.OTA_ZIP, result.contentKind)
        val assessment = FirmwareDiscovery.assess(DiscoveryEvidence(localDownloadUrl = result.downloadUrl,
            localContentKind = result.contentKind, localMetadataObserved = true), "CURRENT")
        assertTrue(assessment.canDownloadCandidate)
        assertFalse(assessment.authoritativeUpdateAvailable)
    }

    @Test fun targetBuildComesOnlyFromExplicitTargetFingerprint() {
        val actual = parse(request(), """{"targetFingerPrint":"$targetFingerprint"}""")
        assertEquals("TARGET_17", actual.targetBuild)
        val guessed = parse(request("TARGET_17.zip"), """{"fingerprint":"$targetFingerprint","displayVersion":"TARGET_17","version":"TARGET_17"}""")
        assertNull(guessed.targetBuild)
        assertTrue(MetadataDiagnostic.TARGET_BUILD_MISSING in guessed.diagnostics)
    }

    @Test fun malformedExplicitFingerprintDoesNotBecomeBuildIdentity() {
        val result = parse(request(), """{"targetFingerPrint":"TARGET_17"}""")
        assertNull(result.targetBuild)
        assertTrue(MetadataDiagnostic.INVALID_TARGET_FINGERPRINT in result.diagnostics)
    }

    @Test fun xmlEscapingAndBothJsonResourceRepresentationsAreSupported() {
        val json = request(resource = jsonString(resource()))
        val result = MotorolaMetadataParser.parse(xml("downloadRequest", json),
            xml(metadataKey, """{"targetFingerPrint":"$targetFingerprint"}"""))
        assertEquals(LocalContentKind.OTA_ZIP, result.contentKind)
        assertEquals(url, result.downloadUrl)
        assertEquals("TARGET_17", result.targetBuild)
        assertEquals(LocalContentKind.OTA_ZIP, parse(request()).contentKind)
    }

    @Test fun unsupportedEnvelopeAndNewSchemaFieldsFailClosed() {
        listOf("""{"newDownloadRequest":{"wifiUrl":"$url"}}""",
            request().replace("\"startingOffset\":0", "\"startingOffset\":0,\"futureKind\":\"ota\""),
            request(resource = """{"wifiUrl":"$url","futureResourceType":"ota"}""")).forEach { json ->
            val result = parse(json)
            assertEquals(LocalContentKind.UNKNOWN, result.contentKind)
            assertNull(result.downloadUrl)
            assertTrue(MetadataDiagnostic.UNSUPPORTED_SCHEMA in result.diagnostics)
        }
    }

    @Test fun unknownKindOrRangedZipDoesNotBecomeFullOtaCandidate() {
        listOf(request("payload.bin"), request(offset = 4_744)).forEach {
            val result = parse(it)
            assertEquals(LocalContentKind.UNKNOWN, result.contentKind)
            assertTrue(MetadataDiagnostic.UNKNOWN_RESOURCE_KIND in result.diagnostics)
        }
    }

    @Test fun resourceApprovalAndNumericBoundsFailClosed() {
        val denied = parse(request(resource = """{"proceed":false,"wifiUrl":"$url"}"""))
        assertEquals(LocalContentKind.UNKNOWN, denied.contentKind)
        assertNull(denied.downloadUrl)
        assertTrue(MetadataDiagnostic.RESOURCE_NOT_APPROVED in denied.diagnostics)
        listOf(request(size = 0), request(offset = -1), request(size = 12, offset = 13),
            request().replace("3417373748", "\"3417373748\""), request().replace("3417373748", "999999999999999999999999")).forEach {
            val result = parse(it)
            assertEquals(LocalContentKind.UNKNOWN, result.contentKind)
            assertNull(result.downloadUrl)
            assertTrue(MetadataDiagnostic.INVALID_RESOURCE_RANGE in result.diagnostics)
        }
    }

    @Test fun jsonDuplicatesMalformedSyntaxAndReaderLimitsFailClosed() {
        listOf(request().dropLast(1), request() + " trailing", request().replace("\"startingOffset\":0", "\"startingOffset\":0,\"startingOffset\":0"),
            request(fileName = "a".repeat(16_385)), " ".repeat(131_073),
            """{"downloadRequest":${"[".repeat(14)}0${"]".repeat(14)}}""").forEach { json ->
            val result = parse(json)
            assertEquals(LocalContentKind.UNKNOWN, result.contentKind)
            assertNull(result.downloadUrl)
            assertTrue(MetadataDiagnostic.INVALID_JSON in result.diagnostics)
        }
    }

    @Test fun xmlEntitiesDuplicatesAndOversizedPreferencesFailClosed() {
        listOf("<!DOCTYPE map [<!ENTITY x SYSTEM 'file:///etc/passwd'>]><map><string name='downloadRequest'>&x;</string></map>",
            "<map><string name='downloadRequest'>a</string><string name='downloadRequest'>b</string></map>",
            " ".repeat(1_048_577)).forEach {
            val result = MotorolaMetadataParser.parse(it, null)
            assertTrue(result.observed)
            assertEquals(LocalContentKind.UNKNOWN, result.contentKind)
            assertNull(result.downloadUrl)
            assertEquals(listOf(MetadataDiagnostic.INVALID_PREFERENCES), result.diagnostics)
        }
    }

    @Test fun signedReferenceNeverAppearsInDiagnosticString() {
        val result = parse(request())
        assertFalse(result.toString().contains(url))
        assertFalse(result.toString().contains("temporary"))
        assertFalse(result.toString().contains("synthetic.zip"))
    }

    @Test fun absentInputsAreDifferentFromUnrecognizedLocalEvidence() {
        assertFalse(MotorolaMetadataParser.parse(null, null).observed)
        assertFalse(MotorolaMetadataParser.fromPreferences(emptyMap(), emptyMap()).observed)
        assertTrue(parse("{}").observed)
    }
}
