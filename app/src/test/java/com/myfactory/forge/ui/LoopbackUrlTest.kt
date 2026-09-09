package com.myfactory.forge.ui

import com.myfactory.forge.ui.screens.isLoopback
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * The preview WebView must never leave the device. These are the cases a
 * prefix check would get wrong, which is why the real check parses the host.
 *
 * Robolectric supplies android.net.Uri; the logic itself is pure.
 */
@RunWith(RobolectricTestRunner::class)
class LoopbackUrlTest {

    @Test
    fun `loopback addresses are allowed`() {
        assertTrue(isLoopback("http://127.0.0.1:3000"))
        assertTrue(isLoopback("http://127.0.0.1:8080/index.html"))
        assertTrue(isLoopback("http://localhost:5173/app"))
        assertTrue(isLoopback("https://localhost:443"))
    }

    @Test
    fun `a host that merely starts with a loopback address is refused`() {
        // The whole reason this is parsed rather than prefix-matched.
        assertFalse(isLoopback("http://127.0.0.1.evil.example/"))
        assertFalse(isLoopback("http://localhost.attacker.test/"))
        assertFalse(isLoopback("http://127.0.0.1@evil.example/"))
    }

    @Test
    fun `remote addresses are refused`() {
        assertFalse(isLoopback("https://example.com"))
        assertFalse(isLoopback("http://192.168.1.10:3000"))
        assertFalse(isLoopback("http://10.0.2.2:8080"))
    }

    @Test
    fun `non-http schemes are refused`() {
        assertFalse(isLoopback("file:///data/data/com.myfactory.forge/files/secret"))
        assertFalse(isLoopback("content://media/external/images"))
        assertFalse(isLoopback("javascript:alert(1)"))
        assertFalse(isLoopback("intent://scan/#Intent;scheme=zxing;end"))
    }

    @Test
    fun `malformed input is refused rather than throwing`() {
        assertFalse(isLoopback(""))
        assertFalse(isLoopback("not a url at all"))
        assertFalse(isLoopback("http://"))
    }
}
