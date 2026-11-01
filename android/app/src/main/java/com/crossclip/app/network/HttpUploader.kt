package com.crossclip.app.network

import android.util.Log
import com.crossclip.app.crypto.CryptoUtil
import com.crossclip.app.util.DebugLogger
import okhttp3.Call
import okhttp3.Callback
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.TimeUnit

object HttpUploader {
    private const val TAG = "CrossClipHttp"
    private val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()

    private val client = OkHttpClient.Builder()
        .connectTimeout(2, TimeUnit.SECONDS)
        .writeTimeout(2, TimeUnit.SECONDS)
        .readTimeout(2, TimeUnit.SECONDS)
        .connectionPool(okhttp3.ConnectionPool(0, 1, TimeUnit.SECONDS))
        .build()

    /**
     * 构建基于 PIN 派生密钥的 AES-256-GCM 密文挑战令牌 (带毫秒级时间戳防重放)
     */
    fun buildAuthCipher(
        pinCode: String,
        deviceId: String = "",
        deviceName: String = "",
        clientPort: Int = 18237
    ): String {
        val nowMs = System.currentTimeMillis()
        val innerPayload = JSONObject().apply {
            put("timestamp", nowMs)
            put("client_port", clientPort)
            put("device_id", deviceId)
            put("device_name", deviceName)
        }.toString()
        return CryptoUtil.encrypt(innerPayload, pinCode.trim())
    }

    /**
     * 验证 PIN 码与电脑连通性并注册手机端对等接收端口
     * @return success: 是否成功, statusCode: HTTP状态码 (200成功, 403密码错误, -1网络不可达), deviceName: 电脑名称, deviceId: 电脑设备指纹
     */
    fun verifyPin(
        pcIp: String,
        httpPort: Int = 18236,
        pinCode: String,
        deviceId: String = "",
        deviceName: String = "",
        clientPort: Int = 18237,
        onResult: (success: Boolean, statusCode: Int, deviceName: String?, deviceId: String?) -> Unit
    ) {
        if (pcIp.isBlank() || pinCode.isBlank()) {
            onResult(false, -1, null, null)
            return
        }
        val url = "http://$pcIp:$httpPort/auth"
        DebugLogger.log("HTTP", "发起 PIN 码密文挑战握手: $url (对等接收端口: $clientPort)")

        val authCipher = try {
            buildAuthCipher(pinCode, deviceId, deviceName, clientPort)
        } catch (e: Exception) {
            onResult(false, -1, null, null)
            return
        }

        val json = JSONObject().apply {
            put("auth_cipher", authCipher)
        }.toString()

        val requestBody = json.toRequestBody(JSON_MEDIA_TYPE)
        val request = Request.Builder().url(url).post(requestBody).build()

        client.newCall(request).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                Log.d(TAG, "连接电脑失败 ($url): ${e.message}")
                DebugLogger.log("HTTP", "连接电脑失败 ($url): ${e.javaClass.simpleName}: ${e.message}")
                onResult(false, -1, null, null)
            }

