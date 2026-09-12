package com.myfactory.forge.core.ai.net

/**
 * Incremental server-sent-events parser, following the WHATWG event-stream
 * rules.
 *
 * It is fed arbitrary chunks, because the network does not respect line
 * boundaries, and emits an [SseEvent] each time a blank line dispatches the
 * accumulated fields. It handles CR, LF and CRLF terminators, the
 * `field: value` form with its single optional leading space, comment lines
 * starting with a colon, and multi-line `data` fields joined with newlines.
 *
 * Deliberately not a coroutine or a flow: it is a pure state machine, which
 * makes the awkward split-chunk cases trivial to test.
 */
class SseParser {

    private val pending = StringBuilder()
    private val data = StringBuilder()
    private var eventName: String? = null
    private var lastId: String? = null
    private var sawCarriageReturn = false

    /** Feeds a chunk and returns whatever events it completed. */
    fun feed(chunk: String): List<SseEvent> {
        val out = mutableListOf<SseEvent>()
        for (ch in chunk) {
            when (ch) {
                CR -> {
                    sawCarriageReturn = true
                    processLine(pending.toString(), out)
                    pending.setLength(0)
                }
                LF -> {
                    // Swallow the LF of a CRLF pair; the CR already ended the line.
                    if (sawCarriageReturn) {
                        sawCarriageReturn = false
                    } else {
                        processLine(pending.toString(), out)
                        pending.setLength(0)
                    }
                }
                else -> {
                    sawCarriageReturn = false
                    pending.append(ch)
                }
            }
        }
        return out
    }

    /**
     * Flushes a trailing line that arrived without a final terminator. Some
     * gateways close the connection right after the last data line.
     */
    fun close(): List<SseEvent> {
        val out = mutableListOf<SseEvent>()
        if (pending.isNotEmpty()) {
            processLine(pending.toString(), out)
            pending.setLength(0)
        }
        if (data.isNotEmpty()) dispatch(out)
        return out
    }

    private fun processLine(line: String, out: MutableList<SseEvent>) {
        if (line.isEmpty()) {
            if (data.isNotEmpty() || eventName != null) dispatch(out)
            return
        }
        if (line[0] == ':') return // comment, typically a keep-alive

        val colon = line.indexOf(':')
        val field: String
        val rawValue: String
        if (colon < 0) {
            field = line
            rawValue = ""
        } else {
            field = line.substring(0, colon)
            rawValue = line.substring(colon + 1)
        }
        val value = if (rawValue.startsWith(" ")) rawValue.substring(1) else rawValue

        when (field) {
            "event" -> eventName = value
            "data" -> {
                if (data.isNotEmpty()) data.append(LF)
                data.append(value)
            }
            "id" -> if (!value.contains(' ')) lastId = value
            // "retry" is reconnection advice; this client does not auto-reconnect.
            else -> Unit
        }
    }

    private fun dispatch(out: MutableList<SseEvent>) {
        out += SseEvent(event = eventName, data = data.toString(), id = lastId)
        data.setLength(0)
        eventName = null
    }

    private companion object {
        /** Carriage return, U+000D. */
        const val CR: Char = '\u000D'

        /** Line feed, U+000A. */
        const val LF: Char = '\u000A'
    }
}
