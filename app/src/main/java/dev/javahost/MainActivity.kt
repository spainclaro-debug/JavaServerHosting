package dev.javahost

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import dev.javahost.ui.AppRoot
import dev.javahost.ui.AppViewModel
import dev.javahost.ui.theme.JavaHostTheme

class MainActivity : ComponentActivity() {

    private val viewModel: AppViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        handleServerIntent(intent)
        setContent {
            val state by viewModel.state.collectAsState()
            JavaHostTheme(state.settings.theme) {
                AppRoot(viewModel)
            }
        }
    }

    override fun onResume() {
        super.onResume()
        // Battery/notification permissions can change in system settings while
        // the app is backgrounded; refresh so guides and wizard summaries are not
        // stale when the user returns.
        viewModel.onResume()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleServerIntent(intent)
    }

    /** Notification / foreground-service content intents deep-link to the instance. */
    private fun handleServerIntent(intent: Intent?) {
        val instanceId = intent?.getStringExtra(EXTRA_INSTANCE_ID) ?: return
        val openConsole = intent.getBooleanExtra(EXTRA_OPEN_CONSOLE, false)
        viewModel.onExternalNavigation(instanceId, openConsole)
        intent.removeExtra(EXTRA_INSTANCE_ID)
        intent.removeExtra(EXTRA_OPEN_CONSOLE)
    }

    companion object {
        const val EXTRA_INSTANCE_ID = "instance_id"
        const val EXTRA_OPEN_CONSOLE = "open_console"
    }
}
