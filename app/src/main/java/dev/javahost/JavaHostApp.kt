package dev.javahost

import android.app.Application

/**
 * Process-wide entry point. The application object is intentionally lightweight:
 * all heavy state lives in [AppContainer], created lazily so a quick launcher
 * tap does not pay the cost of parsing settings or opening the instance store.
 */
class JavaHostApp : Application() {

    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
    }
}
