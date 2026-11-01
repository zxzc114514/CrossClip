package com.crossclip.app.network

import android.util.Log
import com.crossclip.app.util.DebugLogger
import okhttp3.Call
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.util.concurrent.TimeUnit

class SseClient(
    private val onMessageReceived: (encrypted: String, hash: String, senderId: String) -> Unit,
    private val onConnectionChanged: (Boolean) -> Unit,
    private val onAuthRejected: (() -> Unit)? = null
) {
    private val TAG = "CrossClipSSE"
    @Volatile
    private var isClosed = false
    private var thread: Thread? = null
    @Volatile
    private var currentCall: Call? = null

    // 电脑端每 15 秒发送一次 ": keepalive" 心跳，读超时设为 25 秒可立即检测出手机离开局域网导致的 TCP 半开僵死连接
    private val client = OkHttpClient.Builder()
        .connectTimeout(3, TimeUnit.SECONDS)
        .readTimeout(25, TimeUnit.SECONDS)
        .connectionPool(okhttp3.ConnectionPool(0, 1, TimeUnit.SECONDS))
        .retryOnConnectionFailure(true)
        .build()

    fun connect(
        url: String,
        pinCode: String,
        deviceId: String = "android_phone",
        deviceName: String = "安卓手机"
    ) {
        disconnect(notifyChange = false)
        isClosed = false

        thread = Thread {
            Log.i(TAG, "正在建立 SSE 剪贴板加密长连接: $url")
            DebugLogger.log("SSE", "开始发起 SSE 加密长连接: $url")

            while (!isClosed) {
                var call: Call? = null
                try {
                    val authCipher = HttpUploader.buildAuthCipher(pinCode, deviceId, deviceName, 18237)
                    val request = Request.Builder()
                        .url(url)
                        .header("Accept", "text/event-stream")
                        .header("Cache-Control", "no-cache")
                        .header("X-Auth-Cipher", authCipher)
                        .build()

                    call = client.newCall(request)
                    currentCall = call
                    val response = call.execute()
                    if (response.isSuccessful) {
                        currentCall = null
                        onConnectionChanged(true)
                        DebugLogger.log("SSE", "SSE 长连接建立成功 (状态码: ${response.code})")
                        val source = response.body?.source()
                        if (source != null) {
                            while (!isClosed && !source.exhausted()) {
                                val line = source.readUtf8Line() ?: break
                                if (line.startsWith("data: ")) {
                                    val data = line.substring(6).trim()
                                    if (data.isNotEmpty()) {
                                        try {
                                            val json = JSONObject(data)
                                            val enc = json.optString("encrypted", "")
                                            val hash = json.optString("hash", "")
                                            val sender = json.optString("sender_id", "电脑端")
                                            if (enc.isNotEmpty()) {
                                                DebugLogger.log("SSE", "收到电脑端下发数据: hash=$hash, sender=$sender, 加密包长度=${enc.length}")
                                                onMessageReceived(enc, hash, sender)
                                            }
                                        } catch (e: Exception) {
                                            DebugLogger.log("SSE", "解析 data JSON 异常: ${e.message}")
                                        }
                                    }
                                } else if (line.startsWith(": ping") || line.startsWith(": keepalive")) {
                                    DebugLogger.log("SSE_PING", "收到电脑端保活心跳帧: $line")
                                }
                            }
                            DebugLogger.log("SSE", "电脑端 SSE 数据流已断开或退出")
                            if (!isClosed) {
                                onConnectionChanged(false)
                            }
                        }
                    } else if (response.code == 403) {
                        Log.w(TAG, "SSE 连接被拒绝 (403)，PIN 码不匹配")
                        DebugLogger.log("SSE", "SSE 连接被拒绝 (403)，PIN 码不匹配")
                        response.close()
                        onAuthRejected?.invoke()
                        break
                    } else {
                        DebugLogger.log("SSE", "SSE 建立失败，HTTP 状态码: ${response.code}")
                        if (!isClosed) {
                            onConnectionChanged(false)
                        }
                    }
                    response.close()
                    if (currentCall === call) {
                        currentCall = null
                    }
                } catch (e: Exception) {
                    if (currentCall === call) {
                        currentCall = null
                    }
                    if (!isClosed) {
                        Log.w(TAG, "SSE 连接异常断开: ${e.message}，将在 1.5 秒后自动重连...")
                        DebugLogger.log("SSE", "SSE 异常断开: ${e.javaClass.simpleName}: ${e.message}，1.5 秒后重试")
                        onConnectionChanged(false)
                        try {
                            Thread.sleep(1500)
                        } catch (_: InterruptedException) {
                            break
                        }
                    }
                }
            }
            DebugLogger.log("SSE", "SSE 接收循环退出 (isClosed=$isClosed)")
        }.apply {
            isDaemon = true
            name = "CrossClip-SSE"
            start()
        }
    }

    fun disconnect(notifyChange: Boolean = true) {
        isClosed = true
        currentCall?.cancel()
        currentCall = null
        thread?.interrupt()
        thread = null
        if (notifyChange) {
            onConnectionChanged(false)
        }
        DebugLogger.log("SSE", "断开 SSE 长连接")
    }
}