            override fun onResponse(call: Call, response: Response) {
                response.use {
                    DebugLogger.log("HTTP", "PIN 码验证响应状态码: ${response.code}")
                    if (response.code == 200) {
                        val body = response.body?.string() ?: ""
                        try {
                            val respJson = JSONObject(body)
                            val devName = respJson.optString("device_name", "Windows 电脑")
                            val retDevId = respJson.optString("device_id", "")
                            DebugLogger.log("HTTP", "PIN 码验证成功，电脑名称: $devName, 设备ID: $retDevId")
                            onResult(true, 200, devName, retDevId)
                        } catch (e: Exception) {
                            onResult(true, 200, "Windows 电脑", null)
                        }
                    } else if (response.code == 403) {
                        Log.w(TAG, "PIN 码不匹配，电脑端拒绝配对")
                        DebugLogger.log("HTTP", "PIN 码不匹配，电脑端拒绝配对 (403)")
                        onResult(false, 403, null, null)
                    } else {
                        onResult(false, response.code, null, null)
                    }
                }
            }
        })
    }

    /**
     * 向电脑端发送剪贴板数据
     */
    fun sendClipboard(
        pcIp: String,
        httpPort: Int = 18236,
        text: String,
        deviceId: String,
        pinCode: String,
        onResult: ((Boolean) -> Unit)? = null
    ) {
        val url = "http://$pcIp:$httpPort/sync"
        val encryptedPayload = CryptoUtil.encrypt(text, pinCode)
        val textHash = CryptoUtil.computeHash(text)
        val preview = if (text.length > 20) text.take(20) + "..." else text
        val startTime = System.currentTimeMillis()

        DebugLogger.log("HTTP", "开始发送剪贴板到电脑 ($url): 长度=${text.length}, hash=$textHash, 预览=[$preview]")

        val json = JSONObject().apply {
            put("type", "CLIP_SYNC")
            put("sender_id", deviceId)
            put("hash", textHash)
            put("encrypted", encryptedPayload)
            put("timestamp", System.currentTimeMillis())
        }.toString()

        val requestBody = json.toRequestBody(JSON_MEDIA_TYPE)
        val request = Request.Builder().url(url).post(requestBody).build()

        client.newCall(request).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                val elapsed = System.currentTimeMillis() - startTime
                Log.w(TAG, "发送剪贴板到电脑失败 ($url): ${e.message}")
                DebugLogger.log("HTTP", "发送剪贴板失败 (耗时 ${elapsed}ms): ${e.javaClass.simpleName}: ${e.message}", e)
                onResult?.invoke(false)
            }

            override fun onResponse(call: Call, response: Response) {
                val elapsed = System.currentTimeMillis() - startTime
                response.use {
                    if (response.isSuccessful) {
                        Log.i(TAG, "成功通过 HTTP 将剪贴板同步至电脑")
                        DebugLogger.log("HTTP", "成功将剪贴板同步至电脑 (耗时 ${elapsed}ms, 状态码: ${response.code})")
                        onResult?.invoke(true)
                    } else {
                        Log.w(TAG, "电脑端返回错误码: ${response.code}")
                        DebugLogger.log("HTTP", "电脑端返回错误码: ${response.code} (耗时 ${elapsed}ms)")
                        onResult?.invoke(false)
                    }
                }
            }
        })
    }

    /**
     * 周期性向电脑端发送 AES-256-GCM 密文保活心跳与当前 IP/端口注册
     */
    fun sendHeartbeat(
        pcIp: String,
        httpPort: Int = 18236,
        pinCode: String,
        deviceId: String,
        deviceName: String,
        clientPort: Int = 18237,
        onResult: ((Boolean) -> Unit)? = null
    ) {
        if (pcIp.isEmpty() || pinCode.isEmpty()) {
            onResult?.invoke(false)
            return
        }
        val url = "http://$pcIp:$httpPort/heartbeat"
        val authCipher = try {
            buildAuthCipher(pinCode, deviceId, deviceName, clientPort)
        } catch (_: Exception) {
            onResult?.invoke(false)
            return
        }
        val json = JSONObject().apply {
            put("auth_cipher", authCipher)
        }.toString()
        val requestBody = json.toRequestBody(JSON_MEDIA_TYPE)
        val request = Request.Builder().url(url).post(requestBody).build()
        client.newCall(request).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                DebugLogger.log("HEARTBEAT_SEND", "心跳上报失败: ${e.message}")
                onResult?.invoke(false)
            }
            override fun onResponse(call: Call, response: Response) {
                val ok = response.isSuccessful
                response.close()
                DebugLogger.log("HEARTBEAT_SEND", "心跳上报结果: $ok (状态码: ${response.code})")
                onResult?.invoke(ok)
            }
        })
    }
}
