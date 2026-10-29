# Java-compiler metadata on Error Prone's unused annotation definitions. The
# Android tests do not load a Java compiler or invoke this enum. Runtime Android
# and application classes are still checked normally by R8.
-dontwarn javax.lang.model.element.Modifier

# Invoked explicitly by the guarded emulator file-proof tool, outside JUnit.
-keep class nl.moto.ota.platform.update.OtaPayloadFileProbe {
    public static void main(java.lang.String[]);
}
