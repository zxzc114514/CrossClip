package com.crossclip.app.shizuku

import android.content.ClipData
import android.os.Binder
import android.os.Build
import android.os.IBinder
import android.os.Looper
import android.os.Parcel
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.OutputStream
import java.lang.reflect.Method
import java.lang.reflect.Proxy
import java.net.HttpURLConnection
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.URL
import java.security.MessageDigest
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 运行在 UID 2000 (com.android.shell) 下的独立自存活剪贴板守护进程。
 * 通过 app_process 启动并脱离父进程组，即使手机离开局域网导致无线调试关闭、Shizuku 自身关机退出，
 * 本守护进程依然常驻于系统后台，提供 100% 零感知的剪贴板监听、读取与带回读校验的写入服务。
 */
object ClipShellDaemon {

    const val DAEMON_PORT = 18239
    const val DAEMON_VERSION = 2
    private const val SHELL_PACKAGE = "com.android.shell"
    private const val LISTENER_CLASS = "android.content.IOnPrimaryClipChangedListener"
    private const val LISTENER_STUB_CLASS = "android.content.IOnPrimaryClipChangedListener\$Stub"
    private const val LISTENER_DESCRIPTOR = "android.content.IOnPrimaryClipChangedListener"
    private const val METHOD_GET = "getPrimaryClip"
    private const val METHOD_SET = "setPrimaryClip"
    private const val METHOD_LISTENER = "addPrimaryClipChangedListener"

    private val isRunning = AtomicBoolean(true)
    @Volatile
    private var lastKnownHash = ""
    @Volatile
    private var targetAppPackage = "com.crossclip.app"
    @Volatile
    private var listenerBinderRef: IBinder? = null

    @JvmStatic
    fun main(args: Array<String>) {
        if (args.isNotEmpty() && args[0].isNotBlank()) {
            targetAppPackage = args[0].trim()
        }

        bypassHiddenApiRestrictions()

        @Suppress("DEPRECATION")
        if (Looper.myLooper() == null) {
            Looper.prepareMainLooper()
        }

        // 若已有旧实例占用端口，先请求旧实例优雅退出
        requestOldDaemonShutdown()
        Thread.sleep(200)

        val serverSocket = try {
            ServerSocket(DAEMON_PORT, 32, InetAddress.getByName("127.0.0.1")).apply {
                reuseAddress = true
            }
        } catch (e: Throwable) {
            System.err.println("[ClipShellDaemon] 绑定本地端口 $DAEMON_PORT 失败: ${e.message}")
            return
        }

        println("[ClipShellDaemon] 已在 UID=${android.os.Process.myUid()} 启动，监听 127.0.0.1:$DAEMON_PORT")

        // 初始化初始剪贴板哈希，避免刚启动时误触发一次旧剪贴板同步
        try {
            val initText = readSystemClipboardInternal()
            if (!initText.isNullOrEmpty()) {
                lastKnownHash = sha256(initText)
            }
        } catch (_: Throwable) {}

        // 1. 注册系统级 IOnPrimaryClipChangedListener 回调
        val listenerRegistered = registerSystemClipListener()

        // 2. 启动后台定时监控与主进程保活线程
        val scheduler = Executors.newSingleThreadScheduledExecutor { r ->
            Thread(r, "ClipShellDaemon-Watchdog").apply { isDaemon = true }
        }

        // 每 800ms 轻量检查一次剪贴板变化（作为原生 Binder 回调的双保险）
        scheduler.scheduleWithFixedDelay({
            try {
                checkClipboardAndNotify()
            } catch (_: Throwable) {}
        }, 800, 800, TimeUnit.MILLISECONDS)

        // 每 5 秒执行一次主应用进程解冻唤醒与防杀自愈拉起
        scheduler.scheduleWithFixedDelay({
            try {
                keepAppProcessAlive(listenerRegistered)
            } catch (_: Throwable) {}
        }, 3, 5, TimeUnit.SECONDS)

        // 3. 处理来自 CrossClip 主进程的本地 IPC 请求
        val workerPool = Executors.newCachedThreadPool()
        while (isRunning.get()) {
            try {
                val client = serverSocket.accept()
                workerPool.execute {
                    handleIpcClient(client, serverSocket)
                }
            } catch (_: Throwable) {
                if (!isRunning.get()) break
            }
        }

        try {
            scheduler.shutdownNow()
            workerPool.shutdownNow()
            serverSocket.close()
        } catch (_: Throwable) {}
    }

