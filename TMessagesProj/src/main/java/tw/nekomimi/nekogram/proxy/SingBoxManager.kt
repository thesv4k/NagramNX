package tw.nekomimi.nekogram.proxy

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import org.telegram.messenger.ApplicationLoader
import org.telegram.messenger.FileLog
import java.io.File
import java.net.ServerSocket

object SingBoxManager {
    private const val DEFAULT_SOCKS_PORT = 10808
    private var activePort = DEFAULT_SOCKS_PORT
    private var process: Process? = null
    private var isRunning = false
    private var currentVlessUri: String? = null

    fun isRunning(): Boolean = isRunning
    fun getActivePort(): Int = activePort
    fun getCurrentUri(): String? = currentVlessUri

    @Synchronized
    fun start(vlessUri: String): Boolean {
        if (isRunning && currentVlessUri == vlessUri && process != null) {
            return true
        }
        stop()

        val config = VlessUriParser.parseVless(vlessUri) ?: return false
        val context = ApplicationLoader.applicationContext
        activePort = findAvailablePort(DEFAULT_SOCKS_PORT)

        val binaryPath = getBinaryPath(context)
        if (binaryPath == null || !File(binaryPath).exists()) {
            FileLog.e("SingBoxManager: sing-box binary not found at $binaryPath")
            return false
        }

        try {
            val jsonConfig = buildConfigJson(config, activePort)
            val configFile = File(context.filesDir, "singbox_active.json")
            configFile.writeText(jsonConfig.toString(2))

            FileLog.d("SingBoxManager: starting sing-box on port $activePort with config:\n$jsonConfig")

            val pb = ProcessBuilder(binaryPath, "run", "-c", configFile.absolutePath)
            pb.directory(context.filesDir)
            pb.redirectErrorStream(true)
            val proc = pb.start()
            process = proc

            // Read output in background thread for debugging
            Thread({
                try {
                    proc.inputStream.bufferedReader().useLines { lines ->
                        lines.forEach { line ->
                            FileLog.d("SingBox: $line")
                        }
                    }
                } catch (ignored: Exception) {}
            }, "SingBoxLogger").start()

            isRunning = true
            currentVlessUri = vlessUri
            return true
        } catch (t: Throwable) {
            FileLog.e("SingBoxManager: failed to start sing-box", t)
            stop()
            return false
        }
    }

    @Synchronized
    fun stop() {
        if (!isRunning && process == null) return
        try {
            FileLog.d("SingBoxManager: stopping sing-box...")
            process?.destroy()
            process = null
            isRunning = false
            currentVlessUri = null
        } catch (t: Throwable) {
            FileLog.e("SingBoxManager: failed to stop sing-box", t)
        }
    }

    private fun getBinaryPath(context: Context): String? {
        val nativeDir = context.applicationInfo.nativeLibraryDir
        val nativeLib = File(nativeDir, "libsingbox.so")
        if (nativeLib.exists()) {
            try {
                nativeLib.setExecutable(true, false)
            } catch (ignored: Throwable) {}
            return nativeLib.absolutePath
        }
        val target = File(context.filesDir, "libsingbox.so")
        if (!target.exists() || (nativeLib.exists() && target.length() != nativeLib.length())) {
            try {
                if (nativeLib.exists()) {
                    nativeLib.copyTo(target, overwrite = true)
                    target.setExecutable(true, false)
                }
            } catch (t: Throwable) {
                FileLog.e("SingBoxManager: copy binary failed", t)
            }
        }
        return if (target.exists()) target.absolutePath else null
    }

    private fun findAvailablePort(preferredPort: Int): Int {
        try {
            ServerSocket(preferredPort).use { return preferredPort }
        } catch (ignored: Exception) {}
        try {
            ServerSocket(0).use { socket -> return socket.localPort }
        } catch (ignored: Exception) {}
        return preferredPort
    }

