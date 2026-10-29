package nl.moto.ota.core.update

import nl.moto.ota.core.BackupValidation

/**
 * Data-only parser for official Magisk 31.1's boot_patch.sh config. Never shell input.
 * Source commit: 0972f67d97f9b6302b67c5171283330c74114b4a.
 * restore_imgs selects /data/magisk_backup_<SHA1> from this runtime configuration.
 */
object MagiskRuntimeConfig {
    const val MAX_BYTES=4096
    private val sha1=Regex("[a-f0-9]{40}")
    private val flags=setOf("KEEPVERITY","KEEPFORCEENCRYPT","RECOVERYMODE","VENDORBOOT")

    /** Only the two pinned Magisk-init roots; never accept an arbitrary returned path. */
    fun configPath(pathOutput:String):String? {
        if(pathOutput.length>128)return null
        val root=when {
            pathOutput.endsWith("\r\n")->pathOutput.dropLast(2)
            pathOutput.endsWith("\n")->pathOutput.dropLast(1)
            else->pathOutput
        }
        return if(root in setOf("/debug_ramdisk","/sbin"))"$root/.magisk/config"else null
    }

    /** Missing, duplicated, unknown, non-ASCII or malformed fields fail closed. */
    fun stockSha1(bytes:ByteArray):String? {
        if(bytes.size !in 1..MAX_BYTES || bytes.any { val v=it.toInt() and 255;v !in 0x20..0x7e && v!=10 && v!=13 })return null
        val seen=mutableSetOf<String>();var result:String?=null
        for(raw in bytes.toString(Charsets.US_ASCII).split('\n')){
            val line=raw.removeSuffix("\r")
            if(line.isEmpty())continue
            if('\r' in line)return null
            val separator=line.indexOf('=')
            if(separator<=0)return null
            val key=line.substring(0,separator);val value=line.substring(separator+1)
            if(!seen.add(key))return null
            when {
                key=="SHA1"->{if(!sha1.matches(value))return null;result=value}
                key in flags->if(value !in setOf("true","false"))return null
                key=="PREINITDEVICE"->if(!value.matches(Regex("[A-Za-z0-9_.-]{1,128}")))return null
                else->return null
            }
        }
        return result
    }

    fun matchesStock(runtimeSha1:String?,stock:BackupValidation):Boolean=
        stock.usableAsStock && runtimeSha1!=null && sha1.matches(runtimeSha1) && sha1.matches(stock.sha1) && runtimeSha1==stock.sha1
}
