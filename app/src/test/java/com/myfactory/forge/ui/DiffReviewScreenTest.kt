package com.myfactory.forge.ui

import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.performClick
import com.myfactory.forge.core.diff.FilePatch
import com.myfactory.forge.core.diff.UnifiedDiff
import com.myfactory.forge.ui.screens.DiffReviewScreen
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Per-hunk review is the screen where a wrong button costs the user their
 * work, so the assertions are about exactly which hunks come back.
 *
 * Direction matters: the patch describes what changed since the checkpoint,
 * and the set handed to onApply is the hunks to REVERT.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class DiffReviewScreenTest {

    @get:Rule
    val compose = createComposeRule()

    private val nl: String = 10.toChar().toString()

    /** 40 numbered lines with two distant edits, so the diff has two hunks. */
    private val twoHunkPatch: FilePatch = UnifiedDiff.create(
        oldText = (1..40).joinToString(nl) { "line $it" } + nl,
        newText = (1..40).joinToString(nl) { index ->
            when (index) {
                3 -> "FIRST CHANGE"
                30 -> "SECOND CHANGE"
                else -> "line $index"
            }
        } + nl,
        path = "src/App.kt",
    )

    private fun show(
        patches: List<FilePatch> = listOf(twoHunkPatch),
        onApply: (Map<String, Set<Int>>) -> Unit = {},
        onCancel: () -> Unit = {},
    ) {
        compose.render {
            DiffReviewScreen(patches = patches, onApply = onApply, onCancel = onCancel)
        }
    }

    @Test
    fun `the patch under test really has two hunks`() {
        assertEquals(2, twoHunkPatch.hunks.size)
    }

    @Test
    fun `everything starts accepted so apply has nothing to revert`() {
        show()

        // All hunks kept means zero reverts, so the apply button is inert.
        compose.node("Apply 0 changes").assertIsNotEnabled()
    }

    @Test
    fun `reject all marks every hunk for reverting`() {
        var applied: Map<String, Set<Int>>? = null
        show(onApply = { applied = it })

        compose.node("Reject all").performClick()
        compose.node("Apply 2 changes").assertIsEnabled()
        compose.node("Apply 2 changes").performClick()

        assertEquals(mapOf("src/App.kt" to setOf(0, 1)), applied)
    }

    @Test
    fun `accept all after reject all returns to reverting nothing`() {
        show()

        compose.node("Reject all").performClick()
        compose.node("Accept all").performClick()

        compose.node("Apply 0 changes").assertIsNotEnabled()
    }

    @Test
    fun `rejecting a single hunk reverts only that one`() {
        var applied: Map<String, Set<Int>>? = null
        show(onApply = { applied = it })

        // Untick the first hunk; the second stays accepted.
        compose.node("Accept").performClick()

        compose.node("Apply 1 change").performClick()

        assertEquals(mapOf("src/App.kt" to setOf(0)), applied)
    }

    @Test
    fun `cancel applies nothing`() {
        var applied: Map<String, Set<Int>>? = null
        var cancelled = false
        show(onApply = { applied = it }, onCancel = { cancelled = true })

        compose.node("Reject all").performClick()
        compose.node("Cancel").performClick()

        assertTrue(cancelled)
        assertTrue("cancel must not apply anything", applied == null)
    }

    @Test
    fun `the file and its line counts are shown`() {
        show()

        compose.label("src/App.kt").assertExists()
        // The count appears in the per-file header and again in the
        // bottom summary bar, so assert on both rather than one.
        compose.onAllNodesWithText("2 added", substring = true, useUnmergedTree = true)
            .assertCountEquals(2)
    }

    @Test
    fun `an empty review says so instead of showing an empty list`() {
        show(patches = emptyList())

        compose.label("No changes to review.").assertExists()
    }
}