    fun buildConfigJson(config: VlessConfig, localSocksPort: Int): JSONObject {
        val root = JSONObject()

        val log = JSONObject().apply {
            put("level", "warn")
        }
        root.put("log", log)

        val dns = JSONObject().apply {
            put("strategy", "ipv4_only")
            val servers = JSONArray().apply {
                put(JSONObject().apply {
                    put("tag", "dns-remote")
                    put("type", "udp")
                    put("server", "8.8.8.8")
                })
                put(JSONObject().apply {
                    put("tag", "dns-backup")
                    put("type", "udp")
                    put("server", "1.1.1.1")
                })
            }
            put("servers", servers)
        }
        root.put("dns", dns)

        val inbounds = JSONArray().apply {
            put(JSONObject().apply {
                put("type", "socks")
                put("tag", "socks-in")
                put("listen", "127.0.0.1")
                put("listen_port", localSocksPort)
            })
        }
        root.put("inbounds", inbounds)

        val outbound = JSONObject().apply {
            put("type", config.protocol)
            put("tag", "proxy")
            put("server", config.server)
            put("server_port", config.port)
            if (config.protocol == "trojan") {
                put("password", config.uuid)
            } else {
                put("uuid", config.uuid)
            }

            if (config.flow.isNotEmpty()) {
                put("flow", config.flow)
            }

            if (config.security == "reality" || config.security == "tls") {
                val tls = JSONObject().apply {
                    put("enabled", true)
                    if (config.sni.isNotEmpty()) {
                        put("server_name", config.sni)
                    }

                    if (config.alpn.isNotEmpty()) {
                        val alpnArray = JSONArray()
                        for (a in config.alpn) {
                            alpnArray.put(a)
                        }
                        put("alpn", alpnArray)
                    }

                    if (config.security == "reality") {
                        val reality = JSONObject().apply {
                            put("enabled", true)
                            if (config.pbk.isNotEmpty()) {
                                put("public_key", config.pbk)
                            }
                            if (config.sid.isNotEmpty()) {
                                put("short_id", config.sid)
                            }
                        }
                        put("reality", reality)

                        val utls = JSONObject().apply {
                            put("enabled", true)
                            put("fingerprint", if (config.fp.isNotEmpty()) config.fp else "chrome")
                        }
                        put("utls", utls)
                    } else if (config.fp.isNotEmpty()) {
                        val utls = JSONObject().apply {
                            put("enabled", true)
                            put("fingerprint", config.fp)
                        }
                        put("utls", utls)
                    }
                }
                put("tls", tls)
            }

            if (config.transportType == "ws") {
                val transport = JSONObject().apply {
                    put("type", "ws")
                    if (config.path.isNotEmpty()) {
                        put("path", config.path)
                    }
                    if (config.host.isNotEmpty()) {
                        val headers = JSONObject().apply {
                            put("Host", config.host)
                        }
                        put("headers", headers)
                    }
                }
                put("transport", transport)
            } else if (config.transportType == "httpupgrade" || config.transportType == "upgrade") {
                val transport = JSONObject().apply {
                    put("type", "httpupgrade")
                    if (config.path.isNotEmpty()) {
                        put("path", config.path)
                    }
                    if (config.host.isNotEmpty()) {
                        put("host", config.host)
                    }
                }
                put("transport", transport)
            } else if (config.transportType == "xhttp" || config.transportType == "http") {
                val transport = JSONObject().apply {
                    put("type", "http")
                    if (config.path.isNotEmpty()) {
                        put("path", config.path)
                    }
                    if (config.host.isNotEmpty()) {
                        val hostArr = JSONArray()
                        hostArr.put(config.host)
                        put("host", hostArr)
                    }
                }
                put("transport", transport)
            } else if (config.transportType == "grpc") {
                val transport = JSONObject().apply {
                    put("type", "grpc")
                    if (config.serviceName.isNotEmpty()) {
                        put("service_name", config.serviceName)
                    }
                }
                put("transport", transport)
            }
        }

        val directOutbound = JSONObject().apply {
            put("type", "direct")
            put("tag", "direct")
        }

        val outbounds = JSONArray().apply {
            put(outbound)
            put(directOutbound)
        }
        root.put("outbounds", outbounds)

        val route = JSONObject().apply {
            val rules = JSONArray().apply {
                put(JSONObject().apply {
                    put("action", "hijack-dns")
                    put("protocol", "dns")
                })
            }
            put("rules", rules)
            put("default_domain_resolver", "dns-remote")
        }
        root.put("route", route)

        return root
    }
}
