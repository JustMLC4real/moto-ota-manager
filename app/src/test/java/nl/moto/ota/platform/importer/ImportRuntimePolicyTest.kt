package nl.moto.ota.platform.importer

import org.junit.Assert.*
import org.junit.Test

class ImportRuntimePolicyTest {
    private fun accepts(uid:Int=0,sdk:Int=37,models:List<String> = listOf("XT2607-1"),channels:List<String> = listOf("reteu"),android:String="17")=
        ImportRuntimePolicy.accepts(uid,sdk,"motorola",models,"merc",channels,android)
    @Test fun exactRootRuntimeIsRequired(){assertTrue(accepts());assertFalse(accepts(uid=10001));assertFalse(accepts(sdk=35));assertFalse(accepts(android="18"))}
    @Test fun conflictingPropertiesCannotBeHiddenByOneMatchingValue(){assertFalse(accepts(models=listOf("XT2607-1","XT9999-1")));assertFalse(accepts(channels=listOf("reteu","retus")))}
    @Test fun duplicatesAreConsistentButMissingIdentityIsNot(){assertTrue(accepts(models=listOf("XT2607-1","XT2607-1"),channels=listOf("","reteu")));assertFalse(accepts(models=emptyList()));assertFalse(accepts(channels=emptyList()))}
    @Test fun onlyFixedOwnUserZeroDataRootsAreAccepted(){
        assertTrue(ImportRuntimePolicy.allowedFilesRoot("/data/user/0/nl.moto.ota/files"));assertTrue(ImportRuntimePolicy.allowedFilesRoot("/data/data/nl.moto.ota/files"))
        assertFalse(ImportRuntimePolicy.allowedFilesRoot("/data/user/1/nl.moto.ota/files"));assertFalse(ImportRuntimePolicy.allowedFilesRoot("/data/user/0/other.app/files"))
        assertFalse(ImportRuntimePolicy.allowedFilesRoot("/sdcard/Android/data/nl.moto.ota/files"))
    }
    @Test fun transactionCannotInjectAPathOrCommand(){
        assertTrue(ImportRuntimePolicy.allowedTransactionId("12345678-1234-1234-1234-123456789abc"))
        listOf("../selected-ota.zip",";reboot","/dev/block/by-name/init_boot_a","a".repeat(37)).forEach { assertFalse(ImportRuntimePolicy.allowedTransactionId(it)) }
    }
}
