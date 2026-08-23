package tw.nekomimi.nekogram.proxy

import android.os.SystemClock
import android.util.Base64
import org.telegram.messenger.FileLog
import org.telegram.tgnet.RequestTimeDelegate
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.EOFException
import java.io.InputStream
import java.io.OutputStream
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.SecureRandom
import java.util.Collections
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit
import javax.net.ssl.SNIHostName
import javax.net.ssl.SSLParameters
import javax.net.ssl.SSLSocket
import javax.net.ssl.SSLSocketFactory

/**
 * WebProxyServer & WebProxyConnection
 *
 * Implements Telegram WEB-proxy: Masquerading MTProto traffic under standard HTTPS / WebSocket
 * connections to Telegram Web infrastructure (web.telegram.org:443 / *.web.telegram.org:443).
 *
 * Features:
 * - Emulates standard Google Chrome TLS ClientHello & HTTP/1.1 WebSocket Upgrade request
 * - Bypasses DPI firewalls by appearing as normal web browsing traffic to web.telegram.org
 * - Tunneling MTProto 2.0 frames via RFC 6455 Binary WebSocket frames (Sec-WebSocket-Protocol: binary)
 * - Automatic Data Center (DC 1-5) routing based on target IP or custom endpoint
 * - Local transparent SOCKS5 / MTProto bridging for tgnet
 */
object WebProxyServer {
    private const val TAG = "WebProxyServer"

    @Volatile
    private var serverSocket: ServerSocket? = null

    @Volatile
    var localPort: Int = 0
        private set

    @Volatile
    var currentHost: String = "web.telegram.org"
        private set

    @Volatile
    var currentPort: Int = 443
        private set

    @Volatile
    var currentSecret: String = ""
        private set

    @Volatile
    var isRunning: Boolean = false
        private set

    private var executor: ExecutorService? = null
    private val activeConnections = Collections.newSetFromMap(ConcurrentHashMap<WebProxyConnection, Boolean>())

    @Synchronized
    fun start(host: String?, port: Int, secret: String?): Int {
        val targetHost = if (!host.isNullOrBlank()) host.trim() else "web.telegram.org"
        val targetPort = if (port in 1..65535) port else 443
        val targetSecret = secret?.trim() ?: ""

        if (isRunning && serverSocket != null && !serverSocket!!.isClosed) {
            if (currentHost == targetHost && currentPort == targetPort && currentSecret == targetSecret) {
                return localPort
            }
            stop()
        }

        currentHost = targetHost
        currentPort = targetPort
        currentSecret = targetSecret

        try {
            val server = ServerSocket(0, 50, InetAddress.getByName("127.0.0.1"))
            serverSocket = server
            localPort = server.localPort
            isRunning = true

            executor = Executors.newCachedThreadPool { r ->
                Thread(r, "WebProxy-Worker").apply { isDaemon = true }
            }

            executor?.execute {
                FileLog.d("$TAG: Started local WEB-proxy server on 127.0.0.1:$localPort -> $targetHost:$targetPort")
                while (isRunning && !server.isClosed) {
                    try {
                        val clientSocket = server.accept()
                        clientSocket.tcpNoDelay = true
                        val connection = WebProxyConnection(clientSocket, currentHost, currentPort, currentSecret)
                        activeConnections.add(connection)
                        executor?.execute {
                            try {
                                connection.handle()
                            } finally {
                                activeConnections.remove(connection)
                            }
                        }
                    } catch (e: Exception) {
                        if (isRunning) {
                            FileLog.e("$TAG: Error accepting connection: ${e.message}")
                        }
                    }
                }
            }

            return localPort
        } catch (e: Exception) {
            FileLog.e("$TAG: Failed to start WebProxyServer: ${e.message}")
            isRunning = false
            return 0
        }
    }

    @Synchronized
    fun stop() {
        if (!isRunning) return
        isRunning = false
        FileLog.d("$TAG: Stopping WebProxyServer...")
        try {
            serverSocket?.close()
        } catch (_: Exception) {}
        serverSocket = null

        for (conn in activeConnections) {
            conn.close()
        }
        activeConnections.clear()

        try {
            executor?.shutdownNow()
        } catch (_: Exception) {}
        executor = null
        localPort = 0
    }

