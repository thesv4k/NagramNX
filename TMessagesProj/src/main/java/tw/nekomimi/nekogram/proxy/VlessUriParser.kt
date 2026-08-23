package tw.nekomimi.nekogram.proxy

import android.net.Uri
import org.telegram.messenger.FileLog
import java.net.URLDecoder

data class VlessConfig(
    val protocol: String = "vless",
    val uuid: String,
    val server: String,
    val port: Int,
    val flow: String = "",
    val security: String = "none",
    val sni: String = "",
    val pbk: String = "",
    val sid: String = "",
    val spx: String = "",
    val fp: String = "chrome",
    val alpn: List<String> = emptyList(),
    val transportType: String = "tcp",
    val path: String = "",
    val host: String = "",
    val serviceName: String = "",
    val mode: String = "",
    val name: String = "VLESS"
)

object VlessUriParser {
    fun isVlessUri(url: String?): Boolean {
        if (url == null) return false
        val trimmed = url.trim()
        return trimmed.startsWith("vless://", ignoreCase = true) ||
               trimmed.startsWith("trojan://", ignoreCase = true) ||
               trimmed.startsWith("ss://", ignoreCase = true) ||
               trimmed.startsWith("vmess://", ignoreCase = true)
    }

    fun parseVless(url: String): VlessConfig? {
        try {
            val trimmed = url.trim()
            val uri = Uri.parse(trimmed)
            val scheme = uri.scheme?.lowercase() ?: return null

            if (scheme == "trojan") {
                val password = uri.userInfo ?: return null
                val server = uri.host ?: return null
                val port = if (uri.port > 0) uri.port else 443
                val sni = uri.getQueryParameter("sni") ?: uri.getQueryParameter("peer") ?: server
                val alpnStr = uri.getQueryParameter("alpn")
                val alpnList = alpnStr?.split(",")?.map { it.trim() }?.filter { it.isNotEmpty() } ?: emptyList()
                val fragment = uri.fragment?.let {
                    try { URLDecoder.decode(it, "UTF-8") } catch (e: Exception) { it }
                } ?: "Trojan"
                return VlessConfig(
                    protocol = "trojan",
                    uuid = password,
                    server = server,
                    port = port,
                    security = "tls",
                    sni = sni,
                    alpn = alpnList,
                    name = fragment
                )
            }

            if (scheme != "vless") return null

            val uuid = uri.userInfo ?: return null
            val server = uri.host ?: return null
            val port = if (uri.port > 0) uri.port else 443

            val flow = uri.getQueryParameter("flow") ?: ""
            val security = uri.getQueryParameter("security")?.lowercase() ?: "none"
            val sni = uri.getQueryParameter("sni") ?: uri.getQueryParameter("host") ?: server
            val pbk = uri.getQueryParameter("pbk") ?: ""
            val sid = uri.getQueryParameter("sid") ?: ""
            val spx = uri.getQueryParameter("spx") ?: ""
            val fp = uri.getQueryParameter("fp") ?: "chrome"
            val type = uri.getQueryParameter("type")?.lowercase() ?: "tcp"
            val rawPath = uri.getQueryParameter("path") ?: ""
            val path = try { URLDecoder.decode(rawPath, "UTF-8") } catch (e: Exception) { rawPath }
            val host = uri.getQueryParameter("host") ?: ""
            val serviceName = uri.getQueryParameter("serviceName") ?: uri.getQueryParameter("service_name") ?: ""
            val mode = uri.getQueryParameter("mode") ?: ""

            val rawAlpn = uri.getQueryParameter("alpn")
            val alpnList = if (!rawAlpn.isNullOrEmpty()) {
                val decoded = try { URLDecoder.decode(rawAlpn, "UTF-8") } catch (e: Exception) { rawAlpn }
                decoded.split(",").map { it.trim() }.filter { it.isNotEmpty() }
            } else {
                emptyList()
            }

            val fragment = uri.fragment?.let {
                try { URLDecoder.decode(it, "UTF-8") } catch (e: Exception) { it }
            } ?: "VLESS"

            return VlessConfig(
                protocol = "vless",
                uuid = uuid,
                server = server,
                port = port,
                flow = flow,
                security = security,
                sni = sni,
                pbk = pbk,
                sid = sid,
                spx = spx,
                fp = fp,
                alpn = alpnList,
                transportType = type,
                path = path,
                host = host,
                serviceName = serviceName,
                mode = mode,
                name = fragment
            )
        } catch (t: Throwable) {
            FileLog.e("VlessUriParser: parse error", t)
            return null
        }
    }
}
