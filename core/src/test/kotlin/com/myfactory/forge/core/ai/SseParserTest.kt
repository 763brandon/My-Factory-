package com.myfactory.forge.core.ai

import com.myfactory.forge.core.ai.net.SseParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SseParserTest {

    private val lf: String = 10.toChar().toString()
    private val cr: String = 13.toChar().toString()

    @Test
    fun `parses a simple event`() {
        val events = SseParser().feed("event: ping" + lf + "data: hello" + lf + lf)

        assertEquals(1, events.size)
        assertEquals("ping", events[0].event)
        assertEquals("hello", events[0].data)
    }

    @Test
    fun `joins multi-line data with newlines`() {
        val events = SseParser().feed("data: one" + lf + "data: two" + lf + lf)

        assertEquals("one" + lf + "two", events.single().data)
    }

    @Test
    fun `handles CRLF terminators`() {
        val events = SseParser().feed("data: x" + cr + lf + cr + lf)

        assertEquals("x", events.single().data)
    }

    @Test
    fun `handles bare CR terminators`() {
        val events = SseParser().feed("data: x" + cr + cr)

        assertEquals("x", events.single().data)
    }

    @Test
    fun `ignores comment keep-alive lines`() {
        val events = SseParser().feed(": keep-alive" + lf + "data: real" + lf + lf)

        assertEquals(1, events.size)
        assertEquals("real", events.single().data)
    }

    @Test
    fun `strips exactly one leading space from a value`() {
        val events = SseParser().feed("data:  two spaces" + lf + lf)

        assertEquals(" two spaces", events.single().data)
    }

    @Test
    fun `a value with no space after the colon is preserved`() {
        val events = SseParser().feed("data:tight" + lf + lf)

        assertEquals("tight", events.single().data)
    }

    @Test
    fun `an event split across arbitrary chunk boundaries still parses`() {
        // The real failure mode: TCP hands you half a line.
        val full = "event: message" + lf + "data: {\"a\":1}" + lf + lf +
            "event: done" + lf + "data: {\"b\":2}" + lf + lf

        for (chunkSize in 1..7) {
            val parser = SseParser()
            val collected = mutableListOf<com.myfactory.forge.core.ai.net.SseEvent>()
            var index = 0
            while (index < full.length) {
                val end = minOf(index + chunkSize, full.length)
                collected += parser.feed(full.substring(index, end))
                index = end
            }
            collected += parser.close()

            assertEquals("chunk size $chunkSize", 2, collected.size)
            assertEquals("{\"a\":1}", collected[0].data)
            assertEquals("{\"b\":2}", collected[1].data)
        }
    }

    @Test
    fun `a trailing event with no final blank line is flushed on close`() {
        val parser = SseParser()
        val duringFeed = parser.feed("data: last" + lf)
        val onClose = parser.close()

        assertTrue(duringFeed.isEmpty())
        assertEquals("last", onClose.single().data)
    }

    @Test
    fun `an id field is carried onto subsequent events`() {
        val events = SseParser().feed(
            "id: 42" + lf + "data: a" + lf + lf + "data: b" + lf + lf,
        )

        assertEquals("42", events[0].id)
        assertEquals("42", events[1].id)
    }

    @Test
    fun `unknown fields are ignored`() {
        val events = SseParser().feed("retry: 3000" + lf + "weird: x" + lf + "data: v" + lf + lf)

        assertEquals("v", events.single().data)
    }
}
