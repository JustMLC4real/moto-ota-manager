package nl.moto.ota.platform

import android.content.Context
import android.os.BatteryManager
import android.os.StatFs
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import nl.moto.ota.core.*
import java.io.ByteArrayInputStream
import java.security.MessageDigest
import java.util.zip.GZIPInputStream

class DeviceInspector(private val shell: ReadOnlyShell = ReadOnlyShell()) {
    suspend fun inspect(context: Context, rootRequested: Boolean): Inspection = withContext(Dispatchers.IO) {
        // Freshness starts at the earliest observation. Slow root/backup probes must never
        // make old slot or snapshot evidence look fresh merely because the scan finished now.
        val inspectionStartedAtMillis = System.currentTimeMillis()
        val properties = linkedMapOf<String, String>()
        val probes = linkedMapOf<String, ProbeResult>()
        for (name in ReadOnlyShell.PROPERTY_NAMES) {
            val result = shell.property(name)
            if (result.successful) properties[name] = result.stdout.trim()
        }
        val battery = context.getSystemService(BatteryManager::class.java)
            ?.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY) ?: -1
        properties["batteryPercent"] = battery.toString()
        properties["freeBytes"] = runCatching { StatFs(context.filesDir.absolutePath).availableBytes.toString() }.getOrDefault("-1")
        probes[ReadOnlyProbe.BOOT_ID.name] = shell.run(ReadOnlyProbe.BOOT_ID)
        var backups = emptyList<BackupCandidate>()
        var counters = emptyMap<String, String>()
        var metadata = MotorolaMetadataObservation()
        if (rootRequested) {
            val root = shell.run(ReadOnlyProbe.ROOT_ID)
            probes[ReadOnlyProbe.ROOT_ID.name] = root
            val rootGranted = root.successful && Regex("(?:^|\\s)uid=0(?:\\(|\\s|$)").containsMatchIn(root.stdout)
            if (rootGranted) {
                // Some property contexts (observed: ro.virtual_ab.enabled) are
                // unreadable to an ordinary app even when getprop exits 0.
                // Retry only missing allowlisted values after root was granted.
                for (name in ReadOnlyShell.PROPERTY_NAMES.filter { properties[it].isNullOrBlank() }) {
                    val privileged = shell.property(name, asRoot = true)
                    probes["PRIV_PROP_" + name.uppercase().replace('.', '_')] = privileged
                    if (privileged.successful && privileged.stdout.isNotBlank()) properties[name] = privileged.stdout.trim()
                }
                for (probe in ReadOnlyProbe.entries.filter { it.requiresRoot && it != ReadOnlyProbe.ROOT_ID }) {
                    probes[probe.name] = shell.run(probe)
                }
                if (ObservationParsers.engine(probes[ReadOnlyProbe.ENGINE_FOLLOW.name]) == "UNKNOWN" && properties["ro.build.version.sdk"]?.toIntOrNull() in DeviceCapabilities.supportedRestoreSdks) {
                    probes["ENGINE_CALLBACK"] = shell.engineCallback(context.applicationInfo.sourceDir)
                }
                if (probes[ReadOnlyProbe.MAGISK_CODE.name]?.stdout?.trim() == "31100") probes["MAGISK_RUNTIME"] = shell.magiskRuntime(context.applicationInfo.sourceDir)
                val preferences = probes[ReadOnlyProbe.OTA_PREFS.name]?.takeIf { it.successful }
                    ?.let { runCatching { ObservationParsers.preferences(it.stdout) }.getOrNull() }.orEmpty()
                counters = preferences.filterKeys { it in COUNTER_KEYS }
                metadata = MotorolaMetadataParser.parse(
                    probes[ReadOnlyProbe.DOWNLOAD_PREFS.name]?.takeIf { it.successful }?.stdout,
                    probes[ReadOnlyProbe.OTA_PREFS.name]?.takeIf { it.successful }?.stdout)
            }
        }
        val kernel = MetadataObservations.kernelConfig(probes[ReadOnlyProbe.BOOT_CONFIG.name])
        val bootControl = MetadataObservations.hex(probes[ReadOnlyProbe.MISC_BOOT_CONTROL.name], 32)?.let(ReadOnlyMetadata::parseBootControl)
        val vab = MetadataObservations.hex(probes[ReadOnlyProbe.MISC_VIRTUAL_AB.name], 64)?.let(ReadOnlyMetadata::parseVirtualAb)
        val snapshotFile = MetadataObservations.snapshotFile(probes[ReadOnlyProbe.SNAPSHOT_STATE_FILE.name])
        val sourceSlots = listOf(properties["ro.boot.slot_suffix"], kernel["slot_suffix"]).filterNotNull().filter { it in listOf("_a", "_b") }.distinct()
        val snapshot = MetadataObservations.combineSnapshot(
            ObservationParsers.snapshot(probes[ReadOnlyProbe.SNAPSHOT_DUMP.name], probes[ReadOnlyProbe.SNAPSHOT_HAL.name]),
            snapshotFile, vab, sourceSlots.singleOrNull().orEmpty(), probes[ReadOnlyProbe.SNAPSHOT_DIRECTORY.name],
            probes.containsKey(ReadOnlyProbe.SNAPSHOT_STATE_FILE.name),
            probes[ReadOnlyProbe.SNAPSHOT_DUMP.name]?.successful == true || probes[ReadOnlyProbe.SNAPSHOT_HAL.name]?.successful == true)
        val commandEngine = ObservationParsers.engine(probes[ReadOnlyProbe.ENGINE_FOLLOW.name])
        val engine = if (commandEngine != "UNKNOWN") commandEngine else MetadataObservations.engineCallback(probes["ENGINE_CALLBACK"])
        val result = Inspection(properties, probes, backups, snapshot, engine, inspectionStartedAtMillis, rootRequested,
            counters, metadata.downloadUrl, metadata, kernel, bootControl, vab, snapshotFile)
        if (result.toDeviceState().rootGranted) {
            backups = inventoryBackups(probes[ReadOnlyProbe.BACKUPS.name], result.toDeviceState(),
                probes[ReadOnlyProbe.PARTITIONS.name]?.stdout.orEmpty(), DeviceTrustAnchors.spkiSha256(), context)
        }
        val runtimeSha1 = probes["MAGISK_RUNTIME"]?.takeIf { it.successful }?.let {
            runCatching { org.json.JSONObject(it.stdout).takeIf { j -> j.getInt("schema")==1 }?.getString("stockSha1") }.getOrNull()
        }
        val associated = backups.firstOrNull { it.sha1 == runtimeSha1 && it.validation?.structurallyValid == true }
        val association = associated?.let { BootBackupAssociation(it.validation!!.partitionType, it.uncompressedBytes ?: 0,
            it.sha256.orEmpty(), it.sha1.orEmpty(), runtimeSha1.orEmpty(), it.validation.structurallyValid, it.provenanceVerified,
            result.toDeviceState().bootId, inspectionStartedAtMillis) }
        result.copy(backupInventory = backups, backupAssociation = association)
    }

    private fun inventoryBackups(probe: ProbeResult?, device: DeviceState, partitions: String, trustAnchors: Set<String>, context: Context): List<BackupCandidate> {
        if (probe?.successful != true) return emptyList()
        val references = (listOfNotNull(VerifiedStockCatalog.referenceFor(device, trustAnchors)) +
            FirmwareVault(context).referencesFor(device, trustAnchors)).distinctBy { Triple(it.partition,it.sha256,it.bytes) }
        val sizes = partitions.lineSequence().mapNotNull { line ->
            val parts=line.trim().split('=',limit=2); if(parts.size==2)parts[1].toLongOrNull()?.let {parts[0] to it}else null
        }.toMap()
        return probe.stdout.lineSequence().filter(String::isNotBlank).take(20).mapNotNull { line ->
            val parts=line.trim().split('|'); val directory=parts.firstOrNull()?:return@mapNotNull null
            if(!directory.matches(Regex("magisk_backup_[a-fA-F0-9]{40}")))return@mapNotNull null
            val compressedSize=parts.getOrNull(1)?.trim()?.toLongOrNull()
            if(compressedSize==null || compressedSize !in 1..BootImageStreams.MAX_PACKED_BYTES) return@mapNotNull BackupCandidate(
                directory,compressedSize,null,null,null,false,listOf("BACKUP_SIZE_INVALID"),presentationMessages=listOf(DiagnosticMessage("backup.gzip_size")))
            try {
                val measured=shell.withBackup(directory,compressedSize) { BootImageStreams.measure(it,true) }
                val type=measured.partitionType
                val expected=sizes[type+device.slot]?:-1L
                val reference=references.firstOrNull { it.partition==type && it.bytes==measured.bytes && it.sha256==measured.sha256 }
                val validation=if(reference!=null) shell.withBackup(directory,compressedSize) {
                    BackupValidator.inspect(it,true,expected,device,reference,type)
                } else BackupValidation(structurallyValid=type in setOf("boot","init_boot") && measured.bytes==expected,
                    sha256=measured.sha256,sha1=measured.sha1,bytes=measured.bytes,partitionType=type,
                    issues=listOf(CheckResult("STOCK_PROVENANCE_UNKNOWN","Authenticated current partition bytes are not yet available.")))
                val suffixMatches=directory.removePrefix("magisk_backup_").equals(measured.sha1,true)
                val unchanged=validation.sha256==measured.sha256 && validation.sha1==measured.sha1 && validation.bytes==measured.bytes
                val errors=buildList {
                    if(!suffixMatches)add("BACKUP_SHA1_MISMATCH")
                    if(!unchanged)add("BACKUP_CHANGED")
                    addAll(validation.issues.map {it.code})
                }
                BackupCandidate(directory,compressedSize,measured.bytes,measured.sha256,measured.sha1,type!="unknown",errors,
                    validation.usableFor(device) && suffixMatches && unchanged,validation,
                    buildList {
                        if(!suffixMatches)add(DiagnosticMessage("backup.sha1"))
                        if(!unchanged)add(DiagnosticMessage("backup.unstable"))
                        addAll(validation.issues.map {DiagnosticMessage(it.presentationKey?:"check.${it.code}",it.presentationArgs)})
                    })
            } catch(_:Exception) {
                BackupCandidate(directory,compressedSize,null,null,null,false,listOf("BACKUP_STREAM_INVALID"),presentationMessages=listOf(DiagnosticMessage("backup.corrupt")))
            }
        }.toList()
    }

    internal fun inspectBackup(directory: String, gzip: ByteArray, expectedBytes: Long = -1,
                               device: DeviceState = DeviceState(), reference: StockReference? = null): BackupCandidate {
        val sha256 = MessageDigest.getInstance("SHA-256")
        val sha1 = MessageDigest.getInstance("SHA-1")
        var count = 0L
        val header = ByteArray(8)
        var headerCount = 0
        return try {
            GZIPInputStream(ByteArrayInputStream(gzip)).use { input ->
                val buffer = ByteArray(32_768)
                while (true) {
                    val size = input.read(buffer)
                    if (size < 0) break
                    count += size
                    require(count <= 64L * 1_048_576) { "Decompressed image exceeds limit" }
                    if (headerCount < header.size) {
                        val copied = minOf(size, header.size - headerCount)
                        buffer.copyInto(header, headerCount, 0, copied)
                        headerCount += copied
                    }
                    sha256.update(buffer, 0, size)
                    sha1.update(buffer, 0, size)
                }
            }
            val sha1Text = sha1.digest().hex()
            val validHeader = headerCount == 8 && header.toString(Charsets.US_ASCII) == "ANDROID!"
            val validation = if (expectedBytes > 0) BackupValidator.inspect(ByteArrayInputStream(gzip), true, expectedBytes, device, reference) else null
            val errors = buildList {
                if (!validHeader) add("Geen Android boot-imageheader; partitietype onbekend")
                if (sha1Text != directory.removePrefix("magisk_backup_").lowercase()) add("Magisk SHA1-directory komt niet overeen met image")
                if (validation?.usableAsStock != true) {
                    addAll(validation?.issues.orEmpty().map { it.message })
                    add("Build en init_boot-herkomst niet bewezen; niet geschikt voor automatisch herstel")
                }
            }
            BackupCandidate(directory, gzip.size.toLong(), count, sha256.digest().hex(), sha1Text, validHeader, errors,
                provenanceVerified = validation?.usableAsStock == true && errors.isEmpty(), validation = validation,
                presentationMessages = buildList {
                    if (!validHeader) add(DiagnosticMessage("backup.header"))
                    if (sha1Text != directory.removePrefix("magisk_backup_").lowercase()) add(DiagnosticMessage("backup.sha1"))
                    if (validation?.usableAsStock != true) {
                        addAll(validation?.issues.orEmpty().map { DiagnosticMessage(it.presentationKey ?: "check.${it.code}", it.presentationArgs) })
                        add(DiagnosticMessage("backup.provenance"))
                    }
                })
        } catch (_: Exception) {
            BackupCandidate(directory, gzip.size.toLong(), count, null, null, false,
                listOf("Beschadigde gzip, onvolledige image of decompressielimiet overschreden"), presentationMessages = listOf(DiagnosticMessage("backup.corrupt")))
        }
    }

    private fun ByteArray.hex(): String = joinToString("") { "%02x".format(it.toInt() and 0xff) }

    companion object {
        private val COUNTER_KEYS = setOf("ota.service.update.maxUpgradeAttemptCount", "ota.service.update.failure_count",
            "ota.service.update.updateFailCount")
    }
}
