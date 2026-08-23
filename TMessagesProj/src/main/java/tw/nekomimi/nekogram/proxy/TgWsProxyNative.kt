package tw.nekomimi.nekogram.proxy

import org.telegram.messenger.FileLog

object TgWsProxyNative {
    private var isLoaded = false

    init {
        try {
            try {
                System.loadLibrary("tgwsproxy")
            } catch (t: Throwable) {
                FileLog.e("TgWsProxyNative: direct load failed, will use dlopen fallback: " + t.message)
            }
            isLoaded = true
        } catch (t: Throwable) {
            FileLog.e("TgWsProxyNative init error: " + t.message)
        }
    }

    @JvmStatic
    external fun nativeStartProxy(
        host: String?,
        port: Int,
        dcIps: String?,
        secret: String?,
        verbose: Int
    ): Int

    @JvmStatic
    external fun nativeStopProxy(): Int

    fun isAvailable(): Boolean = isLoaded
}
