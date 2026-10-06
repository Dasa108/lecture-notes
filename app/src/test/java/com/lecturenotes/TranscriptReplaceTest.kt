package com.lecturenotes

import com.lecturenotes.transcribe.TranscriptReplace
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class TranscriptReplaceTest {
    private val lines = mapOf(1L to "each call is an ASM.", 2L to "the ASMs and asm", 3L to "plasma")

    @Test fun wholeWordIgnoresCaseByDefault() {
        val r = TranscriptReplace.pattern("ASM", wholeWord = true, matchCase = false)!!
        assertEquals(mapOf(1L to "each call is an agent.", 2L to "the ASMs and agent"), TranscriptReplace.apply(lines, r, "agent"))
        assertEquals(2 to 2, TranscriptReplace.count(lines.values.toList(), r))
    }

    @Test fun matchCaseAndPartialWords() {
        val r = TranscriptReplace.pattern("ASM", wholeWord = false, matchCase = true)!!
        assertEquals(mapOf(1L to "each call is an X.", 2L to "the Xs and asm"), TranscriptReplace.apply(lines, r, "X"))
    }

    @Test fun replacementIsLiteralAndBlankFindIsNull() {
        val r = TranscriptReplace.pattern("a.m", wholeWord = false, matchCase = false)!!
        assertEquals(emptyMap<Long, String>(), TranscriptReplace.apply(mapOf(1L to "aXm"), r, "\$1"))
        assertEquals(mapOf(1L to "\$1"), TranscriptReplace.apply(mapOf(1L to "a.m"), r, "\$1"))
        assertNull(TranscriptReplace.pattern("  ", true, false))
    }
}
