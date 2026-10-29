package nl.moto.ota.core.update

import nl.moto.ota.core.*
import org.junit.Assert.*
import org.junit.Test

class MagiskRuntimeConfigTest {
    private val current="da444102f5a0e669ca3db7ba1de6a6c839b65f41"
    private fun config(hash:String=current)="KEEPVERITY=true\nKEEPFORCEENCRYPT=true\nRECOVERYMODE=false\nVENDORBOOT=false\nPREINITDEVICE=metadata\nSHA1=$hash\n"
    @Test fun officialConfigHasOneExactStockAssociation(){assertEquals(current,MagiskRuntimeConfig.stockSha1(config().toByteArray()))}
    @Test fun crlfAndMissingFinalNewlineAreAccepted(){assertEquals(current,MagiskRuntimeConfig.stockSha1(config().replace("\n","\r\n").toByteArray()));assertEquals(current,MagiskRuntimeConfig.stockSha1(config().trimEnd().toByteArray()))}
    @Test fun missingDuplicateAndMalformedHashesAreRejected(){
        val rejected=listOf("",config().substringBefore("SHA1="),config()+"SHA1=$current\n",config()+"SHA1=${"b".repeat(40)}\n",
            config(current.dropLast(1)),config(current+"0"),config(current.uppercase()),config("'$current'"),config("$current "),config("\$(cat /data/secret)"))
        rejected.forEach { assertNull(MagiskRuntimeConfig.stockSha1(it.toByteArray())) }
    }
    @Test fun unsupportedOrAmbiguousConfigSyntaxIsRejected(){
        listOf(config()+"RECOVERYMODE=false\n",config()+"UNKNOWN=value\n",config().replace("SHA1=","SHA1 ="),config().replace("SHA1=","export SHA1="),
            config().replace("KEEPVERITY=true","KEEPVERITY=yes"),config().replace("PREINITDEVICE=metadata","PREINITDEVICE=/dev/block/metadata"),
            config().replace("SHA1=","SHA1=\u0000"),config().replace("SHA1=","SHA1=\r"),config().replace("SHA1=","SHA1=é"))
            .forEach { assertNull(MagiskRuntimeConfig.stockSha1(it.toByteArray())) }
    }
    @Test fun readsStayBounded(){assertNull(MagiskRuntimeConfig.stockSha1((config()+"\n".repeat(MagiskRuntimeConfig.MAX_BYTES)).toByteArray()))}
    @Test fun onlyKnownRuntimeRootsAreAccepted(){
        assertEquals("/debug_ramdisk/.magisk/config",MagiskRuntimeConfig.configPath("/debug_ramdisk\n"))
        assertEquals("/sbin/.magisk/config",MagiskRuntimeConfig.configPath("/sbin\r\n"))
        listOf("/data/adb","/dev/random","/debug_ramdisk/"," /debug_ramdisk","/debug_ramdisk\nextra","/debug_ramdisk/../data","/sbin\n\n","a".repeat(129))
            .forEach { assertNull(MagiskRuntimeConfig.configPath(it)) }
    }
    @Test fun associationNeverReplacesStockAuthenticity(){
        val stock=trustedBackup();val matching=config(stock.sha1).toByteArray()
        assertTrue(MagiskRuntimeConfig.matchesStock(MagiskRuntimeConfig.stockSha1(matching),stock))
        assertFalse(MagiskRuntimeConfig.matchesStock(MagiskRuntimeConfig.stockSha1(matching),stock.copy(provenanceVerified=false)))
        assertFalse(MagiskRuntimeConfig.matchesStock(MagiskRuntimeConfig.stockSha1(config().toByteArray()),stock))
    }
    @Test fun authenticatedOldImageWithSpoofedBuildStillFailsRuntimeAssociation(){
        val actualStock=trustedBackup();val spoofed=safetyDevice().copy(build="OLD-SPOOFED-BUILD",fingerprint="motorola/merc/merc:17/OLD-SPOOFED-BUILD/old:user/release-keys")
        val oldImage=initBootFixture().apply { this[4300]=(this[4300].toInt() xor 7).toByte() }
        val old=BackupValidator.inspect(oldImage.inputStream(),false,oldImage.size.toLong(),spoofed,stockReference(oldImage,spoofed))
        assertTrue(old.usableAsStock);assertNotEquals(actualStock.sha1,old.sha1)
        assertFalse(MagiskRuntimeConfig.matchesStock(MagiskRuntimeConfig.stockSha1(config(actualStock.sha1).toByteArray()),old))
    }
}