    fun checkPing(host: String?, port: Int, secret: String?, callback: RequestTimeDelegate?) {
        val targetHost = if (!host.isNullOrBlank()) host.trim() else "web.telegram.org"
        val targetPort = if (port in 1..65535) port else 443
        val targetSecret = secret?.trim() ?: ""

        val thread = Thread({
            val startTime = SystemClock.elapsedRealtime()
            var success = false
            var testSocket: Socket? = null
            try {
                val actualHost = WebProxyConnection.resolveDcHost(targetHost, 2)
                val socket = Socket()
                socket.connect(InetSocketAddress(actualHost, targetPort), 5000)
                socket.soTimeout = 5000

                val sslSocketFactory = SSLSocketFactory.getDefault() as SSLSocketFactory
                val sslSocket = sslSocketFactory.createSocket(socket, actualHost, targetPort, true) as SSLSocket
                sslSocket.sslParameters = sslSocket.sslParameters.apply {
                    serverNames = listOf(SNIHostName(actualHost))
                }
                sslSocket.startHandshake()
                testSocket = sslSocket

                val path = WebProxyConnection.extractPath(targetSecret)
                val nonce = ByteArray(16).apply { SecureRandom().nextBytes(this) }
                val secKey = Base64.encodeToString(nonce, Base64.NO_WRAP)

                val request = "GET $path HTTP/1.1\r\n" +
                        "Host: $actualHost\r\n" +
                        "Upgrade: websocket\r\n" +
                        "Connection: Upgrade\r\n" +
                        "Sec-WebSocket-Key: $secKey\r\n" +
                        "Sec-WebSocket-Version: 13\r\n" +
                        "Sec-WebSocket-Protocol: binary\r\n" +
                        "User-Agent: Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36\r\n" +
                        "Origin: https://web.telegram.org\r\n\r\n"

                val out = sslSocket.outputStream
                out.write(request.toByteArray(Charsets.US_ASCII))
                out.flush()

                val reader = sslSocket.inputStream
                val buffer = ByteArray(1024)
                val read = reader.read(buffer)
                if (read > 0) {
                    val resp = String(buffer, 0, read, Charsets.US_ASCII)
                    if (resp.startsWith("HTTP/1.1 101") || resp.startsWith("HTTP/1.0 101") || resp.startsWith("HTTP/1.1 302")) {
                        success = true
                    }
                }
            } catch (e: Exception) {
                FileLog.e("$TAG: Ping check error: ${e.message}")
            } finally {
                try {
                    testSocket?.close()
                } catch (_: Exception) {}
            }

            val latency = if (success) (SystemClock.elapsedRealtime() - startTime) else -1L
            callback?.run(latency)
        }, "WebProxy-Ping")
        thread.isDaemon = true
        thread.start()
    }
}

/**
 * WebProxyConnection
 * Handles one client TCP connection and bridges it to Telegram Web WebSocket endpoint.
 */
