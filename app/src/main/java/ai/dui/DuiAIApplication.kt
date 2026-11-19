package ai.dui

import android.app.Application
import ai.dui.data.adb.AdbConnectManager
import ai.dui.data.updater.PluginUpdateCheckWorker
import ai.dui.data.updater.UpdateCheckWorker
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

class DuiAIApplication : Application(), coil.ImageLoaderFactory {
    /**
     * Images that live on the bridge (a server's files, attachments) need the security header, which Coil's
     * default client does not send. Only requests to the bridge address get it.
     */
    override fun newImageLoader(): coil.ImageLoader {
        val client = okhttp3.OkHttpClient.Builder()
            .addInterceptor { chain ->
                val req = chain.request()
                val bridge = ai.dui.data.preferences.AuthPreferences.currentBridgeHttpUrl.toHttpUrlOrNull()
                val sameServer = bridge != null && req.url.host == bridge.host && req.url.port == bridge.port
                chain.proceed(
                    if (sameServer) req.newBuilder()
                        .header(ai.dui.data.preferences.AuthPreferences.currentFramedHeader, "true")
                        .apply { ai.dui.data.preferences.AuthPreferences.edgeHeader()?.let { (n, v) -> header(n, v) } }
                        .build()
                    else req
                )
            }
            .build()
        return coil.ImageLoader.Builder(this).okHttpClient(client).build()
    }

    override fun onCreate() {
        super.onCreate()
        // a crash opens the crash screen (with the report) instead of the app just vanishing
        ai.dui.crash.CrashHandler.install(this)
        // the crash screen's own process needs none of the startup below
        if (ai.dui.crash.CrashHandler.isCrashProcess(this)) return
        // loads the saved server addresses, Remote mode and the SSH key into memory before anything connects
        ai.dui.data.preferences.AuthPreferences(this)
        // the chosen light / dark themes and the custom ones, before the first screen draws
        ai.dui.theme.ThemeManager.init(this)
        UpdateCheckWorker.schedule(this)
        PluginUpdateCheckWorker.schedule(this)
        // mini app reminders and exact-time tasks: alarms again (Android may have dropped them), off the main thread
        ai.dui.data.miniapps.MiniAppScheduler.rearmAsync(this)
        Thread { ai.dui.ui.miniapps.MiniAppShortcuts.migrate(this) }.start()
        // Reconnects adb when Wireless debugging turns back on (it gets a new port every time)
        AdbConnectManager.init(this)
        ai.dui.data.ssh.SshSessionFactory.init(this)
        // Connect to PC: the paired PCs and the one in use (AuthPreferences reads the latter on its own too)
        ai.dui.data.pc.PcConnect.init(this)
        // the cloud project new Antigravity chats start in (Remote Agents), before the first chat is created
        ai.dui.data.agent.agy.AgyCloud.init(this)
    }

}
