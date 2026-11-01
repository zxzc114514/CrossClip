package com.crossclip.app.shizuku

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.Binder
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.Parcel
import android.os.Process
import android.util.Log
import com.crossclip.app.crypto.CryptoUtil
import com.crossclip.app.util.DebugLogger
import org.lsposed.hiddenapibypass.HiddenApiBypass
import rikka.shizuku.Shizuku
import rikka.shizuku.ShizukuBinderWrapper
import rikka.shizuku.SystemServiceHelper
import java.lang.reflect.Method
import java.lang.reflect.Proxy
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit

object ShizukuClipboardManager {

    private const val TAG = "ShizukuClipboard"
    private const val POLL_INTERVAL_MS = 1000L
    private const val REGISTER_RETRY_MS = 2000L

    private const val ICLIPBOARD_STUB_CLASS = "android.content.IClipboard\$Stub"
    private const val LISTENER_CLASS = "android.content.IOnPrimaryClipChangedListener"
    private const val LISTENER_STUB_CLASS = "android.content.IOnPrimaryClipChangedListener\$Stub"
    private const val SHELL_PACKAGE = "com.android.shell"
    private const val LISTENER_DESCRIPTOR = "android.content.IOnPrimaryClipChangedListener"

    private const val METHOD_GET = "getPrimaryClip"
    private const val METHOD_SET = "setPrimaryClip"
    private const val METHOD_LISTENER = "addPrimaryClipChangedListener"

    enum class State {
        NOT_RUNNING,   // Shizuku 服务未运行
        UNAUTHORIZED,  // Shizuku 运行中但未向 CrossClip 授权
        READY          // 已授权且 Binder 通信正常
    }

    @Volatile
    private var appContext: Context? = null

    @Volatile
    private var onChangeListener: (() -> Unit)? = null

    @Volatile
    private var pollCallback: (() -> Unit)? = null

    @Volatile
    private var primaryClipListenerRegistered = false

    @Volatile
    private var pollingStarted = false

    @Volatile
    private var lastPolledHash = ""

    private var internalListenerBinder: IBinder? = null
    private var pollExecutor: ScheduledExecutorService? = null
    private val binderListenersRegistered = java.util.concurrent.atomic.AtomicBoolean(false)

    private val mainHandler = Handler(Looper.getMainLooper())

    private val binderReceivedListener = Shizuku.OnBinderReceivedListener {
        DebugLogger.log("Shizuku", "收到 Shizuku Binder 恢复通知")
        appContext?.let { ctx ->
            if (isShizukuBinderReady()) {
                ShizukuPrivilegeHelper.applySystemWhitelists(ctx, force = true)
                tryRegisterListener()
            }
        }
    }

    private val binderDeadListener = Shizuku.OnBinderDeadListener {
        DebugLogger.log("Shizuku", "Shizuku Binder 已断开，复位监听状态并无缝切换至独立 ClipShellDaemon 守护")
        primaryClipListenerRegistered = false
        internalListenerBinder = null
        val cb = pollCallback ?: onChangeListener
        if (cb != null) {
            startPolling(cb)
        }
    }

    private data class InvocationPlan(
        val declaringClass: Class<*>,
        val method: Method,
        val candidateIndex: Int,
        val signature: String
    )

    private val invocationPlansCache = HashMap<String, List<InvocationPlan>>()

    private val retryRunnable = object : Runnable {
        override fun run() {
            if (onChangeListener == null || primaryClipListenerRegistered) return
            if (isShizukuBinderReady()) {
                tryRegisterListener()
            }
            if (!primaryClipListenerRegistered && onChangeListener != null) {
                mainHandler.postDelayed(this, REGISTER_RETRY_MS)
            }
        }
    }

    fun init(context: Context) {
        val ctx = context.applicationContext
        appContext = ctx
        DebugLogger.init(ctx)
        if (binderListenersRegistered.compareAndSet(false, true)) {
            try {
                Shizuku.addBinderReceivedListenerSticky(binderReceivedListener)
                Shizuku.addBinderDeadListener(binderDeadListener)
            } catch (_: Throwable) {}
        }
        if (isShizukuBinderReady()) {
            ShizukuPrivilegeHelper.applySystemWhitelists(ctx)
        }
    }

    /**
     * 检查 Shizuku 进程本身是否在线且已向本应用授权
     */
    fun isShizukuBinderReady(): Boolean {
        return try {
            Shizuku.pingBinder() && Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED
        } catch (_: Throwable) {
            false
        }
    }

    /**
     * 是否处于“Shizuku 自身已关机休眠，但独立 UID 2000 守护进程 ClipShellDaemon 仍在线工作”状态
     */
    fun isDaemonAliveOnly(): Boolean {
        return !isShizukuBinderReady() && ShizukuPrivilegeHelper.isShellDaemonAlive()
    }

