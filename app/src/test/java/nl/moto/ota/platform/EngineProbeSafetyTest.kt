package nl.moto.ota.platform

import nl.moto.ota.rootprobe.EngineProbe
import org.junit.Assert.*
import org.junit.Test

class EngineProbeSafetyTest {
    @Test fun onlyExplicitRootOnReviewedAndroid17CanSubscribe() {
        assertTrue(EngineProbe.supportsRuntime(0, 37))
        assertFalse(EngineProbe.supportsRuntime(2000, 37))
        assertFalse(EngineProbe.supportsRuntime(10000, 37))
        (33..37).forEach { assertTrue(EngineProbe.supportsRuntime(0, it)) }
        assertFalse(EngineProbe.supportsRuntime(0, 32))
        assertFalse(EngineProbe.supportsRuntime(0, 38))
    }
    @Test fun invalidProgressAndUnrecognizedStatusesRemainUnknown() {
        assertEquals("UNKNOWN", EngineProbe.classifyStatus(0, Float.NaN, null))
        assertEquals("UNKNOWN", EngineProbe.classifyStatus(0, Float.POSITIVE_INFINITY, null))
        assertEquals("UNKNOWN", EngineProbe.classifyStatus(0, 1.01f, null))
        assertEquals("UNKNOWN", EngineProbe.classifyStatus(99, 0f, null))
    }
    @Test fun applicationErrorOverridesAnIdleStatus() {
        assertEquals("ERROR", EngineProbe.classifyStatus(0, 0f, 5))
        assertEquals("IDLE", EngineProbe.classifyStatus(0, 0f, null))
    }
}
