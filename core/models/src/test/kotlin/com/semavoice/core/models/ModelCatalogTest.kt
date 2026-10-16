package com.semavoice.core.models

import com.semavoice.core.common.Language
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class ModelCatalogTest {
    @Test fun idsAreUnique() {
        val ids = ModelCatalog.builtIn.map { it.id }
        assertEquals(ids.size, ids.toSet().size)
    }

    @Test fun publishableModelsHaveFullChecksums() {
        ModelCatalog.builtIn.filter(ModelCatalog::isPublishable).forEach { m ->
            m.files.forEach { f ->
                assertTrue("${m.id}/${f.name} sha", f.sha256.matches(Regex("[0-9a-f]{64}")))
                assertTrue("${m.id}/${f.name} size", f.sizeBytes > 0)
                assertTrue(f.url.startsWith("https://"))
            }
        }
    }

    @Test fun amharicModelsArePublishedWithChecksums() {
        // Both Amharic models are hosted (GitHub release models-2026.10) with verified checksums.
        assertTrue(ModelCatalog.isPublishable(ModelCatalog.whisperAmharicTinyQ5))
        assertTrue(ModelCatalog.isPublishable(ModelCatalog.ethioAsrAmharicCtc))
        assertEquals("onnx-ctc", ModelCatalog.ethioAsrAmharicCtc.format)
        assertEquals("model_int8.onnx", ModelCatalog.ethioAsrAmharicCtc.files.first().name) // engine loads files.first()
        assertTrue(ModelCatalog.ethioAsrAmharicCtc.qualityRank > ModelCatalog.whisperAmharicTinyQ5.qualityRank)
        assertTrue(ModelCatalog.isPublishable(ModelCatalog.whisperBaseQ5))
    }

    @Test fun stockWhisperDoesNotClaimAmharicOrTigrinya() {
        listOf(ModelCatalog.whisperTinyQ5, ModelCatalog.whisperBaseQ5, ModelCatalog.whisperSmallQ5).forEach {
            assertFalse(Language.AMHARIC in it.languages)
            assertFalse(Language.TIGRINYA in it.languages)
            assertFalse(Language.AFAAN_OROMOO in it.languages)
        }
    }

    @Test fun translationDirectionsOnlyUseListedLanguages() {
        ModelCatalog.builtIn.filter { it.kind == ModelKind.TRANSLATION }.forEach { m ->
            m.directions.forEach { d ->
                assertTrue(d.from in m.languages); assertTrue(d.to in m.languages)
            }
        }
    }

    @Test fun sha256HelperMatchesKnownVector() {
        val f = File.createTempFile("sha", ".bin")
        try {
            f.writeText("abc")
            assertEquals("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad", ModelDownloader.sha256(f))
        } finally { f.delete() }
    }
}

class CompatibilityRulesTest {
    private fun device(ram: Int, cores: Int, abi: String = "arm64-v8a", v82: Boolean = true) =
        DeviceProfile(ram, ram / 2, cores, abi, v82, 34, CompatibilityRules.tierFor(ram, cores))

    @Test fun tiers() {
        assertEquals(PerformanceTier.LOW, CompatibilityRules.tierFor(2000, 4))
        assertEquals(PerformanceTier.MID, CompatibilityRules.tierFor(4000, 8))
        assertEquals(PerformanceTier.HIGH, CompatibilityRules.tierFor(8000, 8))
    }

    @Test fun lowEndPhoneCannotRunMedium() {
        val v = CompatibilityRules.check(ModelCatalog.whisperMediumQ5, device(2000, 4))
        assertTrue(v is CompatibilityRules.Verdict.TooLittleRam)
        assertFalse(CompatibilityRules.canRun(ModelCatalog.whisperMediumQ5, device(2000, 4)))
    }

    @Test fun lowEndPhoneCanRunBase() {
        assertTrue(CompatibilityRules.check(ModelCatalog.whisperBaseQ5, device(2000, 4)) is CompatibilityRules.Verdict.Ok)
    }

    @Test fun slowTierIsAWarningNotABlock() {
        val v = CompatibilityRules.check(ModelCatalog.whisperSmallQ5, device(3000, 4))
        assertTrue(v is CompatibilityRules.Verdict.SlowTier)
        assertTrue(CompatibilityRules.canRun(ModelCatalog.whisperSmallQ5, device(3000, 4)))
    }

    @Test fun oldArmCoreIsRejected() {
        assertTrue(CompatibilityRules.check(ModelCatalog.whisperTinyQ5, device(4000, 8, v82 = false)) is CompatibilityRules.Verdict.MissingCpuFeatures)
    }

    @Test fun armv7IsRejected() {
        assertTrue(CompatibilityRules.check(ModelCatalog.whisperTinyQ5, device(4000, 8, abi = "armeabi-v7a")) is CompatibilityRules.Verdict.UnsupportedAbi)
    }
}