    private fun bypassHiddenApiRestrictions() {
        try {
            val vmRuntimeClass = Class.forName("dalvik.system.VMRuntime")
            val getRuntime = vmRuntimeClass.getDeclaredMethod("getRuntime")
            getRuntime.isAccessible = true
            val runtime = getRuntime.invoke(null)
            val setExemptions = vmRuntimeClass.getDeclaredMethod("setHiddenApiExemptions", Array<String>::class.java)
            setExemptions.isAccessible = true
            setExemptions.invoke(runtime, arrayOf("L"))
        } catch (_: Throwable) {}

        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                org.lsposed.hiddenapibypass.HiddenApiBypass.addHiddenApiExemptions("L")
            }
        } catch (_: Throwable) {}
    }

    private fun requestOldDaemonShutdown() {
        try {
            val url = URL("http://127.0.0.1:$DAEMON_PORT/shutdown")
            val conn = url.openConnection() as HttpURLConnection
            conn.connectTimeout = 300
            conn.readTimeout = 300
            conn.requestMethod = "POST"
            conn.responseCode
            conn.disconnect()
        } catch (_: Throwable) {}
    }

    private fun readHeaderLine(input: java.io.InputStream): String? {
        val sb = java.io.ByteArrayOutputStream(128)
        var readAny = false
        while (true) {
            val b = input.read()
            if (b == -1) {
                return if (readAny) sb.toString(Charsets.UTF_8.name()) else null
            }
            readAny = true
            if (b == '\n'.code) break
            if (b != '\r'.code) {
                sb.write(b)
            }
        }
        return sb.toString(Charsets.UTF_8.name())
    }

    private fun readExactBytes(input: java.io.InputStream, length: Int): ByteArray {
        val buf = ByteArray(length)
        var offset = 0
        while (offset < length) {
            val r = input.read(buf, offset, length - offset)
            if (r == -1) break
            offset += r
        }
        return if (offset == length) buf else buf.copyOf(offset)
    }

    private fun handleIpcClient(socket: Socket, serverSocket: ServerSocket) {
        try {
            socket.soTimeout = 2500
            // 仅允许来自 127.0.0.1 的本机请求
            val remoteAddr = socket.inetAddress?.hostAddress ?: ""
            if (remoteAddr != "127.0.0.1" && remoteAddr != "::1" && !remoteAddr.startsWith("127.")) {
                socket.close()
                return
            }

            val rawInput = socket.getInputStream()
            val out = socket.getOutputStream()

            val requestLine = readHeaderLine(rawInput) ?: return
            val parts = requestLine.split(" ")
            if (parts.size < 2) return
            val method = parts[0].uppercase()
            val path = parts[1]

            var contentLength = 0
            while (true) {
                val line = readHeaderLine(rawInput) ?: break
                if (line.isEmpty()) break
                val lower = line.lowercase()
                if (lower.startsWith("content-length:")) {
                    contentLength = lower.substring("content-length:".length).trim().toIntOrNull() ?: 0
                }
            }

            val body = if (contentLength > 0) {
                String(readExactBytes(rawInput, contentLength), Charsets.UTF_8)
            } else {
                ""
            }

            when {
                path == "/ping" -> {
                    val resp = JSONObject().apply {
                        put("status", "ok")
                        put("version", DAEMON_VERSION)
                        put("uid", android.os.Process.myUid())
                        put("pid", android.os.Process.myPid())
                    }.toString()
                    sendHttpResponse(out, 200, resp)
                }
                path == "/read" -> {
                    val text = readSystemClipboardInternal() ?: ""
                    val resp = JSONObject().apply {
                        put("status", "ok")
                        put("text", text)
                    }.toString()
                    sendHttpResponse(out, 200, resp)
                }
                path == "/write" && method == "POST" -> {
                    val reqJson = JSONObject(body)
                    val text = reqJson.optString("text", "")
                    val ok = writeSystemClipboardWithVerify(text)
                    val resp = JSONObject().apply {
                        put("status", if (ok) "ok" else "error")
                        put("verified", ok)
                    }.toString()
                    sendHttpResponse(out, if (ok) 200 else 500, resp)
                }
                path == "/shutdown" -> {
                    sendHttpResponse(out, 200, "{\"status\":\"shutting_down\"}")
                    isRunning.set(false)
                    try {
                        serverSocket.close()
                    } catch (_: Throwable) {}
                }
                else -> {
                    sendHttpResponse(out, 404, "{\"status\":\"not_found\"}")
                }
            }
        } catch (_: Throwable) {
        } finally {
            try {
                socket.close()
            } catch (_: Throwable) {}
        }
    }

    private fun sendHttpResponse(out: OutputStream, code: Int, json: String) {
        val statusText = when (code) {
            200 -> "OK"
            500 -> "Internal Server Error"
            else -> "Not Found"
        }
        val bytes = json.toByteArray(Charsets.UTF_8)
        val header = "HTTP/1.1 $code $statusText\r\n" +
            "Content-Type: application/json; charset=utf-8\r\n" +
            "Content-Length: ${bytes.size}\r\n" +
            "Connection: close\r\n\r\n"
        out.write(header.toByteArray(Charsets.UTF_8))
        out.write(bytes)
        out.flush()
    }

    private fun getClipboardProxy(): Pair<Class<*>, Any>? {
        return try {
            val smClass = Class.forName("android.os.ServiceManager")
            val getService = smClass.getDeclaredMethod("getService", String::class.java)
            getService.isAccessible = true
            val rawBinder = getService.invoke(null, "clipboard") as? IBinder ?: return null

            val stubClass = Class.forName("android.content.IClipboard\$Stub")
            val asInterface = stubClass.getDeclaredMethod("asInterface", IBinder::class.java)
            asInterface.isAccessible = true
            val proxy = asInterface.invoke(null, rawBinder) ?: return null
            val invokeClass = proxy.javaClass.declaredMethods
                .firstOrNull { it.name == METHOD_GET || it.name == METHOD_SET }
                ?.declaringClass ?: proxy.javaClass
            invokeClass to proxy
        } catch (_: Throwable) {
            null
        }
    }

    private fun extractClipDataText(clip: ClipData): String? {
        for (i in 0 until clip.itemCount) {
            val item = clip.getItemAt(i) ?: continue
            val direct = item.text?.toString()
            if (!direct.isNullOrEmpty()) return direct
            val uriStr = item.uri?.toString()
            if (!uriStr.isNullOrEmpty()) return uriStr
            val coerced = try {
                item.coerceToText(null)?.toString()
            } catch (_: Throwable) {
                null
            }
            if (!coerced.isNullOrEmpty()) return coerced
        }
        return null
    }

    private fun readSystemClipboardInternal(): String? {
        val (invokeClass, proxy) = getClipboardProxy() ?: return null
        val methods = invokeClass.declaredMethods.filter { it.name == METHOD_GET }
        for (method in methods) {
            method.isAccessible = true
            for (args in buildCandidatesForDaemon(method, null, null)) {
                try {
                    val res = method.invoke(proxy, *args.toTypedArray())
                    if (res is ClipData && res.itemCount > 0) {
                        val text = extractClipDataText(res)
                        if (!text.isNullOrEmpty()) {
                            return text
                        }
                    }
                } catch (_: Throwable) {}
            }
        }
        return null
    }

    private fun writeSystemClipboardWithVerify(text: String): Boolean {
        if (text.isEmpty()) return false
        val (invokeClass, proxy) = getClipboardProxy() ?: return false
        val clipData = ClipData.newPlainText("CrossClip", text)
        val targetHash = sha256(text)
        lastKnownHash = targetHash

        val methods = invokeClass.declaredMethods.filter { it.name == METHOD_SET }
        for (method in methods) {
            method.isAccessible = true
            for (args in buildCandidatesForDaemon(method, clipData, null)) {
                try {
                    method.invoke(proxy, *args.toTypedArray())
                    val readBack = readSystemClipboardInternal()
                    if (readBack == text) {
                        lastKnownHash = targetHash
                        return true
                    }
                } catch (_: Throwable) {}
            }
        }
        return false
    }

    private fun registerSystemClipListener(): Boolean {
        val (invokeClass, proxy) = getClipboardProxy() ?: return false
        return try {
            val binder = object : Binder() {
                override fun onTransact(code: Int, data: Parcel, reply: Parcel?, flags: Int): Boolean {
                    if (code == IBinder.FIRST_CALL_TRANSACTION) {
                        try {
                            data.enforceInterface(LISTENER_DESCRIPTOR)
                        } catch (_: Throwable) {}
                        checkClipboardAndNotify()
                        reply?.writeNoException()
                        return true
                    }
                    return super.onTransact(code, data, reply, flags)
                }
            }

            val listenerProxy: Any = try {
                val stubClass = Class.forName(LISTENER_STUB_CLASS)
                val asInterface = stubClass.getDeclaredMethod("asInterface", IBinder::class.java)
                asInterface.isAccessible = true
                asInterface.invoke(null, binder)
            } catch (_: Throwable) {
                val listenerMethod = invokeClass.declaredMethods.firstOrNull { it.name == METHOD_LISTENER }
                val listenerIf = listenerMethod?.parameterTypes?.firstOrNull {
                    it.isInterface && (it.name == LISTENER_CLASS || it.name.contains("PrimaryClipChanged"))
                } ?: return false
                Proxy.newProxyInstance(
                    listenerIf.classLoader,
                    arrayOf(listenerIf, IBinder::class.java)
                ) { _, method, _ ->
                    if (method.name == "asBinder") binder else null
                }
            } ?: return false

            val methods = invokeClass.declaredMethods.filter { it.name == METHOD_LISTENER }
            for (method in methods) {
                method.isAccessible = true
                for (args in buildCandidatesForDaemon(method, null, listenerProxy)) {
                    try {
                        method.invoke(proxy, *args.toTypedArray())
                        listenerBinderRef = binder
                        return true
                    } catch (_: Throwable) {}
                }
            }
            false
        } catch (_: Throwable) {
            false
        }
    }

    private fun checkClipboardAndNotify() {
        val text = readSystemClipboardInternal() ?: return
        if (text.isEmpty()) return
        val hash = sha256(text)
        if (hash != lastKnownHash) {
            if (notifyMainAppClipChanged(text)) {
                lastKnownHash = hash
            }
        }
    }

    private fun notifyMainAppClipChanged(text: String): Boolean {
        // 1. 优先通过本地 HTTP 直推给主进程的 LocalHttpServer (127.0.0.1:18237/local_clip)
        try {
            val url = URL("http://127.0.0.1:18237/local_clip")
            val conn = url.openConnection() as HttpURLConnection
            conn.connectTimeout = 800
            conn.readTimeout = 800
            conn.requestMethod = "POST"
            conn.doOutput = true
            conn.setRequestProperty("Content-Type", "application/json; charset=utf-8")
            val payload = JSONObject().apply {
                put("text", text)
            }.toString().toByteArray(Charsets.UTF_8)
            conn.outputStream.use { it.write(payload) }
            if (conn.responseCode == 200) {
                conn.disconnect()
                return true
            }
            conn.disconnect()
        } catch (_: Throwable) {}

        // 2. 若主进程被冻结或尚未启动，发送高优先级前台广播唤醒它，不锁定 lastKnownHash 以便下一拍重推
        try {
            Runtime.getRuntime().exec(
                arrayOf(
                    "sh",
                    "-c",
                    "am broadcast -a com.crossclip.app.WAKEUP -p $targetAppPackage -f 0x10000020 --receiver-foreground >/dev/null 2>&1"
                )
            ).waitFor()
        } catch (_: Throwable) {}
        return false
    }

    private fun keepAppProcessAlive(listenerRegistered: Boolean) {
        val cmd = "(pidof $targetAppPackage >/dev/null 2>&1 || pgrep -f $targetAppPackage >/dev/null 2>&1) || " +
            "am start-foreground-service -n $targetAppPackage/com.crossclip.app.service.SyncForegroundService -f 0x10000020 >/dev/null 2>&1"
        try {
            Runtime.getRuntime().exec(arrayOf("sh", "-c", cmd)).waitFor()
        } catch (_: Throwable) {}
        if (!listenerRegistered && listenerBinderRef == null) {
            registerSystemClipListener()
        }
    }

    private fun buildCandidatesForDaemon(
        method: Method,
        clipData: ClipData?,
        listener: Any?
    ): List<List<Any?>> {
        val choices = ArrayList<List<Any?>>(method.parameterCount)
        var stringIdx = 0
        var intIdx = 0

        for (param in method.parameterTypes) {
            val list: List<Any?> = when {
                param == ClipData::class.java -> listOf(clipData)
                param == String::class.java -> {
                    stringIdx++
                    if (stringIdx == 1) listOf(SHELL_PACKAGE, targetAppPackage, null)
                    else listOf(null, SHELL_PACKAGE)
                }
                param == Int::class.javaPrimitiveType || param == java.lang.Integer::class.java -> {
                    intIdx++
                    if (intIdx == 1) listOf(0) else listOf(0)
                }
                param == Boolean::class.javaPrimitiveType || param == java.lang.Boolean::class.java ->
                    listOf(false, true)
                param == Long::class.javaPrimitiveType || param == java.lang.Long::class.java ->
                    listOf(0L)
                param.isInterface -> {
                    if (listener != null && (param.name == LISTENER_CLASS || param.name.contains("PrimaryClipChanged"))) {
                        listOf(listener)
                    } else {
                        listOf(null)
                    }
                }
                else -> listOf(null)
            }
            choices.add(list)
        }

        val result = LinkedHashSet<List<Any?>>()
        if (choices.isEmpty()) {
            result.add(emptyList())
            return result.toList()
        }
        val base = IntArray(choices.size)
        result.add(choices.indices.map { i -> choices[i][base[i]] })
        for (i in choices.indices) {
            for (j in 1 until choices[i].size) {
                val alt = base.copyOf()
                alt[i] = j
                result.add(choices.indices.map { k -> choices[k][alt[k]] })
            }
        }
        return result.toList()
    }

    private fun sha256(text: String): String {
        val digest = MessageDigest.getInstance("SHA-256")
        val bytes = digest.digest(text.toByteArray(Charsets.UTF_8))
        return bytes.joinToString("") { "%02x".format(it) }
    }
}
