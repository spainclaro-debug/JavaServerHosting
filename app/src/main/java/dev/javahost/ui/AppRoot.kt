package dev.javahost.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.activity.compose.BackHandler
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import dev.javahost.ui.screens.ConsoleScreen
import dev.javahost.ui.screens.CreateInstanceScreen
import dev.javahost.ui.screens.FilesScreen
import dev.javahost.ui.screens.HomeScreen
import dev.javahost.ui.screens.JreScreen
import dev.javahost.ui.screens.ModsScreen
import dev.javahost.ui.screens.PowerGuideScreen
import dev.javahost.ui.screens.PropertiesScreen
import dev.javahost.ui.screens.ServerDetailScreen
import dev.javahost.ui.screens.SettingsScreen
import dev.javahost.ui.screens.WizardScreen

/** Lightweight in-app navigation stack; avoids pulling in navigation-compose. */
sealed interface Route {
    data object Home : Route
    data object Wizard : Route
    data object Create : Route
    data class Detail(val id: String) : Route
    data class Console(val id: String) : Route
    data class Files(val id: String) : Route
    data class Properties(val id: String) : Route
    data class Mods(val id: String) : Route
    data object Settings : Route
    data object Jre : Route
    data object PowerGuide : Route
}

@Composable
fun AppRoot(vm: AppViewModel) {
    val state by vm.state.collectAsState()
    val console by vm.consoleEntries.collectAsState()
    val navigation by vm.navigationRequest.collectAsState()

    var stack by remember(state.wizardComplete) {
        mutableStateOf(
            if (!state.wizardComplete) listOf<Route>(Route.Wizard)
            else listOf<Route>(Route.Home)
        )
    }

    fun push(route: Route) { stack = stack + route }
    fun pop() { if (stack.size > 1) stack = stack.dropLast(1) }
    fun resetToHome() { stack = listOf(Route.Home) }

    // Leave the wizard as soon as it is completed.
    LaunchedEffect(state.wizardComplete) {
        if (state.wizardComplete && stack.firstOrNull() == Route.Wizard) {
            stack = listOf(Route.Home)
        }
    }

    // Notification taps / service content intents.
    LaunchedEffect(navigation?.nonce, state.instances) {
        val request = navigation ?: return@LaunchedEffect
        val target = state.instance(request.instanceId)
        if (target == null) {
            // Cold start: the notification can arrive before the instance flow
            // has emitted. Keep the request until we have at least one instance
            // (or can be sure the id is gone), otherwise a notification tap
            // right after process death would silently land on Home.
            if (state.instances.isEmpty()) return@LaunchedEffect
            vm.consumeNavigation()
            return@LaunchedEffect
        }
        stack = listOf(Route.Home, Route.Detail(target.id)) +
            if (request.openConsole) listOf(Route.Console(target.id)) else emptyList()
        vm.consumeNavigation()
    }

    // If the currently displayed instance disappears (deleted), go home.
    val top = stack.lastOrNull()
    LaunchedEffect(top, state.instances.size) {
        when (top) {
            is Route.Detail -> if (state.instance(top.id) == null) resetToHome()
            is Route.Console -> if (state.instance(top.id) == null) resetToHome()
            is Route.Files -> if (state.instance(top.id) == null) resetToHome()
            is Route.Properties -> if (state.instance(top.id) == null) resetToHome()
            is Route.Mods -> if (state.instance(top.id) == null) resetToHome()
            else -> Unit
        }
    }

    BackHandler(enabled = stack.size > 1) { pop() }

    Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        when (val route = top) {
            null, Route.Home -> HomeScreen(
                state = state,
                actions = vm,
                onOpenInstance = { id -> push(Route.Detail(id)) },
                onCreate = { push(Route.Create) },
                onOpenSettings = { push(Route.Settings) },
                onOpenJre = { push(Route.Jre) },
                onOpenPowerGuide = { push(Route.PowerGuide) },
                onOpenConsole = { id -> push(Route.Console(id)) }
            )

            Route.Wizard -> WizardScreen(
                state = state,
                actions = vm,
                onFinished = { resetToHome() }
            )

            Route.Create -> CreateInstanceScreen(
                state = state,
                actions = vm,
                onBack = { pop() },
                onCreated = { id ->
                    stack = listOf(Route.Home, Route.Detail(id))
                }
            )

            Route.Settings -> SettingsScreen(
                state = state,
                actions = vm,
                onBack = { pop() },
                onOpenJre = { push(Route.Jre) },
                onOpenPowerGuide = { push(Route.PowerGuide) }
            )

            Route.Jre -> JreScreen(state = state, actions = vm, onBack = { pop() })

            Route.PowerGuide -> PowerGuideScreen(
                state = state,
                actions = vm,
                onBack = { pop() }
            )

            is Route.Detail -> {
                val instance = state.instance(route.id)
                if (instance != null) {
                    ServerDetailScreen(
                        state = state,
                        actions = vm,
                        instance = instance,
                        onBack = { pop() },
                        onConsole = { push(Route.Console(instance.id)) },
                        onFiles = { push(Route.Files(instance.id)) },
                        onProperties = { push(Route.Properties(instance.id)) },
                        onMods = { push(Route.Mods(instance.id)) },
                        onOpenJre = { push(Route.Jre) }
                    )
                }
            }

            is Route.Console -> {
                val instance = state.instance(route.id)
                if (instance != null) {
                    ConsoleScreen(
                        state = state,
                        actions = vm,
                        instance = instance,
                        // The manager keeps a single in-memory session; do not
                        // show another instance's live lines in this one's console.
                        entries = if (state.activeInstanceId == instance.id) console else emptyList(),
                        onBack = { pop() },
                        onOpenFiles = { push(Route.Files(instance.id)) }
                    )
                }
            }

            is Route.Files -> {
                val instance = state.instance(route.id)
                if (instance != null) {
                    FilesScreen(
                        state = state,
                        actions = vm,
                        instance = instance,
                        onBack = { pop() },
                        onOpenProperties = { push(Route.Properties(instance.id)) }
                    )
                }
            }

            is Route.Properties -> {
                val instance = state.instance(route.id)
                if (instance != null && instance.isMinecraft) {
                    PropertiesScreen(
                        state = state,
                        actions = vm,
                        instance = instance,
                        onBack = { pop() }
                    )
                }
            }

            is Route.Mods -> {
                val instance = state.instance(route.id)
                if (instance != null && instance.isMinecraft) {
                    ModsScreen(
                        state = state,
                        actions = vm,
                        instance = instance,
                        onBack = { pop() }
                    )
                }
            }
        }
    }
}
