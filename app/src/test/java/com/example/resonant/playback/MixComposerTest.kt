package com.example.resonant.playback

import com.example.resonant.data.network.MixDocumentDTO
import com.example.resonant.data.network.MixSelectionDTO
import com.google.gson.Gson
import com.google.gson.JsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

/**
 * Paridad con el generador del backend: `mix-v2/` son los fixtures que escribe `MixFixturesTests` en
 * `ResonantBackend.Tests/Fixtures/mix-v2` (se copian aquí cuando se regeneran allí).
 */
class MixComposerTest {

    private val gson = Gson()

    private val names = listOf(
        "preset-fundido", "preset-crescendo", "preset-fusion", "preset-onda", "preset-crossover", "preset-corte",
        "preset-disolucion", "preset-impulso", "preset-halo", "preset-sin-transicion",
        "auto-compatible", "auto-drop", "auto-tempo-clash", "auto-b-without-intro",
        "loop-echo-noise", "one-bar", "free-window-off-beat", "no-sync-requested", "no-grid",
        // playmix-extended-beatmatch: Mi Gente (105) → Gasolina (96,77) generado con extended = true.
        "auto-extended-sync"
    )

    private data class FixtureSong(
        val durationMs: Int, val audioStartMs: Int, val audioEndMs: Int, val bpm: Double?, val bpmPrecise: Double?,
        val bpmStability: Double?, val beatsPerBar: Int?, val firstDownbeatMs: Int?, val downbeatGridJson: List<Int>?,
        val mixInMs: Int?, val mixOutMs: Int?, val dropMs: Int?, val keyCamelot: String?
    ) {
        fun toSong() = MixComposer.Song(
            durationMs, audioStartMs, audioEndMs, bpm, bpmPrecise, bpmStability, beatsPerBar, firstDownbeatMs,
            downbeatGridJson, mixInMs, mixOutMs, dropMs, keyCamelot
        )
    }

    private data class FixtureWindow(val aStartMs: Int, val bStartMs: Int, val bars: Int)

    private fun load(name: String): JsonObject {
        val text = requireNotNull(javaClass.classLoader!!.getResourceAsStream("mix-v2/$name.json")) { "falta $name" }
            .readBytes().toString(Charsets.UTF_8)
        return gson.fromJson(text, JsonObject::class.java)
    }

    private fun run(fixture: JsonObject): MixDocumentDTO? {
        val from = gson.fromJson(fixture.get("from"), FixtureSong::class.java).toSong()
        val to = gson.fromJson(fixture.get("to"), FixtureSong::class.java).toSong()
        val selection = gson.fromJson(fixture.get("selection"), MixSelectionDTO::class.java)
        val beatSync = fixture.get("beatSync")?.takeIf { !it.isJsonNull }?.asBoolean
        val extended = fixture.get("extended")?.takeIf { !it.isJsonNull }?.asBoolean == true
        val window = fixture.get("window")?.takeIf { !it.isJsonNull }?.let { gson.fromJson(it, FixtureWindow::class.java) }
            ?.let { MixComposer.freeWindow(from, to, it.aStartMs, it.bStartMs, it.bars) }
            ?: MixComposer.automaticWindow(from, to, extended = extended)
            ?: return null
        return MixComposer.compose(selection, window, from, to, beatSync, "fixture", extended)
    }

    @Test
    fun everyFixtureMatchesTheBackend() {
        for (name in names) {
            val fixture = load(name)
            val expected = fixture.get("document")?.takeIf { !it.isJsonNull }?.let { gson.fromJson(it, MixDocumentDTO::class.java) }
            val actual = run(fixture)
            if (expected == null) {
                assertNull(name, actual)
                continue
            }
            assertNotNull(name, actual)
            actual!!
            assertEquals("$name durationMs", expected.durationMs, actual.durationMs)
            assertEquals("$name a", expected.a, actual.a)
            assertEquals("$name b", expected.b, actual.b)
            assertEquals("$name bars", expected.bars, actual.bars)
            assertEquals("$name beatSync", expected.beatSync, actual.beatSync)
            assertEquals("$name extendedSync", expected.extendedSync, actual.extendedSync)
            assertEquals("$name selection", expected.selection, actual.selection)
            assertEquals("$name loop", expected.loop, actual.loop)
            assertEquals("$name fx", expected.fx, actual.fx)
            assertEquals("$name lanes", expected.lanes.orEmpty().keys, actual.lanes.orEmpty().keys)
            for ((lane, points) in expected.lanes.orEmpty()) {
                val mine = actual.lanes!![lane]!!
                assertEquals("$name $lane puntos", points.size, mine.size)
                points.zip(mine).forEachIndexed { i, (e, m) ->
                    assertTrue("$name $lane[$i].t ${e.t} != ${m.t}", abs(e.t - m.t) <= 1)
                    assertTrue("$name $lane[$i].v ${e.v} != ${m.v}", abs(e.v - m.v) <= 1e-6)
                    assertEquals("$name $lane[$i].curve", e.curve, m.curve)
                }
            }
        }
    }

    @Test
    fun automaticSelectionFollowsTheSeedScores() {
        val stable = MixComposer.Song(240_000, 200, 238_500, bpm = 126.0, bpmPrecise = 126.0, bpmStability = 0.97, firstDownbeatMs = 500)
        assertEquals("lpf-in-out", MixComposer.automaticSelection(stable.copy(keyCamelot = "8A"), stable.copy(keyCamelot = "11B")).filter)
        assertEquals("three-band-fade", MixComposer.automaticSelection(stable.copy(keyCamelot = "8A"), stable.copy(keyCamelot = "9A")).eq)
        assertEquals("smooth-crossfade", MixComposer.automaticSelection(stable, stable.copy(bpmPrecise = 100.0)).volume)
    }
}
