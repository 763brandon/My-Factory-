package com.myfactory.forge.ui.navigation

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Chat
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Terminal
import androidx.compose.material.icons.filled.Web
import androidx.compose.ui.graphics.vector.ImageVector
import com.myfactory.forge.R

/** The screens reachable from the bottom bar, in order. */
enum class Destination(
    val route: String,
    val labelRes: Int,
    val icon: ImageVector,
) {
    CHAT("chat", R.string.nav_chat, Icons.Default.Chat),
    FILES("files", R.string.nav_files, Icons.Default.Folder),
    TERMINAL("terminal", R.string.nav_terminal, Icons.Default.Terminal),
    PREVIEW("preview", R.string.nav_preview, Icons.Default.Web),
    SETTINGS("settings", R.string.nav_settings, Icons.Default.Settings),
    ;

    companion object {
        fun fromRoute(route: String?): Destination =
            entries.firstOrNull { it.route == route } ?: CHAT
    }
}
