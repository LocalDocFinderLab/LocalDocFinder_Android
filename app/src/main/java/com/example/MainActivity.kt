package com.example

import android.app.SearchManager
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.ui.MainScreen
import com.example.ui.MainViewModel
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

            MyApplicationTheme(darkTheme = isDark) {
                MainScreen(
                    viewModel = viewModel,
                    autoFocusSearch = autoFocusSearchState.value,
                    isDarkTheme = isDark,
                    onToggleTheme = { viewModel.toggleTheme(isDark) }
                )
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
