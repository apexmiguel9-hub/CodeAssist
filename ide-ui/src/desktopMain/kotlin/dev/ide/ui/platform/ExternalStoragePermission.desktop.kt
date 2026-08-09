package dev.ide.ui.platform

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember

/** Desktop has no "all files access" notion (projects already live in plain filesystem folders), so the
 *  controller is inert: nothing to request, nothing to open. */
@Composable
actual fun rememberExternalStoragePermissionController(): ExternalStoragePermissionController =
    remember {
        object : ExternalStoragePermissionController {
            override fun status() = ExternalStorageStatus.NOT_APPLICABLE
            override fun request() {}
            override fun openSettings() {}
        }
    }