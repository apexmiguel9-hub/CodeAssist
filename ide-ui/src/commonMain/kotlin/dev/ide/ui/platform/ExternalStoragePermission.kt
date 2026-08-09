package dev.ide.ui.platform

import androidx.compose.runtime.Composable

/** Shared-storage access status — whether the app can create/read files in `/storage/emulated/0` (so
 *  projects can live at `CodeAssist/projects` instead of the app-private folder). */
enum class ExternalStorageStatus {
    /** Granted — projects go to shared storage. */
    GRANTED,

    /** Not granted yet (or permanently denied): projects stay in the app's private folder. */
    DENIED,

    /** The platform has no "all files access" concept (desktop) — nothing to ask for. */
    NOT_APPLICABLE,
}

/**
 * Queries and requests shared-storage access: on Android 11+ it deep-links to the OS "All files access"
 * settings screen (`MANAGE_EXTERNAL_STORAGE`, only grantable from Settings, not a runtime dialog); on
 * Android 8/9 it requests the legacy `WRITE_EXTERNAL_STORAGE` runtime permission. Backs the Settings
 * "Shared storage projects" action, mirroring [NotificationPermissionController]. Mirrors
 * [AndroidIde.hasPublicStorageAccess] on the Android side.
 */
interface ExternalStoragePermissionController {
    /** The current status, re-read on each call (permission state can change out-of-band via OS settings). */
    fun status(): ExternalStorageStatus

    /** Launch the OS grant flow (all-files settings screen on Android 11+, runtime prompt before that).
     *  No-op when already granted or not applicable. */
    fun request()

    /** Open the OS app settings / all-files-access screen — the recovery path when access is denied. */
    fun openSettings()
}

/** Resolve an [ExternalStoragePermissionController] for the current platform. */
@Composable
expect fun rememberExternalStoragePermissionController(): ExternalStoragePermissionController