    /**
     * 获取当前特权剪贴板引擎状态（含 Shizuku 直连与 ClipShellDaemon 独立存活双通道）
     */
    fun getState(): State {
        return try {
            if (Shizuku.pingBinder()) {
                if (Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED) {
                    State.READY
                } else {
                    State.UNAUTHORIZED
                }
            } else if (ShizukuPrivilegeHelper.isShellDaemonAlive()) {
                State.READY
            } else {
                State.NOT_RUNNING
            }
        } catch (e: Throwable) {
            if (ShizukuPrivilegeHelper.isShellDaemonAlive()) {
                State.READY
            } else {
                State.NOT_RUNNING
            }
        }
    }

    fun isReady(): Boolean = getState() == State.READY

    /**
     * 底层特权剪贴板 Binder 监听是否注册成功。
     */
    fun isListenerRegistered(): Boolean = primaryClipListenerRegistered

    /**
     * 请求 Shizuku 权限
     */
    fun requestPermission(requestCode: Int = 10086) {
        try {
            if (Shizuku.pingBinder() && Shizuku.checkSelfPermission() != PackageManager.PERMISSION_GRANTED) {
                Shizuku.requestPermission(requestCode)
            }
        } catch (e: Throwable) {
            Log.e(TAG, "请求 Shizuku 权限失败: ${e.message}")
        }
    }

    /**
     * 注册底层特权剪贴板变化监听。Shizuku 尚未就绪时自动重试。
     */
    @Synchronized
    fun registerListener(onChanged: () -> Unit) {
        onChangeListener = onChanged
        if (!primaryClipListenerRegistered && isReady()) {
            tryRegisterListener()
        }
        if (!primaryClipListenerRegistered) {
            mainHandler.removeCallbacks(retryRunnable)
            mainHandler.postDelayed(retryRunnable, REGISTER_RETRY_MS)
        }
    }

    @Synchronized
    fun unregisterListener() {
        onChangeListener = null
        primaryClipListenerRegistered = false
        internalListenerBinder = null
        mainHandler.removeCallbacks(retryRunnable)
    }

    /**
     * 用户刚授予 Shizuku 权限时立即重试注册监听并注入系统白名单。
     */
    @Synchronized
    fun onPermissionGranted() {
        appContext?.let { ShizukuPrivilegeHelper.applySystemWhitelists(it, force = true) }
        if (onChangeListener == null) return
        if (!primaryClipListenerRegistered && isReady()) {
            tryRegisterListener()
        }
        if (!primaryClipListenerRegistered) {
            mainHandler.removeCallbacks(retryRunnable)
            mainHandler.postDelayed(retryRunnable, REGISTER_RETRY_MS)
        }
    }

    /**
     * 轮询兜底：使用独立后台常驻守护线程定期读取剪贴板。
     * 当底层原生 Binder 监听注册成功时自动停用，避免持续唤醒耗电。
     */
    @Synchronized
    fun startPolling(onChanged: () -> Unit) {
        pollCallback = onChanged
        if (primaryClipListenerRegistered) {
            DebugLogger.log("Shizuku", "底层监听已就绪，跳过启动高频定时轮询")
            return
        }
        pollingStarted = true
        lastPolledHash = ""
        pollExecutor?.shutdownNow()
        val executor = Executors.newSingleThreadScheduledExecutor { r ->
            Thread(r, "CrossClip-ShizukuPoll").apply { isDaemon = true }
        }
        pollExecutor = executor
        executor.scheduleWithFixedDelay({
            if (pollingStarted && !primaryClipListenerRegistered) {
                pollOnce()
            }
        }, 0, POLL_INTERVAL_MS, TimeUnit.MILLISECONDS)
    }

    @Synchronized
    fun stopPolling() {
        pollingStarted = false
        pollCallback = null
        pollExecutor?.shutdownNow()
        pollExecutor = null
        DebugLogger.log("Shizuku", "已停止主动轮询守护")
    }

    @Synchronized
    fun pausePollingForScreenOff() {
        if (pollingStarted) {
            DebugLogger.log("Shizuku", "息屏暂停主动轮询以节省电量")
            pollExecutor?.shutdownNow()
            pollExecutor = null
        }
    }

    @Synchronized
    fun resumePollingForScreenOn() {
        if (pollingStarted && !primaryClipListenerRegistered && pollCallback != null) {
            val cb = pollCallback ?: return
            DebugLogger.log("Shizuku", "亮屏恢复主动轮询兜底")
            startPolling(cb)
        }
    }

    /**
     * 获取底层 IClipboard Shizuku 代理。
     *
     * 通道一：IClipboard$Stub.asInterface 包装 Shizuku Binder。
     * 通道二（兜底）：从普通 ClipboardManager 反射读取隐藏字段 mService，
     * 拿到 IClipboard$Proxy 类后 newInstance 一个包着 Shizuku Binder 的新代理，
     * 完全避开对隐藏类的 Class.forName 查找。
     */
    @Synchronized
    private fun getIClipboard(): Pair<Class<*>, Any>? {
        if (!isShizukuBinderReady()) return null
        return try {
            val rawBinder = SystemServiceHelper.getSystemService("clipboard")
            if (rawBinder == null) {
                Log.e(TAG, "获取系统 clipboard Binder 失败 (rawBinder=null)")
                return null
            }
            val wrappedBinder = ShizukuBinderWrapper(rawBinder)
            val proxy = buildProxy(wrappedBinder)
            if (proxy == null) {
                Log.e(TAG, "无法创建 IClipboard Shizuku 代理")
                return null
            }
            val invokeClass = resolveInvokeClass(proxy)
            Log.i(TAG, "已获取 IClipboard 代理: ${proxy.javaClass.name} (invokeClass=${invokeClass.name})")
            invokeClass to proxy
        } catch (e: Throwable) {
            Log.e(TAG, "获取 IClipboard 代理异常: ${e.javaClass.simpleName}: ${e.message}")
            null
        }
    }

