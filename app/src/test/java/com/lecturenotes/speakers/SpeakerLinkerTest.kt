package com.lecturenotes.speakers

import com.lecturenotes.notes.NoteStyle
import com.lecturenotes.notes.Prompts
import com.lecturenotes.notes.StudyNotes
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

class SpeakerLinkerTest {
    /** A fake "voice": a fixed random direction, plus a little noise per occurrence. */
    private val rnd = Random(7)
    private fun voice() = FloatArray(192) { rnd.nextFloat() - 0.5f }
    private fun noisy(v: FloatArray, amount: Float = 0.25f) = FloatArray(v.size) { v[it] + (rnd.nextFloat() - 0.5f) * amount }

    @Test
    fun sameVoicesAreLinkedAcrossPiecesEvenWhenLocalIdsSwap() {
        val lecturer = voice()
        val student = voice()
        val linker = SpeakerLinker()
        val piece1 = linker.link(listOf(noisy(lecturer), noisy(student)))
        // In piece 2 the diarizer happens to number the student first.
        val piece2 = linker.link(listOf(noisy(student), noisy(lecturer)))
        assertEquals(piece1[0], piece2[1])
        assertEquals(piece1[1], piece2[0])
        assertEquals(2, linker.speakerCount)
    }

    @Test
    fun aNewVoiceBecomesANewSpeaker() {
        val linker = SpeakerLinker()
        linker.link(listOf(voice()))
        val ids = linker.link(listOf(voice()))
        assertEquals(1, ids.single())
        assertEquals(2, linker.speakerCount)
    }

    @Test
    fun twoLocalSpeakersNeverMergeIntoOne() {
        val a = voice()
        val linker = SpeakerLinker()
        linker.link(listOf(a))
        // Both local speakers resemble A; only the closer one may take A's id.
        val ids = linker.link(listOf(noisy(a, 0.1f), noisy(a, 0.6f)))
        assertTrue(ids[0] != ids[1])
    }

    @Test
    fun segmentsGetTheSpeakerWhoTalksMostDuringThem() {
        val turns = listOf(Turn(0, 10_000, 0), Turn(10_000, 13_000, 1), Turn(13_000, 30_000, 0))
        val speakers = SpeakerLinker.assign(
            listOf(0L..9_000L, 9_500L..13_500L, 20_000L..25_000L, 40_000L..45_000L),
            turns,
        )
        assertEquals(listOf(0, 1, 0, null), speakers)
    }

    @Test
    fun adjacentTurnsOfOneSpeakerMerge() {
        val merged = SpeakerLinker.mergeAdjacent(listOf(Turn(0, 5_000, 0), Turn(5_400, 9_000, 0), Turn(9_000, 12_000, 1)))
        assertEquals(listOf(Turn(0, 9_000, 0), Turn(9_000, 12_000, 1)), merged)
    }

    @Test
    fun talkTimeSumsTurns() {
        assertEquals(mapOf(0 to 7_000L, 1 to 2_000L), SpeakerLinker.talkTime(listOf(Turn(0, 5_000, 0), Turn(5_000, 7_000, 1), Turn(8_000, 10_000, 0))))
    }

    @Test
    fun classQaIsRequestedOnlyWithSpeakersAndParsed() {
        val counts = Prompts.counts(NoteStyle.STANDARD, 600_000, whole = true)
        val with = Prompts.request("L", null, NoteStyle.STANDARD, "[0:00] (Lecturer) hi", counts, null, "", speakers = true)
        val without = Prompts.request("L", null, NoteStyle.STANDARD, "[0:00] hi", counts, null, "")
        assertTrue("classQA" in with)
        assertTrue("(Lecturer)" in with)
        assertFalse("classQA" in without)

        val parsed = StudyNotes.parse(
            JSONObject("""{"classQA":[{"question":"Why ReLU?","askedBy":"Speaker 2","answer":"Avoids vanishing gradients.","at":"12:40"},{"question":"","answer":"x"}]}"""),
            3_600_000, "T",
        )
        assertEquals(1, parsed.classQA.size)
        assertEquals("Speaker 2", parsed.classQA[0].askedBy)
        assertEquals(760_000L, parsed.classQA[0].atMs)
        assertNull(StudyNotes.parse(JSONObject("{}"), 1, "T").classQA.firstOrNull())
    }
}
