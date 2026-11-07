package com.crossclip.app.shizuku

import android.content.Context
import android.os.Process
import android.util.Log
import com.crossclip.app.util.DebugLogger
import org.json.JSONObject
import rikka.shizuku.Shizuku
import java.io.BufferedReader
import java.io.InputStreamReader
import java.lang.reflect.Method
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

object ShizukuPrivilegeHelper {

    private const val TAG = "ShizukuPrivilege"
    private val executor = Executors.newSingleThreadExecutor()
    private val hasApplied = AtomicBoolean(false)

    @Volatile
    private var cachedDaemonAlive = false
    @Volatile
    private var lastDaemonPingMs = 0L

    /**
     * 检查运行于 UID 2000 的独立守护进程 ClipShellDaemon 是否在线且版本匹配。
     * 带 1500ms 短缓存避免高频阻塞调用。
     */
    fun isShellDaemonAlive(forceCheck: Boolean = false): Boolean {
        val now = System.currentTimeMillis()
        if (!forceCheck && now - lastDaemonPingMs < 1500L) {
            return cachedDaemonAlive
        }
        val alive = try {
            val url = URL("http://127.0.0.1:${ClipShellDaemon.DAEMON_PORT}/ping")
            val conn = url.openConnection() as HttpURLConnection
            conn.connectTimeout = 250
            conn.readTimeout = 250
            conn.requestMethod = "GET"
            val code = conn.responseCode
            val versionOk = if (code == 200) {
                val respStr = conn.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
                val json = JSONObject(respStr)
                json.optInt("version", 1) >= ClipShellDaemon.DAEMON_VERSION
            } else {
                false
            }
            conn.disconnect()
            versionOk
        } catch (_: Throwable) {
            false
        }
        cachedDaemonAlive = alive
        lastDaemonPingMs = now
        return alive
    }

    /**
     * 通过本地 ClipShellDaemon (UID 2000) 读取系统剪贴板
     */
    fun readClipboardViaDaemon(): String? {
        return try {
            val url = URL("http://127.0.0.1:${ClipShellDaemon.DAEMON_PORT}/read")
            val conn = url.openConnection() as HttpURLConnection
            conn.connectTimeout = 400
            conn.readTimeout = 400
            conn.requestMethod = "GET"
            if (conn.responseCode == 200) {
                val respStr = conn.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
                conn.disconnect()
                cachedDaemonAlive = true
                lastDaemonPingMs = System.currentTimeMillis()
                val json = JSONObject(respStr)
                val text = json.optString("text", "")
                if (text.isNotEmpty()) text else null
            } else {
                conn.disconnect()
                null
            }
        } catch (_: Throwable) {
            cachedDaemonAlive = false
            null
        }
    }

    /**
     * 通过本地 ClipShellDaemon (UID 2000) 写入系统剪贴板并强制执行回读校验
     */
    fun writeClipboardViaDaemon(text: String): Boolean {
        if (text.isEmpty()) return false
        return try {
            val url = URL("http://127.0.0.1:${ClipShellDaemon.DAEMON_PORT}/write")
            val conn = url.openConnection() as HttpURLConnection
            conn.connectTimeout = 500
            conn.readTimeout = 500
            conn.requestMethod = "POST"
            conn.doOutput = true
            conn.setRequestProperty("Content-Type", "application/json; charset=utf-8")
            val payload = JSONObject().apply {
                put("text", text)
            }.toString().toByteArray(Charsets.UTF_8)
            conn.outputStream.use { it.write(payload) }
            val code = conn.responseCode
            val respStr = if (code == 200) {
                conn.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
            } else ""
            conn.disconnect()
            if (code == 200) {
                cachedDaemonAlive = true
                lastDaemonPingMs = System.currentTimeMillis()
                val json = JSONObject(respStr)
                json.optBoolean("verified", false)
            } else {
                false
            }
        } catch (_: Throwable) {
            cachedDaemonAlive = false
            false
        }
    }

