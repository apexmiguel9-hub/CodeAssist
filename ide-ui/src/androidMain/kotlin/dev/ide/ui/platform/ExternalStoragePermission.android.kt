package dev.ide.ui.platform

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext

@Composable
actual fun rememberExternalStoragePermissionController(): ExternalStoragePermissionController {
    val context = LocalContext.current
    // Legacy path only (API 26-28): a runtime WRITE_EXTERNAL_STORAGE prompt. Android 11+ has no runtime
    // prompt for MANAGE_EXTERNAL_STORAGE — that's granted strictly from the OS settings screen.
    var pending by remember { mutableStateOf<((Boolean) -> Unit)?>(null) }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        val cb = pending; pending = null; cb?.invoke(granted)
    }
    return remember(context, launcher) {
        object : ExternalStoragePermissionController {
            override fun status(): ExternalStorageStatus = when {
                Build.VERSION.SDK_INT >= Build.VERSION_CODES.R ->
                    if (Environment.isExternalStorageManager()) ExternalStorageStatus.GRANTED
                    else ExternalStorageStatus.DENIED
                // Android 10 (API 29, targetSdk 36): scoped storage is enforced and all-files access doesn't
                // exist yet → shared root unreachable, same as AndroidIde.hasPublicStorageAccess.
                Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q -> ExternalStorageStatus.NOT_APPLICABLE
                else ->
                    if (context.checkSelfPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE) ==
                        PackageManager.PERMISSION_GRANTED
                    ) ExternalStorageStatus.GRANTED else ExternalStorageStatus.DENIED
            }

            override fun request() {
                if (status() == ExternalStorageStatus.GRANTED) return
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                    openSettings()
                } else if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
                    pending = { }
                    launcher.launch(Manifest.permission.WRITE_EXTERNAL_STORAGE)
                }
            }

            override fun openSettings() {
                val intent = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                    Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION)
                        .setData(Uri.parse("package:${context.packageName}"))
                } else {
                    Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
                        .setData(Uri.parse("package:${context.packageName}"))
                }
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                runCatching { context.startActivity(intent) }
            }
        }
    }
}