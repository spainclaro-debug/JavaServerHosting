package dev.javahost.core.system

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import androidx.core.app.NotificationManagerCompat
import dev.javahost.BuildConfig
import dev.javahost.core.model.AppSettings
import dev.javahost.core.model.GuideSection
import dev.javahost.core.model.GuideStep
import dev.javahost.core.model.PowerChecks

/**
 * Everything the app needs to keep a user-started server alive on a phone:
 * battery-optimization exemption, notification permission, developer options
 * and the Android 12+ "phantom process killer".
 *
 * Design rules:
 *  - Every intent is checked with [PackageManager.resolveActivity] and returned
 *    as nullable, because no single settings screen exists on every build.
 *  - No manufacturer-specific menu paths are hardcoded (they change per model
 *    and OS release and rot quickly). Vendor quirks are delegated to
 *    dontkillmyapp.com instead.
 *  - Guidance is exposed as [GuideSection] data so the Compose UI decides how
 *    to render it; nothing here touches the UI toolkit.
 *
 * This class only reads system state and builds intents; it is cheap and safe
 * to call from the main thread.
 */
class PowerGuides(context: Context) {

    private val appContext: Context = context.applicationContext

    /* ------------------------------------------------------------------ */
    /* Current state                                                      */
    /* ------------------------------------------------------------------ */

    /** True when Android is already not allowed to battery-optimise us. */
    fun isIgnoringBatteryOptimizations(): Boolean {
        val manager = appContext.getSystemService(Context.POWER_SERVICE) as? PowerManager
            ?: return false
        return runCatching {
            manager.isIgnoringBatteryOptimizations(appContext.packageName)
        }.getOrDefault(false)
    }

    /** True when the FGS notification is allowed to be visible (POST_NOTIFICATIONS). */
    fun areNotificationsEnabled(): Boolean =
        runCatching { NotificationManagerCompat.from(appContext).areNotificationsEnabled() }
            .getOrDefault(false)

    /**
     * One-call snapshot for the power/health checklist. `phantomProcessKiller`
     * guidance is only relevant from Android 12 (API 31).
     */
    fun checks(settings: AppSettings): PowerChecks = PowerChecks(
        batteryOptimizationIgnored = isIgnoringBatteryOptimizations(),
        notificationsGranted = areNotificationsEnabled(),
        androidSdk = Build.VERSION.SDK_INT,
        phantomProcessKillerGuidanceNeeded = phantomProcessKillerGuidanceNeeded(),
        mediaHackFeatureEnabled = BuildConfig.ENABLE_SILENT_MEDIA_HACK,
        mediaHackSettingEnabled = settings.silentMediaHack
    )

    fun phantomProcessKillerGuidanceNeeded(): Boolean =
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.S

    /* ------------------------------------------------------------------ */
    /* Intents (nullable: null means "this ROM cannot open that screen")  */
    /* ------------------------------------------------------------------ */

    /**
     * Direct "ignore battery optimizations" request. Falls back to the generic
     * battery-optimization list when the direct dialog is unavailable.
     */
    fun batteryExemptionIntent(): Intent? =
        requestIgnoreBatteryOptimizationsIntent() ?: batteryOptimizationListIntent()

    /** The generic battery-optimization / app battery usage settings screen. */
    fun batterySettingsIntent(): Intent? =
        batteryOptimizationListIntent() ?: appDetailsIntent()

