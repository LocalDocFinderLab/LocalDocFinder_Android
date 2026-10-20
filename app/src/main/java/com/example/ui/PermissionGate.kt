package com.example.ui

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.CloudOff
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.VerifiedUser
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat

/** What "the app can read the user's files" means on each Android version, in one place. */
object StorageAccess {

    /** Android 11+: the "All files access" switch. Older versions: the normal storage permission. */
    fun hasAccess(context: Context): Boolean =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            Environment.isExternalStorageManager()
        } else {
            granted(context, Manifest.permission.READ_EXTERNAL_STORAGE)
        }

    /** Normal runtime permissions asked together up front (storage or media, plus notifications). */
    fun missingRuntimePermissions(context: Context): Array<String> {
        val wanted = buildList {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                add(Manifest.permission.READ_MEDIA_IMAGES)
                add(Manifest.permission.READ_MEDIA_AUDIO)
                add(Manifest.permission.READ_MEDIA_VIDEO)
                add(Manifest.permission.POST_NOTIFICATIONS)
            } else {
                add(Manifest.permission.READ_EXTERNAL_STORAGE)
            }
        }
        return wanted.filterNot { granted(context, it) }.toTypedArray()
    }

    /** True on Android 11+ where the second step (the All files access screen) exists. */
    val needsAllFilesStep: Boolean get() = Build.VERSION.SDK_INT >= Build.VERSION_CODES.R

    fun openAllFilesSettings(context: Context) {
        try {
            context.startActivity(
                Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION, Uri.parse("package:${context.packageName}"))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
        } catch (_: Exception) {
            try {
                context.startActivity(Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            } catch (_: Exception) {
                openAppSettings(context)
            }
        }
    }

    fun openAppSettings(context: Context) {
        try {
            context.startActivity(
                Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${context.packageName}"))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
        } catch (_: Exception) {
        }
    }

    private fun granted(context: Context, permission: String) =
        ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED
}

/**
 * Shown instead of the app until the phone's files are readable. It asks once, up front, for everything the
 * app needs, and says plainly what stays on the phone, so a first "Allow" does not feel like a leap of faith.
 * [onRecheck] is called when a permission prompt closes; coming back from the system settings screen is
 * rechecked by the caller on resume.
 */
@Composable
fun PermissionGateScreen(onRecheck: () -> Unit) {
    val context = LocalContext.current
    // Set after the first tap, so the screen can switch from "welcome" to "this is needed" wording.
    var attempted by rememberSaveable { mutableStateOf(false) }

    val runtimeLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        onRecheck()
        // Even if a notification or media prompt was declined, the one that matters is All files access.
        if (StorageAccess.needsAllFilesStep && !StorageAccess.hasAccess(context)) {
            StorageAccess.openAllFilesSettings(context)
        }
    }

    fun requestAccess() {
        attempted = true
        val missing = StorageAccess.missingRuntimePermissions(context)
        if (missing.isNotEmpty()) {
            runtimeLauncher.launch(missing)
        } else if (StorageAccess.needsAllFilesStep) {
            StorageAccess.openAllFilesSettings(context)
        } else {
            onRecheck()
        }
    }

    Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .safeDrawingPadding()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 24.dp, vertical = 20.dp)
                .testTag("permission_gate"),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Spacer(modifier = Modifier.height(16.dp))
            Surface(
                shape = CircleShape,
                color = MaterialTheme.colorScheme.primaryContainer,
                modifier = Modifier.size(84.dp)
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(
                        imageVector = if (attempted) Icons.Default.Lock else Icons.Default.FolderOpen,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(40.dp)
                    )
                }
            }

            Spacer(modifier = Modifier.height(20.dp))
            Text(
                text = if (attempted) "File access is needed for the app to work" else "Let LocalDoc Finder find your documents",
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold,
                textAlign = TextAlign.Center,
                modifier = Modifier.testTag("permission_gate_title")
            )
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = if (attempted) {
                    "Searching inside your files is the whole job of this app, and Android only allows it with your " +
                        "permission. Without it there is nothing to search. Your files stay on your phone either way."
                } else {
                    "To search inside your PDFs, notes, Office files and photos, the app has to read the files on this phone."
                },
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center
            )

            Spacer(modifier = Modifier.height(24.dp))
            PrivacyPoint(
                icon = Icons.Default.VerifiedUser,
                title = "Everything stays on your phone",
                body = "Your files are read and indexed right here. Their contents are never uploaded, shared or sold."
            )
            PrivacyPoint(
                icon = Icons.Default.CloudOff,
                title = "Almost no internet use",
                body = "The only things downloaded are the search models you choose and app updates. The app has no code that sends your files or searches out."
            )
            PrivacyPoint(
                icon = Icons.Default.CheckCircle,
                title = "Asked once",
                body = "Allow it now and you will not be interrupted with permission prompts later. You can switch it off any time in Android Settings."
            )

            Spacer(modifier = Modifier.height(24.dp))
            Button(
                onClick = { requestAccess() },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(52.dp)
                    .testTag("btn_grant_access"),
                shape = RoundedCornerShape(14.dp)
            ) {
                Text(
                    text = if (attempted) "Try again" else "Allow access to my files",
                    fontWeight = FontWeight.Bold
                )
            }
            if (StorageAccess.needsAllFilesStep) {
                Text(
                    text = "Android will open a settings screen: switch on \"Allow access to manage all files\" for this app, then come back.",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.padding(top = 10.dp)
                )
            }
            if (attempted) {
                TextButton(
                    onClick = { StorageAccess.openAppSettings(context) },
                    modifier = Modifier.testTag("btn_open_app_settings")
                ) { Text("Open app settings") }
            }
        }
    }
}

@Composable
private fun PrivacyPoint(icon: ImageVector, title: String, body: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 8.dp)
            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f), RoundedCornerShape(14.dp))
            .padding(14.dp),
        verticalAlignment = Alignment.Top
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(22.dp)
        )
        Spacer(modifier = Modifier.width(12.dp))
        Column {
            Text(text = title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
            Spacer(modifier = Modifier.height(2.dp))
            Text(text = body, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}
