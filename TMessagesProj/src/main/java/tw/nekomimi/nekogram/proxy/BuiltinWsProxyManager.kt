package tw.nekomimi.nekogram.proxy

import android.content.Context
import org.telegram.messenger.ApplicationLoader
import org.telegram.messenger.FileLog
import org.telegram.messenger.MessagesController
import org.telegram.messenger.NotificationCenter
import org.telegram.messenger.SharedConfig
import org.telegram.tgnet.ConnectionsManager
import java.net.ServerSocket
import java.security.SecureRandom

object BuiltinWsProxyManager {
    private const val PREF_KEY = "builtin_ws_proxy_enabled"
    private const val PREF_PORT = "builtin_ws_proxy_port"
    private const val PREF_SECRET = "builtin_ws_proxy_secret"

    private var isRunning = false
    private var activePort = 0
    private var activeSecret = ""

    fun isEnabled(): Boolean {
        return ApplicationLoader.applicationContext
            .getSharedPreferences("mainconfig", Context.MODE_PRIVATE)
            .getBoolean(PREF_KEY, false)
    }

    fun setEnabled(enabled: Boolean) {
        ApplicationLoader.applicationContext
            .getSharedPreferences("mainconfig", Context.MODE_PRIVATE)
            .edit()
            .putBoolean(PREF_KEY, enabled)
            .apply()

        if (enabled) {
            start()
        } else {
            stop()
        }
    }

    fun getActivePort(): Int = activePort
    fun getSecret(): String = activeSecret
    fun isProxyRunning(): Boolean = isRunning

    @Synchronized
    fun start(): Boolean {
        SharedConfig.loadProxyList()
        cleanupDuplicates()

        if (isRunning) {
            applyToTelegram(true)
            return true
        }

        val prefs = ApplicationLoader.applicationContext.getSharedPreferences("mainconfig", Context.MODE_PRIVATE)
        activeSecret = prefs.getString(PREF_SECRET, null) ?: generateRandomSecret().also {
            prefs.edit().putString(PREF_SECRET, it).apply()
        }

        activePort = findAvailablePort(10000 + SecureRandom().nextInt(40000))
        prefs.edit().putInt(PREF_PORT, activePort).apply()

        try {
            FileLog.d("BuiltinWsProxyManager: starting native Rust WS proxy on port $activePort with secret $activeSecret...")
            // Rust libtgwsproxy expects 32 hex chars secret in StartProxy, and sets dd prefix for MTProto
            val result = TgWsProxyNative.nativeStartProxy("127.0.0.1", activePort, "", activeSecret, 1)
            FileLog.d("BuiltinWsProxyManager: start result = $result")
            if (result == 0 || result == -1) {
                isRunning = true
                applyToTelegram(true)
                return true
            }
        } catch (t: Throwable) {
            FileLog.e("BuiltinWsProxyManager: failed to start native proxy", t)
        }
        return false
    }

    @Synchronized
    fun stop() {
        SharedConfig.loadProxyList()
        if (isRunning) {
            try {
                FileLog.d("BuiltinWsProxyManager: stopping native Rust WS proxy...")
                TgWsProxyNative.nativeStopProxy()
                isRunning = false
            } catch (t: Throwable) {
                FileLog.e("BuiltinWsProxyManager: failed to stop native proxy", t)
            }
        }
        applyToTelegram(false)
    }

    private fun generateRandomSecret(): String {
        val bytes = ByteArray(16)
        SecureRandom().nextBytes(bytes)
        return bytes.joinToString("") { "%02x".format(it) }
    }

    private fun findAvailablePort(preferredPort: Int): Int {
        try {
            ServerSocket(preferredPort).use { return preferredPort }
        } catch (ignored: Exception) {}
        try {
            ServerSocket(0).use { socket -> return socket.localPort }
        } catch (ignored: Exception) {}
        return 14443
    }

    private fun cleanupDuplicates() {
        val iterator = SharedConfig.proxyList.iterator()
        var found = false
        var changed = false
        while (iterator.hasNext()) {
            val p = iterator.next()
            if (p.address == "127.0.0.1" && (p.secret.startsWith("dd") || p.secret == activeSecret)) {
                if (found) {
                    iterator.remove()
                    changed = true
                } else {
                    found = true
                }
            }
        }
        if (changed) {
            SharedConfig.saveProxyList()
        }
    }

    private fun applyToTelegram(enable: Boolean) {
        SharedConfig.loadProxyList()
        val tgSecret = if (activeSecret.startsWith("dd")) activeSecret else "dd$activeSecret"

        if (enable) {
            var found: SharedConfig.ProxyInfo? = null
            for (p in SharedConfig.proxyList) {
                if (p.address == "127.0.0.1" && (p.secret == tgSecret || p.port == activePort)) {
                    found = p
                    break
                }
            }
            if (found == null) {
                found = SharedConfig.ProxyInfo("127.0.0.1", activePort, "", "", tgSecret)
                SharedConfig.proxyList.add(0, found)
            } else {
                found.port = activePort
                found.secret = tgSecret
            }
            SharedConfig.currentProxy = found
            SharedConfig.saveProxyList()
            MessagesController.getGlobalMainSettings().edit().putBoolean("proxy_enabled", true).commit()
            SharedConfig.saveConfig()

            ConnectionsManager.setProxySettings(
                true,
                found.address,
                found.port,
                found.username,
                found.password,
                found.secret
            )
            NotificationCenter.getGlobalInstance().postNotificationName(NotificationCenter.proxySettingsChanged)
        } else {
            val iterator = SharedConfig.proxyList.iterator()
            var changed = false
            while (iterator.hasNext()) {
                val p = iterator.next()
                if (p.address == "127.0.0.1" && (p.secret.startsWith("dd") || p.secret == tgSecret || p.port == activePort)) {
                    if (SharedConfig.currentProxy == p) {
                        SharedConfig.currentProxy = null
                    }
                    iterator.remove()
                    changed = true
                }
            }
            if (changed) {
                SharedConfig.saveProxyList()
            }

            if (SharedConfig.currentProxy == null) {
                MessagesController.getGlobalMainSettings().edit().putBoolean("proxy_enabled", false).commit()
                SharedConfig.saveConfig()
                ConnectionsManager.setProxySettings(false, "", 0, "", "", "")
            }
            NotificationCenter.getGlobalInstance().postNotificationName(NotificationCenter.proxySettingsChanged)
        }
    }
}
