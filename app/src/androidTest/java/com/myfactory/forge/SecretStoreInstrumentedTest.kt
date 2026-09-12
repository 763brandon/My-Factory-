package com.myfactory.forge

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.myfactory.forge.security.KeystoreSecretStore
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The keystore path only exists on a device, so this is the only place the
 * real encryption can be checked.
 *
 * Run with: ./gradlew :app:connectedDebugAndroidTest
 */
@RunWith(AndroidJUnit4::class)
class SecretStoreInstrumentedTest {

    private lateinit var store: KeystoreSecretStore

    @Before
    fun setUp() {
        store = KeystoreSecretStore(
            InstrumentationRegistry.getInstrumentation().targetContext,
        )
        store.clearAll()
    }

    @After
    fun tearDown() {
        store.clearAll()
    }

    @Test
    fun aKeyRoundTrips() {
        store.put("anthropic", "sk-ant-test-value-12345")

        assertEquals("sk-ant-test-value-12345", store.get("anthropic"))
        assertTrue(store.contains("anthropic"))
    }

    @Test
    fun theStoredFormIsNotThePlaintext() {
        val secret = "sk-plain-text-secret"
        store.put("provider", secret)

        val prefs = InstrumentationRegistry.getInstrumentation().targetContext
            .getSharedPreferences("forge_secrets", android.content.Context.MODE_PRIVATE)
        val stored = prefs.getString("provider", null)

        assertNotEquals(secret, stored)
        assertFalse(
            "the secret must not be recoverable from the preference file",
            stored!!.contains("plain-text"),
        )
    }

    @Test
    fun eachEncryptionUsesAFreshInitialisationVector() {
        // Reusing an IV under GCM is a real break, not a style issue.
        val prefs = InstrumentationRegistry.getInstrumentation().targetContext
            .getSharedPreferences("forge_secrets", android.content.Context.MODE_PRIVATE)

        store.put("a", "identical secret")
        val first = prefs.getString("a", null)
        store.put("b", "identical secret")
        val second = prefs.getString("b", null)

        assertNotEquals(
            "the same plaintext must not encrypt to the same ciphertext",
            first,
            second,
        )
    }

    @Test
    fun removingAKeyMakesItUnreadable() {
        store.put("gone", "value")
        store.remove("gone")

        assertNull(store.get("gone"))
        assertFalse(store.contains("gone"))
    }

    @Test
    fun clearingRemovesEveryKey() {
        store.put("one", "1")
        store.put("two", "2")

        store.clearAll()

        assertTrue(store.aliases().isEmpty())
    }

    @Test
    fun readingAnUnknownAliasReturnsNull() {
        assertNull(store.get("never-stored"))
    }

    @Test
    fun aLongKeyIsHandled() {
        val long = "sk-" + "x".repeat(4000)
        store.put("long", long)

        assertEquals(long, store.get("long"))
    }
}
