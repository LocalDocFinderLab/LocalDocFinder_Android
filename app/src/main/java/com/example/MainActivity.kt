package com.example

import android.app.SearchManager
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.ui.MainScreen
import com.example.ui.MainViewModel
import com.example.ui.PermissionGateScreen
import com.example.ui.StorageAccess
import com.example.ui.theme.MyApplicationTheme
import com.example.widget.DocuVectorWidget

class MainActivity : ComponentActivity() {

    private val viewModel: MainViewModel by viewModels()
    private val autoFocusSearchState = mutableStateOf(false)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        handleSearchIntent(intent)

        setContent {
            val userDarkTheme by viewModel.isDarkTheme.collectAsStateWithLifecycle()
            val isDark = userDarkTheme ?: isSystemInDarkTheme()

            // The app needs to read the phone's files to do anything, so nothing else shows until it can.
            // Rechecked every time the app comes back to the front (e.g. from the Android settings screen).
            val context = LocalContext.current
            var hasFileAccess by remember { mutableStateOf(StorageAccess.hasAccess(context)) }
            val lifecycleOwner = LocalLifecycleOwner.current
            DisposableEffect(lifecycleOwner) {
                val observer = LifecycleEventObserver { _, event ->
                    if (event == Lifecycle.Event.ON_RESUME) hasFileAccess = StorageAccess.hasAccess(context)
                }
                lifecycleOwner.lifecycle.addObserver(observer)
                onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
            }

            MyApplicationTheme(darkTheme = isDark) {
                if (hasFileAccess) {
                    MainScreen(
                        viewModel = viewModel,
                        autoFocusSearch = autoFocusSearchState.value,
                        isDarkTheme = isDark,
                        onToggleTheme = { viewModel.toggleTheme(isDark) }
                    )
                } else {
                    PermissionGateScreen(onRecheck = { hasFileAccess = StorageAccess.hasAccess(context) })
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleSearchIntent(intent)
    }

    private fun handleSearchIntent(intent: Intent?) {
        if (intent == null) return

        if (intent.getBooleanExtra(com.example.updater.worker.AppUpdateCheckWorker.EXTRA_SHOW_UPDATE, false)) {
            viewModel.setShowUpdateSheet(true)
            viewModel.checkForUpdates(forceCheck = false)
        }

        val shouldFocus = intent.getBooleanExtra(DocuVectorWidget.EXTRA_FOCUS_SEARCH, false) ||
                intent.action == Intent.ACTION_SEARCH

        val query = intent.getStringExtra(DocuVectorWidget.EXTRA_QUERY)
            ?: intent.getStringExtra(SearchManager.QUERY)

        if (!query.isNullOrBlank()) {
            viewModel.onQueryChanged(query)
        }

        if (shouldFocus) {
            autoFocusSearchState.value = true
        }
    }
}
