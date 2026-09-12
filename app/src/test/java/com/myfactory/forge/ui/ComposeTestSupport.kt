package com.myfactory.forge.ui

import androidx.compose.runtime.Composable
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.junit4.ComposeContentTestRule
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performSemanticsAction
import com.myfactory.forge.ui.theme.ForgeTheme

/**
 * Shared helpers for the screen tests.
 *
 * The split between [node] and [label] is deliberate and the thing to get
 * right when adding a test:
 *
 *  - [node] searches the MERGED tree, so a match resolves to the control
 *    itself. Use it to click and to assert enabled state, because only the
 *    control carries an onClick and an enabled flag.
 *  - [label] searches the UNMERGED tree, so it finds the text wherever it
 *    actually lives. Use it to assert that something is on screen, and for
 *    the few Material 3 containers whose label sits under
 *    clearAndSetSemantics and so never reaches the merged node. Clicking a
 *    label still works, because it lies inside its control's bounds.
 */

/**
 * Renders [content] inside the app theme, with dynamic colour off so the
 * palette does not vary with the SDK level the test runs on.
 */
fun ComposeContentTestRule.render(content: @Composable () -> Unit) {
    setContent {
        ForgeTheme(dynamicColor = false) { content() }
    }
}

/** The control carrying this text. Merged tree: clickable and has enabled state. */
fun ComposeContentTestRule.node(text: String): SemanticsNodeInteraction =
    onNodeWithText(text, useUnmergedTree = false)

/** The text itself, wherever it sits. Unmerged tree: for presence assertions. */
fun ComposeContentTestRule.label(text: String): SemanticsNodeInteraction =
    onNodeWithText(text, useUnmergedTree = true)

/** Substring match on the unmerged tree, for text built from a format string. */
fun ComposeContentTestRule.labelContaining(text: String): SemanticsNodeInteraction =
    onNodeWithText(text, substring = true, useUnmergedTree = true)

/**
 * The single editable field on screen. Finding by the field's label would
 * match the floating label rather than the input, which has no text action.
 */
fun ComposeContentTestRule.textField(): SemanticsNodeInteraction =
    onNode(hasSetTextAction())

/**
 * Invokes a control's click action directly instead of dispatching a touch.
 *
 * Needed inside a ModalBottomSheet: under Robolectric the sheet is still
 * sliding in when the test runs, so a synthetic tap lands outside the
 * button's bounds and silently does nothing. Driving the semantics action
 * still proves the control is wired to its callback, which is what these
 * tests are for.
 */
fun SemanticsNodeInteraction.invokeClick(): SemanticsNodeInteraction =
    performSemanticsAction(SemanticsActions.OnClick)

/** Matches any node that exposes a click action. */
val hasClickAction: SemanticsMatcher =
    SemanticsMatcher.keyIsDefined(SemanticsActions.OnClick)

/**
 * An icon-only control, found by its accessibility label.
 *
 * That these are findable this way is itself the assertion worth making: an
 * icon button with no content description is invisible to a screen reader.
 */
fun ComposeContentTestRule.button(contentDescription: String): SemanticsNodeInteraction =
    onNodeWithContentDescription(contentDescription, useUnmergedTree = false)

/**
 * The nth control carrying this text, for lists where every row has the same
 * action buttons.
 */
fun ComposeContentTestRule.nodeAt(text: String, index: Int): SemanticsNodeInteraction =
    onAllNodesWithText(text, useUnmergedTree = false)[index]

/**
 * Brings the control into view before pressing it.
 *
 * Settings and the provider editor are long scrolling columns, and the
 * Robolectric viewport is only 470px tall, so most of their controls start
 * below the fold. A real user scrolls; so does the test.
 */
fun SemanticsNodeInteraction.scrollAndClick(): SemanticsNodeInteraction {
    performScrollTo()
    return performClick()
}