    fun notificationSettingsIntent(): Intent? {
        val intent = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                .putExtra(Settings.EXTRA_APP_PACKAGE, appContext.packageName)
        } else {
            appDetailsIntentRaw()
        }
        return resolvable(intent)
    }

    fun developerOptionsIntent(): Intent? =
        resolvable(Intent(Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS))

    fun appDetailsIntent(): Intent? = resolvable(appDetailsIntentRaw())

    fun dontKillMyAppIntent(): Intent? =
        resolvable(Intent(Intent.ACTION_VIEW, Uri.parse(DONT_KILL_MY_APP_URL)))

    private fun appDetailsIntentRaw(): Intent =
        Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, packageUri())

    private fun requestIgnoreBatteryOptimizationsIntent(): Intent? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) return null
        return resolvable(
            Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, packageUri())
        )
    }

    private fun batteryOptimizationListIntent(): Intent? =
        resolvable(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))

    private fun packageUri(): Uri = Uri.parse("package:${appContext.packageName}")

    /**
     * Adds [Intent.FLAG_ACTIVITY_NEW_TASK] (callers may be services) and returns
     * null when no activity can handle the intent on this build.
     */
    private fun resolvable(intent: Intent): Intent? = try {
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        if (appContext.packageManager.resolveActivity(intent, PackageManager.MATCH_DEFAULT_ONLY) != null) {
            intent
        } else {
            null
        }
    } catch (t: Throwable) {
        null
    }

    /* ------------------------------------------------------------------ */
    /* Guidance data                                                      */
    /* ------------------------------------------------------------------ */

    /** All guidance, in the order the help screen should show it. */
    fun guideSections(): List<GuideSection> = listOf(
        batteryGuide(),
        notificationGuide(),
        phantomKillerGuide(),
        oemBackgroundGuide(),
        troubleshootingGuide()
    )

    fun batteryGuide(): GuideSection = GuideSection(
        title = "Allow JavaHost to keep running",
        intro = "Android tries to save power by stopping background work. A Java server mostly " +
            "sits at 0% CPU while waiting for players, which makes it look idle and easy to " +
            "kill. Exempting JavaHost from battery optimisation is the single most important " +
            "reliability step.",
        steps = listOf(
            GuideStep(
                "Open battery optimisation",
                "Use the button below. If JavaHost is not listed, switch the filter to " +
                    "\"All apps\" first."
            ),
            GuideStep(
                "Choose \"Don't optimise\"",
                "On Android 13+ the same switch lives under Settings > Apps > JavaHost > " +
                    "Battery and is called \"Unrestricted\"."
            ),
            GuideStep(
                "Verify",
                "Re-open this screen: JavaHost should report that battery optimisation is " +
                    "disabled for the app."
            )
        ),
        adbCommands = listOf(
            "adb shell dumpsys deviceidle whitelist +${appContext.packageName}"
        ),
        helpUrl = DONT_KILL_MY_APP_URL
    )

    fun notificationGuide(): GuideSection = GuideSection(
        title = "Allow the status notification",
        intro = "The foreground service shows a live notification with status, uptime, players, " +
            "heap usage and free RAM, plus Stop and Restart actions. On Android 13+ the " +
            "notification is hidden until the permission is granted; the server itself keeps " +
            "running either way.",
        steps = listOf(
            GuideStep(
                "Open notification settings",
                "Use the button below, or Settings > Apps > JavaHost > Notifications."
            ),
            GuideStep(
                "Enable notifications",
                "Allow JavaHost to post notifications. The \"Server status\" channel must be on " +
                    "to see live progress, but it never makes a sound."
            )
        ),
        adbCommands = emptyList(),
        helpUrl = null
    )

    /**
     * Generic Android 12/12L/13/14+ phantom-process-killer walkthrough.
     *
     * This deliberately does not name vendor menus: the restriction is part of
     * AOSP and the switch is in Developer options on stock builds, while the ADB
     * commands work on every build that exposes device_config.
     */
    fun phantomKillerGuide(): GuideSection = GuideSection(
        title = "Stop Android's child-process killer (Android 12+)",
        intro = "Android 12 introduced the \"phantom process killer\". When an app spawns too " +
            "many child processes - or when the system is short on memory - Android silently " +
            "kills those children. JavaHost runs the server as a child process, so a busy " +
            "server can disappear with no crash report. This is a generic AOSP restriction; " +
            "it cannot be bypassed from inside the app, but it can be disabled for this device.",
        steps = listOf(
            GuideStep(
                "Enable Developer options",
                "Open Settings > About phone and tap \"Build number\" seven times. The exact " +
                    "path to Developer options varies slightly by device; it is typically under " +
                    "Settings > System."
            ),
            GuideStep(
                "Turn off child process restrictions",
                "Open Developer options and enable \"Disable child process restrictions\". If " +
                    "your build does not expose that switch, use the ADB commands below."
            ),
            GuideStep(
                "Or use ADB (works on Android 12, 12L, 13 and 14+)",
                "Enable USB or wireless debugging, connect the phone to a computer and run the " +
                    "three commands below once. They raise the phantom process limit to the " +
                    "maximum and, on Android 14+, keep that value across reboots."
            ),
            GuideStep(
                "Verify",
                "Run `adb shell device_config get activity_manager max_phantom_processes`. A " +
                    "value of 2147483647 means the limit is effectively removed."
            ),
            GuideStep(
                "Undo if needed",
                "To restore Android's default, run " +
                    "`adb shell device_config delete activity_manager max_phantom_processes` " +
                    "and reboot."
            )
        ),
        adbCommands = listOf(
            "adb shell settings put global settings_enable_monitor_phantom_procs false",
            "adb shell device_config set_sync_disabled_for_tests persistent",
            "adb shell device_config put activity_manager max_phantom_processes 2147483647"
        ),
        helpUrl = DONT_KILL_MY_APP_URL
    )

    fun oemBackgroundGuide(): GuideSection = GuideSection(
        title = "Manufacturer background restrictions",
        intro = "Many manufacturers layer extra background killers on top of Android (auto-start " +
            "managers, \"deep sleep\", \"app freeze\" and similar). These menus differ on every " +
            "model and change between OS releases, so JavaHost intentionally does not hardcode " +
            "vendor-specific paths that would go stale.",
        steps = listOf(
            GuideStep(
                "Check dontkillmyapp.com",
                "Open dontkillmyapp.com, find your manufacturer and follow the current steps " +
                    "for JavaHost. The instructions are kept up to date per device."
            ),
            GuideStep(
                "Allow unrestricted background usage",
                "In App info > Battery, pick \"Unrestricted\" / \"Don't optimise\". On some " +
                    "builds also enable \"Allow background activity\" and auto-start."
            ),
            GuideStep(
                "Test with the screen off",
                "Start the server, turn the screen off for ten minutes, then reopen JavaHost " +
                    "and check the uptime. If it reset, a background killer is still active."
            )
        ),
        adbCommands = emptyList(),
        helpUrl = DONT_KILL_MY_APP_URL
    )

    fun troubleshootingGuide(): GuideSection = GuideSection(
        title = "Still having issues?",
        intro = "If the server still stops on its own after the steps above, collect diagnostics " +
            "before asking for help so the cause is visible in the log.",
        steps = listOf(
            GuideStep(
                "Read the console log",
                "The console keeps the last lines before the process vanished; crash reports are " +
                    "written under the instance's crashes/ folder and logs/latest.log."
            ),
            GuideStep(
                "Include device details",
                "Manufacturer, model, Android version and whether the screen was off are the " +
                    "facts that identify an OEM killer quickly."
            ),
            GuideStep(
                "Ask for help",
                "Open the project issue tracker or dontkillmyapp.com and attach the console tail " +
                    "and the crash report."
            )
        ),
        adbCommands = emptyList(),
        helpUrl = DONT_KILL_MY_APP_URL
    )

    companion object {
        const val DONT_KILL_MY_APP_URL = "https://dontkillmyapp.com/"
        const val ADB_PHANTOM_PROCESS_LIMIT = 2147483647
    }
}