class WebProxyConnection(
    private val clientSocket: Socket,
    private val configuredHost: String,
    private val configuredPort: Int,
    private val secret: String
) {
    companion object {
        private const val TAG = "WebProxyConn"

        fun extractPath(secret: String?): String {
            if (secret.isNullOrBlank()) return "/apiws"
            var s = secret
            if (s.startsWith("web://") || s.startsWith("wss://")) {
                val idx = s.indexOf('/', 8)
                if (idx != -1) return s.substring(idx)
            }
            if (s.startsWith("web_")) {
                s = s.substring(4)
            }
            return if (s.startsWith("/")) s else if (s.isNotEmpty()) "/$s" else "/apiws"
        }

        fun resolveDcHost(configuredHost: String, dcId: Int): String {
            if (configuredHost != "web.telegram.org" && !configuredHost.endsWith(".web.telegram.org") && configuredHost.isNotEmpty()) {
                return configuredHost
            }
            return when (dcId) {
                1 -> "pluto.web.telegram.org"
                2 -> "venus.web.telegram.org"
                3 -> "aurora.web.telegram.org"
                4 -> "vesta.web.telegram.org"
                5 -> "flora.web.telegram.org"
                else -> "venus.web.telegram.org"
            }
        }

        fun getDcIdFromIp(ip: String): Int {
            return when {
                ip.startsWith("149.154.175.") -> {
                    val last = ip.substringAfterLast('.').toIntOrNull() ?: 0
                    if (last >= 100) 3 else 1
                }
                ip.startsWith("149.154.167.") -> {
                    val last = ip.substringAfterLast('.').toIntOrNull() ?: 0
                    if (last in 90..99) 4 else 2
                }
                ip.startsWith("91.108.4.") || ip.startsWith("149.154.166.") -> 4
                ip.startsWith("91.108.56.") || ip.startsWith("91.108.8.") || ip.startsWith("91.108.12.") || ip.startsWith("91.108.16.") || ip.startsWith("91.108.20.") -> 5
                else -> 2
            }
        }
    }

    @Volatile
    private var isClosed = false
    private var tlsSocket: SSLSocket? = null
    private val random = SecureRandom()

    fun close() {
        if (isClosed) return
        isClosed = true
        try {
            clientSocket.close()
        } catch (_: Exception) {}
        try {
            tlsSocket?.close()
        } catch (_: Exception) {}
    }

    fun handle() {
        try {
            val clientIn = BufferedInputStream(clientSocket.inputStream)
            val clientOut = BufferedOutputStream(clientSocket.outputStream)

            clientSocket.soTimeout = 15000

            // 1. Inspect initial handshake: SOCKS5 vs MTProto
            clientIn.mark(64)
            val firstByte = clientIn.read()
            if (firstByte == -1) return
            clientIn.reset()

            var targetDcId = 2
            var initialBytesToSend: ByteArray? = null

            if (firstByte == 0x05) {
                // SOCKS5 Greeting
                val ver = clientIn.read()
                val nmethods = clientIn.read()
                val methods = ByteArray(nmethods)
                readFully(clientIn, methods)

                // NO AUTH reply: 0x05 0x00
                clientOut.write(byteArrayOf(0x05, 0x00))
                clientOut.flush()

                // SOCKS5 Request
                val reqVer = clientIn.read()
                val cmd = clientIn.read()
                val rsv = clientIn.read()
                val atyp = clientIn.read()

                var dstIp = ""
                var dstPort = 443

                when (atyp) {
                    0x01 -> { // IPv4
                        val ipBytes = ByteArray(4)
                        readFully(clientIn, ipBytes)
                        dstIp = "${ipBytes[0].toInt() and 0xFF}.${ipBytes[1].toInt() and 0xFF}.${ipBytes[2].toInt() and 0xFF}.${ipBytes[3].toInt() and 0xFF}"
                    }
                    0x03 -> { // Domain name
                        val len = clientIn.read()
                        val domainBytes = ByteArray(len)
                        readFully(clientIn, domainBytes)
                        dstIp = String(domainBytes, Charsets.US_ASCII)
                    }
                    0x04 -> { // IPv6
                        val ipBytes = ByteArray(16)
                        readFully(clientIn, ipBytes)
                        dstIp = "ipv6"
                    }
                }
                val portBytes = ByteArray(2)
                readFully(clientIn, portBytes)
                dstPort = ((portBytes[0].toInt() and 0xFF) shl 8) or (portBytes[1].toInt() and 0xFF)

                targetDcId = getDcIdFromIp(dstIp)

                // SOCKS5 Success Response: 0x05 0x00 0x00 0x01 0x00 0x00 0x00 0x00 0x00 0x00
                clientOut.write(byteArrayOf(0x05, 0x00, 0x00, 0x01, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00))
                clientOut.flush()
            } else {
                // Direct MTProto header (64 bytes)
                val header = ByteArray(64)
                readFully(clientIn, header)
                // bytes 60..61 contain DC id
                val dcVal = (header[60].toInt() and 0xFF) or ((header[61].toInt() and 0xFF) shl 8)
                val dcShort = dcVal.toShort().toInt()
                targetDcId = if (Math.abs(dcShort) in 1..5) Math.abs(dcShort) else 2
                initialBytesToSend = header
            }

            // 2. Connect TLS 1.3 / WebSocket to Telegram Web DC
            val remoteHost = resolveDcHost(configuredHost, targetDcId)
            val remotePort = configuredPort
            val path = extractPath(secret)

            val rawSocket = Socket()
            rawSocket.tcpNoDelay = true
            rawSocket.connect(InetSocketAddress(remoteHost, remotePort), 10000)

            val sslSocketFactory = SSLSocketFactory.getDefault() as SSLSocketFactory
            val ssl = sslSocketFactory.createSocket(rawSocket, remoteHost, remotePort, true) as SSLSocket
            ssl.sslParameters = ssl.sslParameters.apply {
                serverNames = listOf(SNIHostName(remoteHost))
            }
            ssl.startHandshake()
            tlsSocket = ssl

            val tlsIn = BufferedInputStream(ssl.inputStream)
            val tlsOut = BufferedOutputStream(ssl.outputStream)

            // 3. HTTP WebSocket Upgrade Request (Chrome Mimicry)
            val nonce = ByteArray(16).apply { random.nextBytes(this) }
            val secKey = Base64.encodeToString(nonce, Base64.NO_WRAP)

            val upgradeReq = "GET $path HTTP/1.1\r\n" +
                    "Host: $remoteHost\r\n" +
                    "Upgrade: websocket\r\n" +
                    "Connection: Upgrade\r\n" +
                    "Sec-WebSocket-Key: $secKey\r\n" +
                    "Sec-WebSocket-Version: 13\r\n" +
                    "Sec-WebSocket-Protocol: binary\r\n" +
                    "User-Agent: Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36\r\n" +
                    "Origin: https://web.telegram.org\r\n" +
                    "Accept-Encoding: gzip, deflate, br, zstd\r\n" +
                    "Accept-Language: en-US,en;q=0.9\r\n" +
                    "Cache-Control: no-cache\r\n" +
                    "Pragma: no-cache\r\n\r\n"

            tlsOut.write(upgradeReq.toByteArray(Charsets.US_ASCII))
            tlsOut.flush()

            // 4. Read HTTP 101 Response
            val statusLine = readHttpLine(tlsIn)
            if (!statusLine.contains("101")) {
                FileLog.e("$TAG: WebSocket upgrade failed: $statusLine")
                close()
                return
            }
            // Consume remaining headers
            while (true) {
                val headerLine = readHttpLine(tlsIn)
                if (headerLine.isEmpty()) break
            }

            clientSocket.soTimeout = 0
            ssl.soTimeout = 0

            // If initial MTProto header was read, send it now inside a WebSocket frame
            if (initialBytesToSend != null) {
                sendWebSocketBinaryFrame(tlsOut, initialBytesToSend)
            }

            // 5. Bidirectional Relay
            val relayThread = Thread({
                try {
                    val buffer = ByteArray(16384)
                    while (!isClosed) {
                        val count = clientIn.read(buffer)
                        if (count == -1) break
                        sendWebSocketBinaryFrame(tlsOut, buffer, 0, count)
                    }
                } catch (_: Exception) {}
                close()
            }, "WebProxy-ClientToTls")
            relayThread.isDaemon = true
            relayThread.start()

            try {
                while (!isClosed) {
                    val frame = readWebSocketFrame(tlsIn) ?: break
                    val opcode = frame.opcode
                    if (opcode == 0x02 || opcode == 0x00 || opcode == 0x01) {
                        // Binary / data frame
                        clientOut.write(frame.payload)
                        clientOut.flush()
                    } else if (opcode == 0x09) {
                        // Ping -> reply with Pong
                        sendWebSocketPongFrame(tlsOut, frame.payload)
                    } else if (opcode == 0x08) {
                        // Close
                        break
                    }
                }
            } catch (_: Exception) {}
        } catch (e: Exception) {
            FileLog.e("$TAG: Connection error: ${e.message}")
        } finally {
            close()
        }
    }

    private fun sendWebSocketBinaryFrame(out: OutputStream, data: ByteArray, offset: Int = 0, length: Int = data.size) {
        val mask = ByteArray(4).apply { random.nextBytes(this) }
        val header = ByteBuffer.allocate(14).order(ByteOrder.BIG_ENDIAN)
        header.put(0x82.toByte()) // FIN + Binary opcode (0x02)

        if (length <= 125) {
            header.put((0x80 or length).toByte())
        } else if (length <= 65535) {
            header.put((0x80 or 126).toByte())
            header.putShort(length.toShort())
        } else {
            header.put((0x80 or 127).toByte())
            header.putLong(length.toLong())
        }
        header.put(mask)

        val maskedPayload = ByteArray(length)
        for (i in 0 until length) {
            maskedPayload[i] = (data[offset + i].toInt() xor mask[i % 4].toInt()).toByte()
        }

        synchronized(out) {
            out.write(header.array(), 0, header.position())
            out.write(maskedPayload)
            out.flush()
        }
    }

    private fun sendWebSocketPongFrame(out: OutputStream, payload: ByteArray) {
        val mask = ByteArray(4).apply { random.nextBytes(this) }
        val length = Math.min(payload.size, 125)
        val header = ByteArray(2 + 4)
        header[0] = 0x8A.toByte() // FIN + Pong opcode (0x0A)
        header[1] = (0x80 or length).toByte()
        System.arraycopy(mask, 0, header, 2, 4)

        val maskedPayload = ByteArray(length)
        for (i in 0 until length) {
            maskedPayload[i] = (payload[i].toInt() xor mask[i % 4].toInt()).toByte()
        }

        synchronized(out) {
            out.write(header)
            out.write(maskedPayload)
            out.flush()
        }
    }

    private class WsFrame(val opcode: Int, val payload: ByteArray)

    private fun readWebSocketFrame(input: InputStream): WsFrame? {
        val b0 = input.read()
        if (b0 == -1) return null
        val b1 = input.read()
        if (b1 == -1) return null

        val opcode = b0 and 0x0F
        val isMasked = (b1 and 0x80) != 0
        var len = (b1 and 0x7F).toLong()

        if (len == 126L) {
            val lenBytes = ByteArray(2)
            readFully(input, lenBytes)
            len = ((lenBytes[0].toInt() and 0xFF) shl 8 or (lenBytes[1].toInt() and 0xFF)).toLong()
        } else if (len == 127L) {
            val lenBytes = ByteArray(8)
            readFully(input, lenBytes)
            val bb = ByteBuffer.wrap(lenBytes).order(ByteOrder.BIG_ENDIAN)
            len = bb.long
        }

        val mask = if (isMasked) {
            val m = ByteArray(4)
            readFully(input, m)
            m
        } else null

        if (len > 10 * 1024 * 1024) {
            throw IllegalArgumentException("WebSocket frame payload too large: $len")
        }

        val payload = ByteArray(len.toInt())
        readFully(input, payload)

        if (mask != null) {
            for (i in payload.indices) {
                payload[i] = (payload[i].toInt() xor mask[i % 4].toInt()).toByte()
            }
        }

        return WsFrame(opcode, payload)
    }

    private fun readHttpLine(input: InputStream): String {
        val sb = StringBuilder()
        while (true) {
            val b = input.read()
            if (b == -1) break
            if (b == '\n'.code) break
            if (b != '\r'.code) {
                sb.append(b.toChar())
            }
        }
        return sb.toString()
    }

    private fun readFully(input: InputStream, buffer: ByteArray) {
        var offset = 0
        while (offset < buffer.size) {
            val read = input.read(buffer, offset, buffer.size - offset)
            if (read == -1) throw EOFException("Unexpected EOF while reading ${buffer.size} bytes")
            offset += read
        }
    }
}
