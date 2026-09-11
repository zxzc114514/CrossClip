package com.crossclip.app.network

import android.util.Log
import com.crossclip.app.util.DebugLogger
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.OutputStream
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 手机端本地轻量级 HTTP 服务端 (监听端口 18237)
 * 采用原生 ServerSocket + 线程池实现，体积极小，零第三方依赖。
 * 电脑端复制文本时，直接向手机 http://<手机IP>:18237/sync 发起 HTTP POST 推送。
 */
class LocalHttpServer(
    private val port: Int = 18237,
    private val onSyncReceived: (encrypted: String, hash: String, senderId: String) -> Unit
) {
    private val TAG = "LocalHttpServer"
    private var serverSocket: ServerSocket? = null
    private val isRunning = AtomicBoolean(false)
    private val threadPool = Executors.newCachedThreadPool()
    private var acceptThread: Thread? = null

    fun start() {
        if (isRunning.getAndSet(true)) {
            Log.d(TAG, "本地 HTTP 服务已在运行中")
            return
        }

        acceptThread = Thread {
            try {
                serverSocket = ServerSocket(port).apply {
                    reuseAddress = true
                }
                Log.i(TAG, "本地微型 HTTP 服务已启动，监听端口: $port")
                DebugLogger.log("LOCAL_HTTP", "本地微型 HTTP 服务已启动，监听端口: $port")

                while (isRunning.get()) {
                    val clientSocket = try {
                        serverSocket?.accept() ?: break
                    } catch (e: Exception) {
                        if (!isRunning.get()) break
                        Log.w(TAG, "accept 异常: ${e.message}")
                        continue
                    }
                    threadPool.execute {
                        handleClient(clientSocket)
                    }
                }
            } catch (e: Exception) {
                if (isRunning.get()) {
                    Log.e(TAG, "本地 HTTP 服务异常退出: ${e.message}")
                    DebugLogger.log("LOCAL_HTTP", "本地 HTTP 服务异常退出: ${e.message}", e)
                }
            }
        }.apply {
            isDaemon = true
            name = "CrossClip-LocalHttpServer"
            start()
        }
    }

    private fun handleClient(socket: Socket) {
        val remoteIp = socket.inetAddress?.hostAddress ?: "未知"
        try {
            socket.soTimeout = 3000
            val reader = BufferedReader(InputStreamReader(socket.getInputStream(), Charsets.UTF_8))
            val outputStream = socket.getOutputStream()

            // 1. 读取请求行
            val requestLine = reader.readLine() ?: return
            val parts = requestLine.split(" ")
            if (parts.size < 2) return
            val method = parts[0].uppercase()
            val path = parts[1]

            // 2. 读取 Header
            var contentLength = 0
            var line: String?
            while (reader.readLine().also { line = it } != null) {
                if (line.isNullOrEmpty()) break
                val headerLower = line!!.lowercase()
                if (headerLower.startsWith("content-length:")) {
                    contentLength = headerLower.substring("content-length:".length).trim().toIntOrNull() ?: 0
                }
            }

            // 3. 读取 Body
            val bodyBuilder = StringBuilder()
            if (contentLength > 0) {
                val buffer = CharArray(1024)
                var remaining = contentLength
                while (remaining > 0) {
                    val read = reader.read(buffer, 0, minOf(remaining, buffer.size))
                    if (read == -1) break
                    bodyBuilder.append(buffer, 0, read)
                    remaining -= read
                }
            }
            val body = bodyBuilder.toString()

            // 4. 路由处理
            when {
                path == "/sync" && method == "POST" -> {
                    try {
                        val json = JSONObject(body)
                        val enc = json.optString("encrypted", "")
                        val hash = json.optString("hash", "")
                        val sender = json.optString("sender_id", "电脑端")
                        if (enc.isNotEmpty()) {
                            DebugLogger.log("LOCAL_HTTP", "收到电脑端对等 HTTP POST 推送: remote=$remoteIp, hash=$hash, 加密包长度=${enc.length}")
                            onSyncReceived(enc, hash, sender)
                            sendResponse(outputStream, 200, "{\"status\":\"ok\"}")
                        } else {
                            sendResponse(outputStream, 400, "{\"error\":\"missing_encrypted_payload\"}")
                        }
                    } catch (e: Exception) {
                        DebugLogger.log("LOCAL_HTTP", "解析电脑端推送 JSON 异常: ${e.message}")
                        sendResponse(outputStream, 400, "{\"error\":\"invalid_json\"}")
                    }
                }
                path == "/ping" -> {
                    sendResponse(outputStream, 200, "{\"status\":\"pong\"}")
                }
                else -> {
                    sendResponse(outputStream, 404, "{\"error\":\"not_found\"}")
                }
            }
        } catch (e: Exception) {
            DebugLogger.log("LOCAL_HTTP", "处理来自 $remoteIp 的 HTTP 请求异常: ${e.message}")
        } finally {
            try {
                socket.close()
            } catch (_: Exception) {}
        }
    }

    private fun sendResponse(out: OutputStream, code: Int, jsonBody: String) {
        val statusText = if (code == 200) "OK" else if (code == 400) "Bad Request" else "Not Found"
        val bodyBytes = jsonBody.toByteArray(Charsets.UTF_8)
        val header = "HTTP/1.1 $code $statusText\r\n" +
                "Content-Type: application/json; charset=utf-8\r\n" +
                "Content-Length: ${bodyBytes.size}\r\n" +
                "Connection: close\r\n" +
                "Access-Control-Allow-Origin: *\r\n\r\n"
        out.write(header.toByteArray(Charsets.UTF_8))
        out.write(bodyBytes)
        out.flush()
    }

    fun stop() {
        isRunning.set(false)
        try {
            serverSocket?.close()
        } catch (_: Exception) {}
        serverSocket = null
        acceptThread?.interrupt()
        acceptThread = null
        DebugLogger.log("LOCAL_HTTP", "已停止本地微型 HTTP 服务")
    }
}
