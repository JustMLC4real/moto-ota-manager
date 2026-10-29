package nl.moto.ota.platform.update

import nl.moto.ota.core.*
import org.json.JSONArray
import org.json.JSONObject

/** Narrow private root-result schema; never an importable observation or authority file. */
internal object AutomationDeviceCodec {
    fun encode(d:DeviceState)=JSONObject().put("schema",1).put("manufacturer",d.manufacturer).put("model",d.model)
        .put("device",d.device).put("channel",d.channel).put("android",d.androidVersion).put("sdk",d.sdkInt)
        .put("build",d.build).put("fingerprint",d.fingerprint).put("vendor",d.vendorFingerprint)
        .put("patch",d.securityPatch).put("slot",d.slot).put("bootId",d.bootId).put("captured",d.capturedAtMillis)
        .put("virtualAb",d.virtualAb).put("snapshot",d.snapshot.name).put("engine",d.engine.name)
        .put("battery",d.batteryPercent).put("freeBytes",d.freeBytes).put("modules",JSONArray(d.modules)).put("partitions",JSONArray(d.partitions))
    fun decode(j:JSONObject):DeviceState {
        require(j.getInt("schema")==1)
        fun strings(key:String)=j.getJSONArray(key).let {a->require(a.length()<=256);(0 until a.length()).map {a.getString(it)}}
        return DeviceState(manufacturer=j.getString("manufacturer"),model=j.getString("model"),device=j.getString("device"),channel=j.getString("channel"),
            androidVersion=j.getString("android"),sdkInt=j.getInt("sdk"),build=j.getString("build"),fingerprint=j.getString("fingerprint"),
            vendorFingerprint=j.getString("vendor"),securityPatch=j.getString("patch"),slot=j.getString("slot"),bootId=j.getString("bootId"),
            capturedAtMillis=j.getLong("captured"),virtualAb=j.getBoolean("virtualAb"),snapshot=SnapshotState.valueOf(j.getString("snapshot")),engine=EngineState.valueOf(j.getString("engine")),
            batteryPercent=j.getInt("battery"),freeBytes=j.getLong("freeBytes"),modules=strings("modules"),partitions=strings("partitions"),
            rootChecked=true,rootGranted=true,magiskCode=31100,magiskVersion="31.1",moduleInventoryKnown=true,bootSuccessful=true,
            bootloaderUnlocked=true,verifiedBootState="orange",securityEvidenceSource="KERNEL_BOOTCONFIG",abUpdate=true,partitionInventoryKnown=true)
    }
}
