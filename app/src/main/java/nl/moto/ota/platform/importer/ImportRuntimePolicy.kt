package nl.moto.ota.platform.importer

/** Pure guards: a regular app, emulator SDK or unreviewed device never enters the root importer. */
object ImportRuntimePolicy {
    fun allowedFilesRoot(canonical:String):Boolean=canonical in setOf("/data/user/0/nl.moto.ota/files","/data/data/nl.moto.ota/files")
    fun allowedTransactionId(id:String):Boolean=id.matches(Regex("[a-f0-9-]{36}"))
    fun accepts(uid:Int,sdk:Int,manufacturer:String,technicalModels:List<String>,device:String,channels:List<String>,androidVersion:String):Boolean =
        uid==0 && sdk==37 && manufacturer.equals("motorola",true) && technicalModels.distinct()==listOf("XT2607-1") &&
            device=="merc" && channels.filter(String::isNotBlank).distinct()==listOf("reteu") && androidVersion=="17"
}
