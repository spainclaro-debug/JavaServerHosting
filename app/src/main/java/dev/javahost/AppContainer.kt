package dev.javahost

import android.content.Context
import dev.javahost.core.api.Downloader
import dev.javahost.core.api.JreController
import dev.javahost.core.api.ServerController
import dev.javahost.core.modrinth.ModrinthRepository
import dev.javahost.core.net.OkHttpDownloader
import dev.javahost.core.runtime.JreManager
import dev.javahost.core.server.ServerManager
import dev.javahost.core.storage.FileBrowser
import dev.javahost.core.storage.InstanceStore
import dev.javahost.core.storage.Paths
import dev.javahost.core.storage.SettingsStore
import dev.javahost.core.system.MemoryAdvisor
import dev.javahost.core.system.PowerGuides

/**
 * Process-wide dependency graph. Created once by [JavaHostApp]; the Activity,
 * the ViewModel and the foreground service all share the same instances, which
 * is what lets the service keep a server alive after the UI is destroyed.
 *
 * Lazy properties keep cold start cheap (a launcher tap should not hash the
 * settings JSON or walk instance directories until needed).
 */
class AppContainer(val context: Context) {

    val paths: Paths by lazy { Paths(context) }
    val settings: SettingsStore by lazy { SettingsStore(context) }
    val instances: InstanceStore by lazy { InstanceStore(paths) }
    val downloader: Downloader by lazy {
        OkHttpDownloader(userAgent = BuildConfig.MODRINTH_USER_AGENT)
    }

    /** Downloaded, checksum-verified aarch64 OpenJDK runtimes. */
    val jre: JreController by lazy { JreManager(context, settings, downloader) }

    /** The single child-JVM host shared by UI + foreground service. */
    val server: ServerController by lazy {
        ServerManager(context, paths, instances, settings, jre)
    }

    val memory: MemoryAdvisor by lazy { MemoryAdvisor(context) }
    val power: PowerGuides by lazy { PowerGuides(context) }
    val files: FileBrowser by lazy { FileBrowser(instances, paths) }
    val modrinth: ModrinthRepository by lazy {
        ModrinthRepository(downloader, instances, paths)
    }
}
