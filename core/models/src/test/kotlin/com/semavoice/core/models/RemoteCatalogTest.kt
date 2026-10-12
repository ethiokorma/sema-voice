package com.semavoice.core.models

import com.semavoice.core.common.Language
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class RemoteCatalogTest {
    private val catalog = RemoteCatalog(File.createTempFile("cat", ".json").also { it.delete() })

    @Test fun parsesEntriesAndDerivesDefaults() {
        val text = """
            [{"id":"whisper-small-amharic-q5_1","file":"ggml-small-amharic-q5_1.bin","sizeBytes":190000000,
              "sha256":"ae85e4a935d7a567bd102fe55afc16bb595bdb618e11b2fc7591bc08120411bb","sourceRepo":"seyyaw/whisper-finetuned-amharic",
              "license":"Apache-2.0","base":"small","languages":["am","en"]},
             {"id":"bad","file":"x.bin","sizeBytes":1,"sha256":"not-a-hash"}]
        """.trimIndent()
        val models = catalog.parse(text, "https://host/models")
        assertEquals(1, models.size)
        val m = models[0]
        assertEquals("https://host/models/ggml-small-amharic-q5_1.bin", m.files[0].url)
        assertTrue(Language.AMHARIC in m.languages)
        assertEquals(700, m.requiredRamMb)
        assertEquals(PerformanceTier.MID, m.minTier)
        assertTrue(m.recommended)
        assertTrue(m.experimental)
        assertTrue(ModelCatalog.isPublishable(m))
    }

    @Test fun malformedJsonYieldsNothing() {
        assertTrue(catalog.parse("{oops", "https://x").isEmpty())
    }
}