    /**
     * 创建包着 Shizuku Binder 的 IClipboard 代理，供常规调用与自检共用。
     */
    private fun buildProxy(wrappedBinder: IBinder): Any? {
        var failInfo: String? = null
        var proxy: Any? = null

        // 通道一：隐藏 Stub.asInterface
        try {
            val stubClass = Class.forName(ICLIPBOARD_STUB_CLASS)
            proxy = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                HiddenApiBypass.invoke(stubClass, null, "asInterface", wrappedBinder)
            } else {
                stubClass.getMethod("asInterface", IBinder::class.java).invoke(null, wrappedBinder)
            }
        } catch (e: Throwable) {
            failInfo = "${e.javaClass.simpleName}: ${e.message}"
            Log.w(TAG, "Stub.asInterface 方式失败: $failInfo")
        }

        // 通道二：ClipboardManager.mService + newInstance 包 Shizuku Binder
        if (proxy == null) {
            try {
                val context = appContext ?: throw IllegalStateException("appContext 未初始化")
                val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                val field = HiddenApiBypass.getInstanceFields(ClipboardManager::class.java)
                    .firstOrNull { it.name == "mService" }
                    ?: throw IllegalStateException("未找到 ClipboardManager.mService")
                val localProxy = field.get(cm)
                    ?: throw IllegalStateException("ClipboardManager.mService 为空")
                val clazz = localProxy.javaClass
                proxy = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                    HiddenApiBypass.newInstance(clazz, wrappedBinder)
                } else {
                    clazz.getConstructor(IBinder::class.java).newInstance(wrappedBinder)
                }
            } catch (e: Throwable) {
                Log.e(TAG, "mService+newInstance 兜底失败: ${e.javaClass.simpleName}: ${e.message}")
            }
        }

        return proxy
    }

    /**
     * 找到真正声明剪贴板方法的具体类，供 HiddenApiBypass.invoke 使用。
     */
    private fun resolveInvokeClass(proxy: Any): Class<*> {
        return try {
            getDeclaredMethods(proxy.javaClass)
                .firstOrNull { it.name == METHOD_GET || it.name == METHOD_SET }
                ?.declaringClass
                ?: proxy.javaClass
        } catch (e: Throwable) {
            proxy.javaClass
        }
    }

    /**
     * 通过 Shell 特权（优先 Shizuku Binder 直连，次选独立存活的 ClipShellDaemon）静默写入剪贴板。
     * 每次写入后强制执行回读校验，只有回读内容与目标文本完全一致才返回 true，彻底杜绝假成功。
     */
    fun writeClipboard(text: String): Boolean {
        if (text.isEmpty()) return false
        val targetHash = CryptoUtil.computeHash(text)

        // 通道 A：若 Shizuku Binder 在线，尝试直连写入并执行真实回读校验
        val pair = getIClipboard()
        if (pair != null) {
            val (invokeClass, proxy) = pair
            try {
                val clipData = ClipData.newPlainText("CrossClip", text)
                val plans = getInvocationPlans(invokeClass, proxy, METHOD_SET)

                for (plan in plans) {
                    try {
                        invokePlannedMethod(plan, proxy, METHOD_SET, clipData, null)
                        val verified = readClipboardDirectFromProxy(invokeClass, proxy)
                        if (verified == text) {
                            lastPolledHash = targetHash
                            DebugLogger.log(
                                "Shizuku",
                                "后台写入并回读校验成功 (长度 ${text.length}, 签名=${plan.signature})"
                            )
                            return true
                        }
                    } catch (_: Throwable) {}
                }

                // 固定 AOSP 签名兜底 + 回读校验
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                    for (args in buildCommonArgs(METHOD_SET, clipData, null)) {
                        try {
                            HiddenApiBypass.invoke(invokeClass, proxy, METHOD_SET, *args)
                            val verified = readClipboardDirectFromProxy(invokeClass, proxy)
                            if (verified == text) {
                                lastPolledHash = targetHash
                                DebugLogger.log("Shizuku", "后台写入并回读校验成功 (AOSP兜底)")
                                return true
                            }
                        } catch (_: Throwable) {}
                    }
                }
                invocationPlansCache.clear()
            } catch (e: Throwable) {
                invocationPlansCache.clear()
                Log.w(TAG, "Shizuku 直连写入异常，切换至独立守护进程通道: ${e.message}")
            }
        }

        // 通道 B：通过独立存活的 ClipShellDaemon (UID 2000) 写入并回读校验
        if (ShizukuPrivilegeHelper.writeClipboardViaDaemon(text)) {
            lastPolledHash = targetHash
            DebugLogger.log("Shizuku", "通过独立 ClipShellDaemon (UID 2000) 写入并校验成功 (长度 ${text.length})")
            return true
        }

        DebugLogger.log("Shizuku", "特权通道写入剪贴板未通过回读校验")
        return false
    }

    private fun readClipboardDirectFromProxy(invokeClass: Class<*>, proxy: Any): String? {
        return try {
            val result = invokeIClipboardMethod(invokeClass, proxy, METHOD_GET, skipNullResult = true)
            if (result is ClipData && result.itemCount > 0) {
                val item = result.getItemAt(0)
                item.text?.toString() ?: item.coerceToText(null)?.toString()
            } else {
                null
            }
        } catch (_: Throwable) {
            null
        }
    }

    /**
     * 通过 Shell 特权（Shizuku Binder 直连或独立 ClipShellDaemon）读取系统剪贴板内容。
     */
    fun readClipboard(): String? {
        val pair = getIClipboard()
        if (pair != null) {
            val (invokeClass, proxy) = pair
            val text = readClipboardDirectFromProxy(invokeClass, proxy)
            if (!text.isNullOrEmpty()) {
                DebugLogger.log("Shizuku", "后台读取成功 (长度 ${text.length})")
                return text
            }
        }

        val daemonText = ShizukuPrivilegeHelper.readClipboardViaDaemon()
        if (!daemonText.isNullOrEmpty()) {
            DebugLogger.log("Shizuku", "通过独立 ClipShellDaemon 读取成功 (长度 ${daemonText.length})")
            return daemonText
        }
        return null
    }

    /**
     * 界面自检：完整走一遍特权读取链路，并把设备真实方法签名或独立守护状态带出来。
     */
    fun debugReadClipboard(): String {
        return try {
            if (!isReady()) return "特权未激活或未运行"
            if (isDaemonAliveOnly()) {
                val dText = ShizukuPrivilegeHelper.readClipboardViaDaemon()
                return if (!dText.isNullOrEmpty()) {
                    "独立守护 ClipShellDaemon (UID 2000) 在线，读取成功 (长度 ${dText.length})"
                } else {
                    "独立守护 ClipShellDaemon (UID 2000) 在线，当前剪贴板为空"
                }
            }
            val rawBinder = SystemServiceHelper.getSystemService("clipboard")
                ?: return "无法获取系统 clipboard Binder"
            val wrappedBinder = ShizukuBinderWrapper(rawBinder)
            val proxy = buildProxy(wrappedBinder)
                ?: return "创建 Shizuku 剪贴板代理失败"
            val invokeClass = resolveInvokeClass(proxy)
            val report = buildSignatureReport(invokeClass)

            try {
                val result = invokeIClipboardMethod(invokeClass, proxy, METHOD_GET)
                if (result is ClipData && result.itemCount > 0) {
                    val item = result.getItemAt(0)
                    val text = item.text?.toString() ?: item.coerceToText(null)?.toString()
                    if (!text.isNullOrEmpty()) {
                        return "Shizuku 读取成功 (长度 ${text.length})\n$report"
                    }
                }
                "Shizuku 调用成功但剪贴板为空\n$report"
            } catch (e: Throwable) {
                "Shizuku 读取失败: ${e.javaClass.simpleName}: ${e.message}\n$report"
            }
        } catch (e: Throwable) {
            "Shizuku 读取失败: ${e.javaClass.simpleName}: ${e.message}"
        }
    }

    /**
     * 界面完整自检：先测读取，再写入一段标记文本并回读校验，最后恢复原剪贴板。
     */
    fun debugFullSelfTest(): String {
        val daemonAlive = ShizukuPrivilegeHelper.isShellDaemonAlive(forceCheck = true)
        val listenerPart = when {
            isListenerRegistered() -> "底层监听: Shizuku 原生 Binder 已注册 | 独立守护: ${if (daemonAlive) "在线" else "未启动"}"
            daemonAlive -> "底层监听: 独立 ClipShellDaemon (UID 2000) 守护在线"
            else -> "底层监听: 未注册（当前依赖每秒轮询）"
        }
        val readPart = debugReadClipboard()
        val writePart = debugWriteClipboard()
        return "$listenerPart\n$readPart\n$writePart"
    }

    /**
     * 写入自检：写入标记文本 -> 回读校验 -> 恢复原剪贴板。
     */
    fun debugWriteClipboard(): String {
        return try {
            if (!isReady()) return "写入自检: 未授权或未运行"
            val original = readClipboard()
            val marker = "CROSSCLIP_SELFTEST_" + System.currentTimeMillis()

            val writeOk = try {
                invokeSetPrimaryClipDirect(marker)
            } catch (e: Throwable) {
                "异常: ${e.javaClass.simpleName}: ${e.message}"
            }

            val verified = readClipboard()
            val restored = restoreClipboard(original)

            buildString {
                append("特权写入自检:\n")
                append("  写入调用: ").append(if (writeOk == true) "成功" else "$writeOk").append("\n")
                append("  回读校验: ").append(
                    if (verified == marker) "一致"
                    else "不一致 (${verified?.take(30) ?: "null"})"
                ).append("\n")
                append("  恢复原剪贴板: ").append(if (restored) "成功" else "失败，请重新复制一次")
            }
        } catch (e: Throwable) {
            "特权写入自检异常: ${e.javaClass.simpleName}: ${e.message}"
        }
    }

    /**
     * 直接走特权通道调用 setPrimaryClip，供写入自检使用。
     */
    private fun invokeSetPrimaryClipDirect(text: String): Boolean {
        if (text.isEmpty()) return false
        val pair = getIClipboard()
        if (pair != null) {
            val (invokeClass, proxy) = pair
            val clipData = ClipData.newPlainText("CrossClip", text)
            invokeIClipboardMethod(invokeClass, proxy, METHOD_SET, clipData = clipData)
            return true
        }
        return ShizukuPrivilegeHelper.writeClipboardViaDaemon(text)
    }

    private fun restoreClipboard(text: String?): Boolean {
        if (text.isNullOrEmpty()) return true
        return try {
            invokeSetPrimaryClipDirect(text)
        } catch (e: Throwable) {
            Log.w(TAG, "恢复原剪贴板失败: ${e.javaClass.simpleName}: ${e.message}")
            false
        }
    }

    /**
     * 汇总设备上剪贴板三个关键方法的真实签名，方便排查新 ROM 差异。
     */
    private fun buildSignatureReport(invokeClass: Class<*>): String {
        val sb = StringBuilder()
        sb.append("设备剪贴板接口签名:\n")
        for (name in listOf(METHOD_GET, METHOD_SET, METHOD_LISTENER)) {
            val methods = getDeclaredMethods(invokeClass).filter { it.name == name }
            if (methods.isEmpty()) {
                sb.append("  $name: 未找到\n")
            } else {
                sb.append("  $name: ").append(methods.joinToString(" | ") { describeMethod(it) }).append("\n")
            }
        }
        return sb.toString().trimEnd()
    }

    /**
     * 注册底层特权剪贴板变化监听。
     */
    @Synchronized
    private fun tryRegisterListener() {
        if (primaryClipListenerRegistered) return
        val pair = getIClipboard() ?: return
        val (invokeClass, proxy) = pair

        try {
            val listenerBinder = object : Binder() {
                override fun onTransact(code: Int, data: Parcel, reply: Parcel?, flags: Int): Boolean {
                    if (code == IBinder.FIRST_CALL_TRANSACTION) {
                        data.enforceInterface(LISTENER_DESCRIPTOR)
                        Log.i(TAG, "收到 Shizuku 底层剪贴板变更 Binder 回调")
                        onChangeListener?.invoke()
                        reply?.writeNoException()
                        return true
                    }
                    return super.onTransact(code, data, reply, flags)
                }
            }

            val listenerProxy: Any? = try {
                val listenerStubClass = Class.forName(LISTENER_STUB_CLASS)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                    HiddenApiBypass.invoke(listenerStubClass, null, "asInterface", listenerBinder)
                } else {
                    listenerStubClass.getMethod("asInterface", IBinder::class.java).invoke(null, listenerBinder)
                }
            } catch (e: Throwable) {
                Log.w(TAG, "监听器 Stub.asInterface 失败，改用动态代理: ${e.javaClass.simpleName}: ${e.message}")
                buildListenerDynamicProxy(invokeClass, listenerBinder)
            }

            if (listenerProxy == null) {
                Log.e(TAG, "监听器代理为空，无法注册")
                return
            }

            invokeIClipboardMethod(invokeClass, proxy, METHOD_LISTENER, listener = listenerProxy)
            internalListenerBinder = listenerBinder
            primaryClipListenerRegistered = true
            Log.i(TAG, "已成功注册 Shizuku 底层原生剪贴板 Binder 监听器")
            DebugLogger.log("Shizuku", "底层监听注册成功，自动停用主动轮询")
            stopPolling()
        } catch (e: Throwable) {
            Log.w(TAG, "注册 Shizuku 剪贴板监听失败: ${e.javaClass.simpleName}: ${e.message}")
            DebugLogger.log("Shizuku", "底层监听注册失败: ${e.javaClass.simpleName}: ${e.message}")
        }
    }

    /**
     * 从 addPrimaryClipChangedListener 参数类型里拿到监听器接口，构造动态代理。
     * 避免依赖隐藏类 IOnPrimaryClipChangedListener 的 Class.forName。
     */
    private fun buildListenerDynamicProxy(invokeClass: Class<*>, listenerBinder: IBinder): Any? {
        return try {
            val methods = getDeclaredMethods(invokeClass).filter { it.name == METHOD_LISTENER }
            val listenerInterface = methods.firstNotNullOfOrNull { method ->
                method.parameterTypes.firstOrNull {
                    it.isInterface && (it.name == LISTENER_CLASS || it.name.contains("PrimaryClipChanged"))
                }
            } ?: methods.firstNotNullOfOrNull { method ->
                method.parameterTypes.firstOrNull { it.isInterface }
            } ?: return null

            Proxy.newProxyInstance(
                listenerInterface.classLoader,
                arrayOf(listenerInterface, IBinder::class.java)
            ) { _, method, _ ->
                if (method.name == "asBinder" && method.parameterCount == 0) {
                    listenerBinder
                } else {
                    null
                }
            }
        } catch (e: Throwable) {
            Log.w(TAG, "动态代理构建监听器失败: ${e.javaClass.simpleName}: ${e.message}")
            null
        }
    }

    /**
     * 调用 IClipboard 隐藏接口方法。
     *
     * 不依赖固定 AOSP 参数个数：先枚举设备上真实声明的方法，
     * 再按每个方法的参数类型动态合成多组候选实参；调用失败或返回 null 时自动换下一组。
     */
    @Synchronized
    private fun invokeIClipboardMethod(
        invokeClass: Class<*>,
        proxy: Any,
        methodName: String,
        clipData: ClipData? = null,
        listener: Any? = null,
        skipNullResult: Boolean = false
    ): Any? {
        var lastError: Throwable? = null
        var lastAttempt = "无候选"
        var anyCandidateExecutedSuccessfully = false

        val plans = getInvocationPlans(invokeClass, proxy, methodName)
        for (plan in plans) {
            try {
                val result = invokePlannedMethod(plan, proxy, methodName, clipData, listener)
                anyCandidateExecutedSuccessfully = true
                if (skipNullResult && result == null) {
                    lastAttempt = "类=${plan.declaringClass.name}, 签名=${plan.signature}, 返回 null"
                    Log.w(TAG, "$methodName 返回 null，继续尝试下一组候选: $lastAttempt")
                    continue
                }
                Log.i(
                    TAG,
                    "HiddenApiBypass 调用 $methodName 成功 (类=${plan.declaringClass.name}, " +
                        "签名=${plan.signature})"
                )
                return result
            } catch (e: Throwable) {
                lastError = e
                lastAttempt = "类=${plan.declaringClass.name}, 签名=${plan.signature}"
                Log.w(
                    TAG,
                    "HiddenApiBypass 调用 $methodName 失败 ($lastAttempt): " +
                        "${e.javaClass.simpleName}: ${e.message}"
                )
            }
        }

        if (anyCandidateExecutedSuccessfully) {
            return null
        }

        // 固定 AOSP 签名兜底
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            for (args in buildCommonArgs(methodName, clipData, listener)) {
                try {
                    val result = HiddenApiBypass.invoke(invokeClass, proxy, methodName, *args)
                    if (skipNullResult && result == null) {
                        lastAttempt = "AOSP兜底参数数=${args.size} 返回 null"
                        continue
                    }
                    Log.i(TAG, "HiddenApiBypass 调用 $methodName 成功 (参数数: ${args.size})")
                    return result
                } catch (e: Throwable) {
                    lastError = e
                    lastAttempt = "AOSP兜底参数数=${args.size}"
                    Log.w(
                        TAG,
                        "HiddenApiBypass 调用 $methodName 失败 (参数数: ${args.size}): " +
                            "${e.javaClass.simpleName}: ${e.message}"
                    )
                }
            }
        }

        // 普通反射兜底
        try {
            return invokeByReflection(proxy, methodName, listener, clipData)
        } catch (e: Throwable) {
            lastError = e
            lastAttempt = "普通反射: ${e.message}"
        }

        // 调用完全失败，清除计划缓存，触发下次调用时自愈重建
        invocationPlansCache.clear()

        throw IllegalStateException("$methodName 调用失败: $lastAttempt", lastError)
    }

    /**
     * 获取设备上某个方法名的全部“方法 x 候选实参”调用计划，缓存复用。
     */
    @Synchronized
    private fun getInvocationPlans(
        invokeClass: Class<*>,
        proxy: Any,
        methodName: String
    ): List<InvocationPlan> {
        invocationPlansCache[methodName]?.let { return it }
        val plans = buildInvocationPlans(invokeClass, proxy, methodName)
        if (plans.isNotEmpty()) {
            invocationPlansCache[methodName] = plans
        }
        return plans
    }

    private fun buildInvocationPlans(
        invokeClass: Class<*>,
        proxy: Any,
        methodName: String
    ): List<InvocationPlan> {
        val candidateClasses = LinkedHashSet<Class<*>>()
        candidateClasses.add(invokeClass)
        candidateClasses.add(proxy.javaClass)
        for (clazz in listOf(invokeClass, proxy.javaClass)) {
            for (method in getDeclaredMethods(clazz).filter { it.name == methodName }) {
                candidateClasses.add(method.declaringClass)
            }
        }

        val plans = ArrayList<InvocationPlan>()
        for (clazz in candidateClasses) {
            for (method in getDeclaredMethods(clazz).filter { it.name == methodName }) {
                val candidateCount = buildArgCandidates(method, null, null).size
                for (i in 0 until candidateCount) {
                    plans.add(InvocationPlan(clazz, method, i, describeMethod(method)))
                }
            }
        }
        return plans
    }

    /**
     * 用当前 clipData/listener 重建某条调用计划的具体实参并执行。
     */
    private fun invokePlannedMethod(
        plan: InvocationPlan,
        proxy: Any,
        methodName: String,
        clipData: ClipData?,
        listener: Any?
    ): Any? {
        val candidates = buildArgCandidates(plan.method, clipData, listener)
        val args = candidates.getOrNull(plan.candidateIndex)
            ?: throw IllegalStateException("候选实参重建失败 (签名=${plan.signature})")
        return HiddenApiBypass.invoke(plan.declaringClass, proxy, methodName, *args.toTypedArray())
    }

    /**
     * 按设备真实方法签名动态合成候选实参。
     *
     * 只按参数类型猜测语义，不依赖任何 ROM 的参数个数：
     * String 首位猜 callingPackage，其余猜 attributionTag/featureId 等可空字段；
     * int 首位猜 userId，第二位猜 deviceId；boolean 两个值都试。
     */
    private fun buildArgCandidates(
        method: Method,
        clipData: ClipData?,
        listener: Any?
    ): List<List<Any?>> {
        val choices = ArrayList<List<Any?>>(method.parameterCount)
        var stringIndex = 0
        var intIndex = 0
        var interfaceIndex = 0
        val myUserId = Process.myUid() / 100000
        val hasListenerParam = method.parameterTypes.any {
            it.isInterface && (it.name == LISTENER_CLASS || it.name.contains("PrimaryClipChanged"))
        }

        for (param in method.parameterTypes) {
            val paramChoices: List<Any?> = when {
                param == ClipData::class.java -> listOf(clipData)

                param == String::class.java -> {
                    stringIndex++
                    if (stringIndex == 1) {
                        listOf(SHELL_PACKAGE, appContext?.packageName, null)
                    } else {
                        listOf(null, SHELL_PACKAGE)
                    }
                }

                param == Int::class.javaPrimitiveType || param == java.lang.Integer::class.java -> {
                    intIndex++
                    if (intIndex == 1) {
                        listOf(myUserId, 0)
                    } else if (intIndex == 2) {
                        listOf(0, myUserId)
                    } else {
                        listOf(0)
                    }
                }

                param == Boolean::class.javaPrimitiveType || param == java.lang.Boolean::class.java ->
                    listOf(false, true)

                param == java.lang.Long::class.javaPrimitiveType || param == java.lang.Long::class.java ->
                    listOf(0L)

                param == java.lang.Float::class.javaPrimitiveType || param == java.lang.Float::class.java ->
                    listOf(0f)

                param == java.lang.Double::class.javaPrimitiveType || param == java.lang.Double::class.java ->
                    listOf(0.0)

                param == java.lang.Short::class.javaPrimitiveType || param == java.lang.Short::class.java ->
                    listOf(0.toShort())

                param == java.lang.Byte::class.javaPrimitiveType || param == java.lang.Byte::class.java ->
                    listOf(0.toByte())

                param == java.lang.Character::class.javaPrimitiveType || param == java.lang.Character::class.java ->
                    listOf('\u0000')

                param.isInterface -> {
                    interfaceIndex++
                    val isListenerType = param.name == LISTENER_CLASS || param.name.contains("PrimaryClipChanged")
                    if (listener != null && (isListenerType || (!hasListenerParam && interfaceIndex == 1))) {
                        listOf(listener)
                    } else {
                        listOf(null)
                    }
                }

                param.isArray -> listOf(null)
                else -> listOf(null)
            }
            choices.add(paramChoices)
        }

        return buildBoundedCandidates(choices)
    }

    /**
     * 从每参数候选值里生成有限组合：先基础组合，再逐个替换第二候选，
     * 最后补“全部 shell / 全部 null / 全部零值”三组定向组合。
     */
    private fun buildBoundedCandidates(choices: List<List<Any?>>): List<List<Any?>> {
        val result = LinkedHashSet<List<Any?>>()
        val base = IntArray(choices.size)

        fun addCombination(indexes: IntArray) {
            val combo = choices.indices.map { i -> choices[i][indexes[i]] }
            result.add(combo)
        }

        addCombination(base)
        for (i in choices.indices) {
            for (j in 1 until choices[i].size) {
                val alt = base.copyOf()
                alt[i] = j
                addCombination(alt)
            }
        }

        val allShell = IntArray(choices.size) { i ->
            val idx = choices[i].indexOfFirst { it == SHELL_PACKAGE }
            if (idx >= 0) idx else 0
        }
        addCombination(allShell)

        val allNull = IntArray(choices.size) { i ->
            val idx = choices[i].indexOfFirst { it == null }
            if (idx >= 0) idx else 0
        }
        addCombination(allNull)

        val allZero = IntArray(choices.size) { i ->
            val idx = choices[i].indexOfFirst {
                it == 0 || it == 0L || it == 0f || it == 0.0 || it == false || it == '\u0000'
            }
            if (idx >= 0) idx else 0
        }
        addCombination(allZero)

        return result.take(48).toList()
    }

    /**
     * Android 各版本 IClipboard 常见签名参数，按当前 SDK 优先排列。
     * 参数顺序统一为 (callingPackage, attributionTag, userId, deviceId)。
     */
    private fun buildCommonArgs(
        methodName: String,
        clipData: ClipData?,
        listener: Any?
    ): List<Array<out Any?>> {
        val myUserId = Process.myUid() / 100000
        val variants = when (methodName) {
            METHOD_GET -> listOf(
                arrayOf<Any?>(SHELL_PACKAGE, null, myUserId, 0), // 34+
                arrayOf<Any?>(SHELL_PACKAGE, null, myUserId),    // 31-33
                arrayOf<Any?>(SHELL_PACKAGE, myUserId),          // 29-30
                arrayOf<Any?>(SHELL_PACKAGE)                     // <=28
            )
            METHOD_SET -> listOf(
                arrayOf<Any?>(clipData, SHELL_PACKAGE, null, myUserId, 0), // 34+
                arrayOf<Any?>(clipData, SHELL_PACKAGE, null, myUserId),    // 31-33
                arrayOf<Any?>(clipData, SHELL_PACKAGE, myUserId),          // 29-30
                arrayOf<Any?>(clipData, SHELL_PACKAGE)                     // <=28
            )
            METHOD_LISTENER -> listOf(
                arrayOf<Any?>(listener, SHELL_PACKAGE, null, myUserId, 0), // 34+
                arrayOf<Any?>(listener, SHELL_PACKAGE, null, myUserId),    // 31-33
                arrayOf<Any?>(listener, SHELL_PACKAGE, myUserId),          // 29-30
                arrayOf<Any?>(listener, SHELL_PACKAGE)                     // <=28
            )
            else -> emptyList()
        }
        if (variants.isEmpty()) return variants

        val primaryIndex = when {
            Build.VERSION.SDK_INT >= 34 -> 0
            Build.VERSION.SDK_INT >= 31 -> 1
            Build.VERSION.SDK_INT >= 29 -> 2
            else -> 3
        }.coerceAtMost(variants.lastIndex)
        return listOf(variants[primaryIndex]) + variants.filterIndexed { i, _ -> i != primaryIndex }
    }

    /**
     * 低版本/异常时的普通反射兜底；全部失败抛异常，避免调用方误判为成功。
     */
    private fun invokeByReflection(
        proxy: Any,
        methodName: String,
        listener: Any? = null,
        clipData: ClipData? = null
    ): Any? {
        val methods = proxy.javaClass.methods.filter { it.name == methodName }
        if (methods.isEmpty()) {
            Log.e(TAG, "未找到 $methodName 方法")
            throw IllegalStateException("$methodName 方法不存在")
        }
        var lastError: Throwable? = null
        for (method in methods) {
            for (args in buildArgCandidates(method, clipData, listener)) {
                try {
                    return method.invoke(proxy, *args.toTypedArray())
                } catch (e: Throwable) {
                    lastError = e
                    Log.w(
                        TAG,
                        "反射调用 $methodName 重载失败 (签名=${describeMethod(method)}, " +
                            "参数=[${formatArgs(args)}]): ${e.javaClass.simpleName}: ${e.message}"
                    )
                }
            }
        }
        throw IllegalStateException("$methodName 全部反射调用失败", lastError)
    }

    private fun pollOnce() {
        if (!isReady()) return
        try {
            val text = readClipboard() ?: return
            if (text.isNotEmpty()) {
                val hash = CryptoUtil.computeHash(text)
                if (hash != lastPolledHash) {
                    lastPolledHash = hash
                    DebugLogger.log("Shizuku", "轮询发现剪贴板变化 (长度 ${text.length})")
                    pollCallback?.invoke()
                }
            }
        } catch (e: Throwable) {
            Log.w(TAG, "轮询读取 Shizuku 剪贴板异常: ${e.javaClass.simpleName}: ${e.message}")
        }
    }

    private fun getDeclaredMethods(clazz: Class<*>): List<Method> {
        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                HiddenApiBypass.getDeclaredMethods(clazz).filterIsInstance<Method>()
            } else {
                clazz.declaredMethods.toList()
            }
        } catch (e: Throwable) {
            Log.w(TAG, "枚举 ${clazz.name} 方法失败: ${e.javaClass.simpleName}: ${e.message}")
            emptyList()
        }
    }

    private fun describeMethod(method: Method): String {
        return "(${method.parameterTypes.joinToString(", ") { it.name }})"
    }

    private fun formatArgs(args: List<Any?>): String {
        return args.joinToString(", ") {
            it?.let { v -> if (v is String) "\"$v\"" else v.toString() } ?: "null"
        }
    }
}