    /**
     * 自动通过 Shizuku 执行底层 Shell 命令将应用注入系统白名单并启动独立 ClipShellDaemon 守护进程。
     */
    fun applySystemWhitelists(context: Context, force: Boolean = false) {
        if (!ShizukuClipboardManager.isShizukuBinderReady()) {
            Log.w(TAG, "Shizuku Binder 尚未就绪，跳过特权白名单注入")
            return
        }

        if (!force && hasApplied.getAndSet(true)) {
            startClipShellDaemon(context)
            return
        }

        val pkg = context.packageName
        val pid = Process.myPid()
        executor.execute {
            try {
                Log.i(TAG, "开始执行 Shizuku 系统级白名单与剪贴板特权赋权...")
                DebugLogger.log("SHIZUKU_PRIV", "开始执行系统级白名单与防冻结注入 (包名: $pkg, PID: $pid)")

                val commands = listOf(
                    // 1. Android 原生系统 Doze / DeviceIdle 永久白名单
                    "cmd deviceidle whitelist +$pkg",
                    "dumpsys deviceidle whitelist +$pkg",

                    // 2. AppOps 原生后台运行、剪贴板读写与悬浮窗权限
                    "cmd appops set $pkg RUN_IN_BACKGROUND allow",
                    "cmd appops set $pkg RUN_ANY_IN_BACKGROUND allow",
                    "cmd appops set $pkg READ_CLIPBOARD allow",
                    "cmd appops set $pkg WRITE_CLIPBOARD allow",
                    "cmd appops set $pkg WAKE_LOCK allow",
                    "cmd appops set $pkg START_FOREGROUND allow",
                    "cmd appops set $pkg SYSTEM_ALERT_WINDOW allow",
                    "cmd appops set $pkg AUTO_REVOKE_PERMISSIONS_IF_UNUSED ignore",

                    // 3. MIUI / HyperOS 定制私有权限码赋权
                    // 10008: MIUI 自启动 (AUTO_START)
                    "cmd appops set $pkg 10008 allow",
                    // 10021: MIUI 后台弹出界面 / 锁屏显示
                    "cmd appops set $pkg 10021 allow",
                    // 10022: MIUI 后台运行常驻
                    "cmd appops set $pkg 10022 allow",
                    // 10020: MIUI 显示悬浮窗
                    "cmd appops set $pkg 10020 allow",
                    // 10029: MIUI 后台完全访问剪贴板
                    "cmd appops set $pkg 10029 allow",

                    // 4. MIUI 自启动广播
                    "am broadcast -a miui.intent.action.AUTOSTART_ENABLE --es extra_pkgname $pkg",

                    // 5. MIUI Powerkeeper 数据库级无限制策略写入
                    "content insert --uri content://com.miui.powerkeeper.configure/scene --bind pkg:s:$pkg --bind bg_control:i:0",
                    "content update --uri content://com.miui.powerkeeper.configure/scene --bind bg_control:i:0 --where \"pkg='$pkg'\"",

                    // 6. MIUI / HyperOS 进程冷冻器免冻结名单
                    "settings put global miui_freeze_disabled_packages $pkg",
                    "settings put system miui_freeze_disabled_packages $pkg",
                    "settings put secure miui_freeze_disabled_packages $pkg"
                )

                var successCount = 0
                for (cmd in commands) {
                    val (exitCode, out, err) = runShellCommandWithDetails(cmd)
                    if (exitCode == 0) {
                        successCount++
                        DebugLogger.log("SHIZUKU_CMD", "OK (code $exitCode) -> [$cmd] ${if (out.isNotEmpty()) ": $out" else ""}")
                    } else {
                        DebugLogger.log("SHIZUKU_CMD", "WARN (code $exitCode) -> [$cmd] stdout=[$out] stderr=[$err]")
                    }
                }

                DebugLogger.log("SHIZUKU_PRIV", "特权白名单注入完成: $successCount/${commands.size} 成功")

                // 启动脱离 Shizuku 生命周期的独立 UID 2000 剪贴板守护进程
                startClipShellDaemon(context)
            } catch (e: Throwable) {
                Log.e(TAG, "执行 Shizuku 特权注入异常: ${e.javaClass.simpleName}: ${e.message}")
                DebugLogger.log("SHIZUKU_PRIV", "特权注入异常: ${e.javaClass.simpleName}: ${e.message}", e)
            }
        }
    }

    /**
     * 在 UID 2000 (Shell) 下启动脱离父进程的独立守护进程 ClipShellDaemon。
     * 即使离开局域网导致无线调试关闭、Shizuku 自身关机退出，该守护进程仍常驻后台。
     */
    fun startClipShellDaemon(context: Context) {
        val pkg = context.packageName
        val apkPath = context.applicationInfo.sourceDir
        executor.execute {
            try {
                if (isShellDaemonAlive(forceCheck = true)) {
                    DebugLogger.log("SHIZUKU_DAEMON", "ClipShellDaemon (UID 2000) 已在线运行中")
                    return@execute
                }
                val cmd = "nohup app_process -Djava.class.path=\"$apkPath\" /system/bin " +
                    "--nice-name=crossclip_shell_daemon com.crossclip.app.shizuku.ClipShellDaemon \"$pkg\" " +
                    ">/dev/null 2>&1 &"
                val res = runShellCommandWithDetails(cmd)
                Thread.sleep(400)
                val alive = isShellDaemonAlive(forceCheck = true)
                DebugLogger.log(
                    "SHIZUKU_DAEMON",
                    "拉起独立 ClipShellDaemon (UID 2000): exitCode=${res.exitCode}, 在线状态=$alive"
                )
            } catch (e: Exception) {
                DebugLogger.log("SHIZUKU_DAEMON", "启动 ClipShellDaemon 异常: ${e.message}")
            }
        }
    }

    private data class CommandResult(val exitCode: Int, val stdout: String, val stderr: String)

    private fun runShellCommandWithDetails(command: String): CommandResult {
        return try {
            val method: Method = Shizuku::class.java.getDeclaredMethod(
                "newProcess",
                Array<String>::class.java,
                Array<String>::class.java,
                String::class.java
            )
            method.isAccessible = true
            val process = method.invoke(null, arrayOf("sh", "-c", command), null, null) as java.lang.Process

            val stdoutBuilder = StringBuilder()
            val stderrBuilder = StringBuilder()

            val stdoutThread = Thread {
                try {
                    val reader = BufferedReader(InputStreamReader(process.inputStream))
                    var line: String?
                    while (reader.readLine().also { line = it } != null) {
                        stdoutBuilder.append(line).append("\n")
                    }
                } catch (_: Exception) {}
            }
            val stderrThread = Thread {
                try {
                    val reader = BufferedReader(InputStreamReader(process.errorStream))
                    var line: String?
                    while (reader.readLine().also { line = it } != null) {
                        stderrBuilder.append(line).append("\n")
                    }
                } catch (_: Exception) {}
            }

            stdoutThread.start()
            stderrThread.start()

            val exitCode = process.waitFor()
            stdoutThread.join(1000)
            stderrThread.join(1000)

            CommandResult(exitCode, stdoutBuilder.toString().trim(), stderrBuilder.toString().trim())
        } catch (e: Throwable) {
            CommandResult(-1, "", "${e.javaClass.simpleName}: ${e.message}")
        }
    }
}
