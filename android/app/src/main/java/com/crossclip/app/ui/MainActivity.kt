package com.crossclip.app.ui

import android.app.AlertDialog
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.SwitchCompat
import com.crossclip.app.BuildConfig
import com.crossclip.app.R
import com.crossclip.app.service.SyncForegroundService
import com.crossclip.app.shizuku.ShizukuClipboardManager
import com.crossclip.app.util.DebugLogger
import com.crossclip.app.util.PermissionHelper
import rikka.shizuku.Shizuku

class MainActivity : AppCompatActivity() {

    private lateinit var tvStatus: TextView
    private lateinit var tvAppTitle: TextView
    private lateinit var tvVersionBadge: TextView
    private lateinit var tvDiscoveredDevice: TextView
    private lateinit var btnSwitchDevice: TextView
    private lateinit var llDeviceHeader: View
    private lateinit var btnRefreshScan: TextView

    private lateinit var llConnectedBar: LinearLayout
    private lateinit var tvConnectedSummary: TextView
    private lateinit var btnDisconnectPc: Button
    private lateinit var llPinInputRow: LinearLayout
    private lateinit var etPinCode: EditText
    private lateinit var btnConnectPc: Button

    private lateinit var tvToggleManualIp: TextView
    private lateinit var llManualIpContainer: LinearLayout
    private lateinit var etManualIp: EditText
    private lateinit var btnApplyManualIp: Button

    private lateinit var switchAutoSync: SwitchCompat
    private lateinit var btnLockTaskGuide: Button
    private lateinit var switchHideRecents: SwitchCompat
    private lateinit var btnSilenceNotification: Button

    private lateinit var tvShizukuBadge: TextView
    private lateinit var tvShizukuDesc: TextView
    private lateinit var tvShizukuDebug: TextView
    private lateinit var btnShizukuAuth: Button
    private lateinit var cardManualSync: View
    private lateinit var btnTestSend: Button
    private lateinit var btnClipboardPerm: Button
    private lateinit var btnBatteryPerm: Button
    private lateinit var btnAutoStartPerm: Button
    private lateinit var llShizukuDebugContainer: LinearLayout
    private lateinit var tvCollapseDebug: TextView
    private val collapseDebugRunnable = Runnable {
        llShizukuDebugContainer.visibility = View.GONE
    }

    private lateinit var llKeepaliveHeader: View
    private lateinit var tvKeepaliveToggleArrow: TextView
    private lateinit var llKeepaliveContent: LinearLayout

    private lateinit var llLogsHeader: View
    private lateinit var tvLogsToggleArrow: TextView
    private lateinit var llLogsContent: LinearLayout
    private lateinit var tvLogFilePath: TextView
    private lateinit var btnViewLogs: Button
    private lateinit var btnCopyLogs: Button
    private lateinit var btnExportLogs: Button
    private lateinit var btnClearLogs: Button

    private val handler = Handler(Looper.getMainLooper())
    private var isDestroyedActivity = false

    private val shizukuPermissionListener = Shizuku.OnRequestPermissionResultListener { _, grantResult ->
        runOnUiThread {
            updateShizukuUI()
            if (grantResult == PackageManager.PERMISSION_GRANTED) {
                ShizukuClipboardManager.onPermissionGranted()
                DebugLogger.log("UI", "Shizuku 特权授权成功")
                Toast.makeText(this, "特权已激活，已驻留独立 Shell 剪贴板守护进程", Toast.LENGTH_LONG).show()
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        try {
            Shizuku.addRequestPermissionResultListener(shizukuPermissionListener)
        } catch (_: Throwable) {}

        ShizukuClipboardManager.init(applicationContext)
        DebugLogger.init(applicationContext)
        DebugLogger.log("APP_LIFECYCLE", "MainActivity.onCreate 版本: v${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})")
        initViews()
        loadConfig()
        applyHideFromRecentsPreference()
        startSyncService()
        startStatusPolling()
        runShizukuSelfTest()
        promptMiuiKeepAliveIfNeeded()
    }

    override fun onResume() {
        super.onResume()
        applyHideFromRecentsPreference()
        SyncForegroundService.instance?.let { svc ->
            if (svc.connectionState != 1 && svc.pinCode.isNotEmpty()) {
                svc.triggerFastReconnect("MainActivity.onResume")
            }
        }
    }

    private fun applyHideFromRecentsPreference() {
        val sp = getSharedPreferences("cross_clip_config", Context.MODE_PRIVATE)
        val hideRecents = sp.getBoolean("hide_recents", false)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            val am = getSystemService(Context.ACTIVITY_SERVICE) as? android.app.ActivityManager
            am?.appTasks?.forEach { task ->
                try {
                    task.setExcludeFromRecents(hideRecents)
                } catch (_: Exception) {}
            }
        }
    }

    private fun initViews() {
        tvAppTitle = findViewById(R.id.tv_app_title)
        tvVersionBadge = findViewById(R.id.tv_version_badge)
        tvVersionBadge.text = "v${BuildConfig.VERSION_NAME}"
        tvStatus = findViewById(R.id.tv_status)
        tvDiscoveredDevice = findViewById(R.id.tv_discovered_device)
        btnSwitchDevice = findViewById(R.id.btn_switch_device)
        llDeviceHeader = findViewById(R.id.ll_device_header)
        btnRefreshScan = findViewById(R.id.btn_refresh_scan)

        llConnectedBar = findViewById(R.id.ll_connected_bar)
        tvConnectedSummary = findViewById(R.id.tv_connected_summary)
        btnDisconnectPc = findViewById(R.id.btn_disconnect_pc)
        llPinInputRow = findViewById(R.id.ll_pin_input_row)
        etPinCode = findViewById(R.id.et_pin_code)
        btnConnectPc = findViewById(R.id.btn_connect_pc)

        tvToggleManualIp = findViewById(R.id.tv_toggle_manual_ip)
        llManualIpContainer = findViewById(R.id.ll_manual_ip_container)
        etManualIp = findViewById(R.id.et_manual_ip)
        btnApplyManualIp = findViewById(R.id.btn_apply_manual_ip)

        val onDeviceSelectClick = View.OnClickListener {
            showDeviceSelectionDialog()
        }
        btnSwitchDevice.setOnClickListener(onDeviceSelectClick)
        llDeviceHeader.setOnClickListener(onDeviceSelectClick)

        switchAutoSync = findViewById(R.id.switch_auto_sync)
        btnLockTaskGuide = findViewById(R.id.btn_lock_task_guide)
        switchHideRecents = findViewById(R.id.switch_hide_recents)
        btnSilenceNotification = findViewById(R.id.btn_silence_notification)

        tvShizukuBadge = findViewById(R.id.tv_shizuku_badge)
        tvShizukuDesc = findViewById(R.id.tv_shizuku_desc)
        tvShizukuDebug = findViewById(R.id.tv_shizuku_debug)
        btnShizukuAuth = findViewById(R.id.btn_shizuku_auth)
        cardManualSync = findViewById(R.id.card_manual_sync)
        btnTestSend = findViewById(R.id.btn_test_send)
        btnClipboardPerm = findViewById(R.id.btn_clipboard_perm)
        btnBatteryPerm = findViewById(R.id.btn_battery_perm)
        btnAutoStartPerm = findViewById(R.id.btn_autostart_perm)
        llShizukuDebugContainer = findViewById(R.id.ll_shizuku_debug_container)
        tvCollapseDebug = findViewById(R.id.tv_collapse_debug)

        llKeepaliveHeader = findViewById(R.id.ll_keepalive_header)
        tvKeepaliveToggleArrow = findViewById(R.id.tv_keepalive_toggle_arrow)
        llKeepaliveContent = findViewById(R.id.ll_keepalive_content)

        llLogsHeader = findViewById(R.id.ll_logs_header)
        tvLogsToggleArrow = findViewById(R.id.tv_logs_toggle_arrow)
        llLogsContent = findViewById(R.id.ll_logs_content)
        tvLogFilePath = findViewById(R.id.tv_log_file_path)
        btnViewLogs = findViewById(R.id.btn_view_logs)
        btnCopyLogs = findViewById(R.id.btn_copy_logs)
        btnExportLogs = findViewById(R.id.btn_export_logs)
        btnClearLogs = findViewById(R.id.btn_clear_logs)

        tvLogFilePath.text = "日志路径: ${DebugLogger.getLogFilePath()}"

        llKeepaliveHeader.setOnClickListener {
            val expanding = llKeepaliveContent.visibility != View.VISIBLE
            llKeepaliveContent.visibility = if (expanding) View.VISIBLE else View.GONE
            tvKeepaliveToggleArrow.text = if (expanding) "收起" else "展开"
        }

        llLogsHeader.setOnClickListener {
            val expanding = llLogsContent.visibility != View.VISIBLE
            llLogsContent.visibility = if (expanding) View.VISIBLE else View.GONE
            tvLogsToggleArrow.text = if (expanding) "收起" else "展开"
        }

        btnViewLogs.setOnClickListener {
            showLogsDialog()
        }

        btnCopyLogs.setOnClickListener {
            val allLogs = DebugLogger.readAll()
            val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            cm.setPrimaryClip(ClipData.newPlainText("CrossClip Logs", allLogs))
            Toast.makeText(this, "已复制全量运行日志", Toast.LENGTH_SHORT).show()
        }

        btnExportLogs.setOnClickListener {
            DebugLogger.shareLogFile(this)
        }

        btnClearLogs.setOnClickListener {
            DebugLogger.clear()
            Toast.makeText(this, "日志已清空", Toast.LENGTH_SHORT).show()
        }

        btnShizukuAuth.setOnClickListener {
            when (ShizukuClipboardManager.getState()) {
                ShizukuClipboardManager.State.READY -> {
                    handler.removeCallbacks(collapseDebugRunnable)
                    llShizukuDebugContainer.visibility = View.VISIBLE
                    tvShizukuDebug.text = "正在执行底层剪贴板读写与回读自检..."
                    Thread {
                        SyncForegroundService.instance?.setSelfTestWriteMode(true)
                        val result = try {
                            ShizukuClipboardManager.debugFullSelfTest()
                        } finally {
                            SyncForegroundService.instance?.setSelfTestWriteMode(false)
                        }
                        runOnUiThread {
                            tvShizukuDebug.text = result
                            handler.postDelayed(collapseDebugRunnable, 8000L)
                        }
                    }.start()
                }
                ShizukuClipboardManager.State.UNAUTHORIZED -> {
                    ShizukuClipboardManager.requestPermission()
                }
                ShizukuClipboardManager.State.NOT_RUNNING -> {
                    try {
                        val launchIntent = packageManager.getLaunchIntentForPackage("moe.shizuku.privileged.api")
                        if (launchIntent != null) {
                            startActivity(launchIntent)
                        } else {
                            Toast.makeText(this, "未检测到 Shizuku 应用，请先安装并启动一次 Shizuku", Toast.LENGTH_LONG).show()
                        }
                    } catch (e: Exception) {
                        Toast.makeText(this, "未检测到 Shizuku，当前使用标准兼容模式", Toast.LENGTH_LONG).show()
                    }
                }
            }
        }

        btnRefreshScan.setOnClickListener {
            Toast.makeText(this, "正在重新扫描局域网电脑...", Toast.LENGTH_SHORT).show()
            val service = SyncForegroundService.instance
            if (service != null) {
                service.triggerRescan()
            } else {
                startSyncService()
            }
        }

        tvToggleManualIp.setOnClickListener {
            if (llManualIpContainer.visibility == View.VISIBLE) {
                llManualIpContainer.visibility = View.GONE
            } else {
                llManualIpContainer.visibility = View.VISIBLE
            }
        }

        btnApplyManualIp.setOnClickListener {
            val ip = etManualIp.text.toString().trim()
            if (ip.isEmpty()) {
                Toast.makeText(this, "请输入电脑的有效 IP 地址", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            val service = SyncForegroundService.instance
            if (service != null) {
                val pin = etPinCode.text.toString().trim().ifEmpty { service.pinCode }
                if (pin.isEmpty()) {
                    Toast.makeText(this, "请先输入 6 位 PIN 码", Toast.LENGTH_SHORT).show()
                    return@setOnClickListener
                }
                service.connectWithPin(ip, pin) { success, statusCode, name ->
                    if (success) {
                        Toast.makeText(this, "已连接至电脑: $name", Toast.LENGTH_SHORT).show()
                    } else if (statusCode == 403) {
                        Toast.makeText(this, "PIN 码不匹配，请核对电脑托盘显示的 6 位数字", Toast.LENGTH_LONG).show()
                    } else {
                        Toast.makeText(this, "连接超时，请确认电脑 IP 与防火墙状态", Toast.LENGTH_SHORT).show()
                    }
                }
            }
        }

        switchAutoSync.setOnCheckedChangeListener { _, isChecked ->
            cardManualSync.visibility = if (isChecked) View.GONE else View.VISIBLE
            val sp = getSharedPreferences("cross_clip_config", Context.MODE_PRIVATE)
            sp.edit().putBoolean("auto_sync", isChecked).apply()
            startSyncService()
        }

        btnLockTaskGuide.setOnClickListener {
            showLockTaskGuideDialog()
        }

        switchHideRecents.setOnCheckedChangeListener { _, isChecked ->
            val sp = getSharedPreferences("cross_clip_config", Context.MODE_PRIVATE)
            sp.edit().putBoolean("hide_recents", isChecked).apply()
            applyHideFromRecentsPreference()
            if (isChecked) {
                Toast.makeText(this, "已开启最近任务隐藏，退至桌面后在多任务列表中隐身", Toast.LENGTH_LONG).show()
            } else {
                Toast.makeText(this, "已关闭最近任务隐藏", Toast.LENGTH_SHORT).show()
            }
        }

        btnSilenceNotification.setOnClickListener {
            SyncForegroundService.instance?.hideForegroundNotification()
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                try {
                    val intent = Intent(android.provider.Settings.ACTION_CHANNEL_NOTIFICATION_SETTINGS).apply {
                        putExtra(android.provider.Settings.EXTRA_APP_PACKAGE, packageName)
                        putExtra(android.provider.Settings.EXTRA_CHANNEL_ID, SyncForegroundService.CHANNEL_ID)
                    }
                    startActivity(intent)
                    Toast.makeText(this, "在系统页面关闭允许通知即可隐藏通知栏提示", Toast.LENGTH_LONG).show()
                } catch (_: Exception) {
                    val appIntent = Intent(android.provider.Settings.ACTION_APP_NOTIFICATION_SETTINGS).apply {
                        putExtra(android.provider.Settings.EXTRA_APP_PACKAGE, packageName)
                    }
                    startActivity(appIntent)
                }
            } else {
                Toast.makeText(this, "已隐藏通知中心常驻通知", Toast.LENGTH_SHORT).show()
            }
        }

        btnDisconnectPc.setOnClickListener {
            val service = SyncForegroundService.instance ?: return@setOnClickListener
            service.disconnectCurrentPc()
            llConnectedBar.visibility = View.GONE
            llPinInputRow.visibility = View.VISIBLE
            tvToggleManualIp.visibility = View.VISIBLE
            etPinCode.isEnabled = true
            btnConnectPc.text = "连接电脑"
            btnConnectPc.isEnabled = true
            Toast.makeText(this, "已断开当前电脑连接", Toast.LENGTH_SHORT).show()
        }

        btnConnectPc.setOnClickListener {
            val service = SyncForegroundService.instance
            if (service == null) {
                Toast.makeText(this, "后台服务启动中，请稍候...", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }

            if (service.connectionState == 1) {
                service.disconnectCurrentPc()
                etPinCode.isEnabled = true
                btnConnectPc.text = "连接电脑"
                Toast.makeText(this, "已断开与电脑的连接", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }

            val pin = etPinCode.text.toString().trim()
            if (pin.length != 6) {
                Toast.makeText(this, "请输入电脑托盘显示的 6 位 PIN 码", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }

            var targetIp = service.currentPcIp
            if (targetIp.isEmpty()) {
                val devices = service.getDiscoveredDeviceList()
                targetIp = devices.firstOrNull { it.deviceId == service.currentTargetDeviceId }?.ip
                    ?: devices.firstOrNull()?.ip
                    ?: service.lastSavedPcIp
            }
            if (targetIp.isEmpty()) {
                val manualIp = etManualIp.text.toString().trim()
                if (manualIp.isNotEmpty()) {
                    targetIp = manualIp
                }
            }

            if (targetIp.isEmpty()) {
                Toast.makeText(this, "尚未发现电脑，请确认电脑已启动 CrossClip 或手动指定 IP", Toast.LENGTH_LONG).show()
                llManualIpContainer.visibility = View.VISIBLE
                return@setOnClickListener
            }

            btnConnectPc.isEnabled = false
            btnConnectPc.text = "握手中..."

            service.connectWithPin(targetIp, pin) { success, statusCode, name ->
                btnConnectPc.isEnabled = true
                if (success) {
                    btnConnectPc.text = "连接电脑"
                    Toast.makeText(this, "已连接至 $name", Toast.LENGTH_SHORT).show()
                } else if (statusCode == 403) {
                    btnConnectPc.text = "连接电脑"
                    etPinCode.isEnabled = true
                    Toast.makeText(this, "PIN 码不匹配，请右键电脑右下角托盘核对 6 位 PIN 码", Toast.LENGTH_LONG).show()
                } else {
                    btnConnectPc.text = "连接电脑"
                    etPinCode.isEnabled = true
                    Toast.makeText(this, "连接超时，请确认手机与电脑处于同一局域网", Toast.LENGTH_SHORT).show()
                }
            }
        }

        btnTestSend.setOnClickListener {
            SyncForegroundService.instance?.sendCurrentClipboardManual()
                ?: Toast.makeText(this, "后台服务未就绪，正在拉起...", Toast.LENGTH_SHORT).show()
        }

        btnClipboardPerm.setOnClickListener {
            PermissionHelper.openClipboardPermissionSetting(this)
        }

        btnBatteryPerm.setOnClickListener {
            PermissionHelper.openBatteryKeepAliveSettings(this)
        }

        btnAutoStartPerm.setOnClickListener {
            PermissionHelper.openAutoStartPermissionSetting(this)
        }

        tvCollapseDebug.setOnClickListener {
            handler.removeCallbacks(collapseDebugRunnable)
            llShizukuDebugContainer.visibility = View.GONE
        }

        tvShizukuDebug.setOnClickListener {
            showLogsDialog()
        }
    }

    private fun showLogsDialog() {
        val dialog = AlertDialog.Builder(this)
            .setTitle("CrossClip 运行日志 (最新 200 行)")
            .setMessage(DebugLogger.readTail(200))
            .setPositiveButton("确定", null)
            .setNegativeButton("复制全部") { _, _ ->
                val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                cm.setPrimaryClip(ClipData.newPlainText("CrossClip Logs", DebugLogger.readAll()))
                Toast.makeText(this, "已复制全部日志", Toast.LENGTH_SHORT).show()
            }
            .setNeutralButton("导出文件") { _, _ ->
                DebugLogger.shareLogFile(this)
            }
            .create()
        dialog.show()
    }

    private fun showLockTaskGuideDialog() {
        val guideMessage = StringBuilder()
            .append("在系统多任务切换界面为 CrossClip 卡片加锁，可防止系统清理后台时误杀同步服务：\n\n")
            .append("1. 小米、澎湃 OS (HyperOS / MIUI)：\n")
            .append("   进入多任务界面，长按 CrossClip 卡片点击锁头图标，或向下拉动卡片加锁。\n\n")
            .append("2. OPPO、一加、真我 (ColorOS / RealmeUI)：\n")
            .append("   进入多任务界面，点击卡片右上角菜单选择锁定。\n\n")
            .append("3. vivo、iQOO (OriginOS) / 华为、荣耀：\n")
            .append("   进入多任务界面，向下拉动 CrossClip 卡片即可显示锁定标记。")
            .toString()

        AlertDialog.Builder(this)
            .setTitle("多任务卡片加锁指引")
            .setMessage(guideMessage)
            .setPositiveButton("知道了", null)
            .show()
    }

    private fun loadConfig() {
        val sp = getSharedPreferences("cross_clip_config", Context.MODE_PRIVATE)
        val isAuto = sp.getBoolean("auto_sync", true)
        switchAutoSync.isChecked = isAuto
        switchHideRecents.isChecked = sp.getBoolean("hide_recents", false)
        cardManualSync.visibility = if (isAuto) View.GONE else View.VISIBLE

        val savedPin = sp.getString("pin_code", "") ?: ""
        if (savedPin.isNotEmpty()) {
            etPinCode.setText(savedPin)
        }

        val lastIp = sp.getString("last_pc_ip", "") ?: ""
        if (lastIp.isNotEmpty()) {
            etManualIp.setText(lastIp)
        }
    }

    private fun showDeviceSelectionDialog() {
        val service = SyncForegroundService.instance
        if (service == null) {
            Toast.makeText(this, "后台服务未就绪，请稍候...", Toast.LENGTH_SHORT).show()
            return
        }
        val devices = service.getDiscoveredDeviceList()
        if (devices.isEmpty()) {
            AlertDialog.Builder(this)
                .setTitle("未发现在线电脑")
                .setMessage("当前局域网未探测到在线电脑。\n\n请确认：\n1. 手机与电脑已连入同一 Wi-Fi 局域网\n2. 电脑端 CrossClip.exe 正在运行\n3. 若刚连回 Wi-Fi，可点击重新扫描立即唤醒")
                .setPositiveButton("重新扫描") { _, _ ->
                    service.triggerRescan()
                    Toast.makeText(this, "正在重新搜索局域网电脑...", Toast.LENGTH_SHORT).show()
                }
                .setNegativeButton("取消", null)
                .setNeutralButton("清除配对记录") { _, _ ->
                    service.clearSavedHistory()
                    etPinCode.setText("")
                    etManualIp.setText("")
                    Toast.makeText(this, "已清除历史电脑与配对记录", Toast.LENGTH_SHORT).show()
                }
                .show()
            return
        }

        val items = devices.map { dev ->
            val isCurrent = (service.connectionState == 1 && dev.ip == service.currentPcIp)
            val tag = if (isCurrent) " · 当前连接" else ""
            "${dev.name} (${dev.ip})$tag"
        }.toTypedArray()

        var selectedIndex = devices.indexOfFirst { service.connectionState == 1 && it.ip == service.currentPcIp }
        if (selectedIndex < 0) {
            selectedIndex = devices.indexOfFirst { it.deviceId == service.currentTargetDeviceId || it.ip == service.currentPcIp }
        }
        if (selectedIndex < 0) selectedIndex = 0

        AlertDialog.Builder(this)
            .setTitle("选择连接的电脑 (${devices.size} 台在线)")
            .setSingleChoiceItems(items, selectedIndex) { dialog, which ->
                val chosen = devices[which]
                service.selectTargetDevice(chosen)
                val sp = getSharedPreferences("cross_clip_config", Context.MODE_PRIVATE)
                val devPin = sp.getString("pin_code_${chosen.deviceId}", "") ?: ""
                if (devPin.isNotEmpty()) {
                    etPinCode.setText(devPin)
                }
                etPinCode.isEnabled = true
                btnConnectPc.text = "连接电脑"
                Toast.makeText(this, "已切换目标电脑: ${chosen.name} (${chosen.ip})", Toast.LENGTH_SHORT).show()
                dialog.dismiss()
            }
            .setNegativeButton("取消", null)
            .setNeutralButton("重新扫描") { _, _ ->
                service.triggerRescan()
                Toast.makeText(this, "正在重新搜索局域网电脑...", Toast.LENGTH_SHORT).show()
            }
            .show()
    }

    private fun startStatusPolling() {
        handler.post(object : Runnable {
            override fun run() {
                if (isDestroyedActivity) return
                val service = SyncForegroundService.instance
                if (service != null) {
                    val pcName = if (service.currentPcName.isNotEmpty() && service.currentPcName != "未连接") {
                        service.currentPcName
                    } else {
                        "Windows 电脑"
                    }

                    val deviceList = service.getDiscoveredDeviceList()
                    val devCount = deviceList.size

                    val liveTarget = deviceList.firstOrNull { it.deviceId == service.currentTargetDeviceId }
                        ?: deviceList.firstOrNull { service.currentPcIp.isNotEmpty() && it.ip == service.currentPcIp }
                        ?: deviceList.firstOrNull()
                    val activeIp = if (service.connectionState == 1) {
                        service.currentPcIp
                    } else {
                        liveTarget?.ip ?: ""
                    }
                    val activeName = if (service.connectionState == 1) {
                        pcName
                    } else {
                        liveTarget?.name ?: pcName
                    }

                    if (activeIp.isNotEmpty() && (service.connectionState == 1 || liveTarget != null)) {
                        if (devCount > 1) {
                            tvDiscoveredDevice.text = "在线设备 ($devCount 台): $activeName · $activeIp"
                            btnSwitchDevice.visibility = View.VISIBLE
                            btnSwitchDevice.text = "切换 ($devCount)"
                        } else {
                            tvDiscoveredDevice.text = "在线设备: $activeName · $activeIp"
                            btnSwitchDevice.visibility = View.VISIBLE
                            btnSwitchDevice.text = "选择"
                        }
                        tvDiscoveredDevice.setTextColor(Color.parseColor("#0D131A"))
                    } else {
                        if (devCount > 0) {
                            tvDiscoveredDevice.text = "发现在线电脑 ($devCount 台)，点击选择"
                            tvDiscoveredDevice.setTextColor(Color.parseColor("#0D131A"))
                            btnSwitchDevice.visibility = View.VISIBLE
                            btnSwitchDevice.text = "选择 ($devCount)"
                        } else {
                            val hint = if (service.lastSavedPcIp.isNotEmpty()) {
                                "等待历史电脑 (${service.lastSavedPcIp}) 上线..."
                            } else {
                                "正在扫描当前 Wi-Fi 局域网内的电脑..."
                            }
                            tvDiscoveredDevice.text = hint
                            tvDiscoveredDevice.setTextColor(Color.parseColor("#475569"))
                            btnSwitchDevice.visibility = View.GONE
                        }
                    }

                    when (service.connectionState) {
                        1 -> {
                            tvStatus.text = "● 已连接 · $pcName (${service.currentPcIp})"
                            tvStatus.setTextColor(Color.parseColor("#059669"))
                            llConnectedBar.visibility = View.VISIBLE
                            llPinInputRow.visibility = View.GONE
                            tvToggleManualIp.visibility = View.GONE
                            llManualIpContainer.visibility = View.GONE
                        }
                        2 -> {
                            tvStatus.text = "● PIN 码不匹配，请核对电脑端 6 位 PIN 码后重连"
                            tvStatus.setTextColor(Color.parseColor("#DC2626"))
                            llConnectedBar.visibility = View.GONE
                            llPinInputRow.visibility = View.VISIBLE
                            tvToggleManualIp.visibility = View.VISIBLE
                            btnConnectPc.text = "连接电脑"
                            btnConnectPc.isEnabled = true
                            etPinCode.isEnabled = true
                        }
                        -1 -> {
                            tvStatus.text = "● 正在执行 AES-256-GCM 密文挑战握手..."
                            tvStatus.setTextColor(Color.parseColor("#D97706"))
                            llConnectedBar.visibility = View.GONE
                            llPinInputRow.visibility = View.VISIBLE
                            btnConnectPc.text = "握手中..."
                            btnConnectPc.isEnabled = false
                        }
                        else -> {
                            llConnectedBar.visibility = View.GONE
                            llPinInputRow.visibility = View.VISIBLE
                            tvToggleManualIp.visibility = View.VISIBLE
                            btnConnectPc.text = "连接电脑"
                            btnConnectPc.isEnabled = true
                            etPinCode.isEnabled = true
                            if (liveTarget != null) {
                                tvStatus.text = "● 已发现电脑，输入 6 位 PIN 码即可建立加密连接"
                                tvStatus.setTextColor(Color.parseColor("#1E40AF"))
                            } else {
                                tvStatus.text = "● 尚未连接电脑，回到局域网后将自动秒连"
                                tvStatus.setTextColor(Color.parseColor("#475569"))
                            }
                        }
                    }
                }
                updateShizukuUI()
                handler.postDelayed(this, 1200)
            }
        })
    }

    private fun promptMiuiKeepAliveIfNeeded() {
        val sp = getSharedPreferences("cross_clip_config", Context.MODE_PRIVATE)
        if (sp.getBoolean("miui_keepalive_prompted", false)) return
        val manufacturer = Build.MANUFACTURER.lowercase()
        if (!manufacturer.contains("xiaomi") && !manufacturer.contains("redmi")) return

        sp.edit().putBoolean("miui_keepalive_prompted", true).apply()

        val powerManager = getSystemService(Context.POWER_SERVICE) as PowerManager
        if (powerManager.isIgnoringBatteryOptimizations(packageName)) return

        AlertDialog.Builder(this)
            .setTitle("开启后台无限制")
            .setMessage(
                "MIUI / HyperOS 默认会限制后台网络，建议将 CrossClip 省电策略设为无限制并开启自启动，确保息屏与离开局域网再回时秒级重连。"
            )
            .setPositiveButton("前往设置") { _, _ ->
                PermissionHelper.openBatteryKeepAliveSettings(this)
            }
            .setNegativeButton("稍后", null)
            .show()
    }

    private fun updateShizukuUI() {
        when (ShizukuClipboardManager.getState()) {
            ShizukuClipboardManager.State.READY -> {
                if (ShizukuClipboardManager.isDaemonAliveOnly()) {
                    tvShizukuBadge.text = "● 独立守护运行中 (免疫断网)"
                    tvShizukuBadge.setTextColor(Color.parseColor("#059669"))
                    tvShizukuDesc.text = "Shizuku 已退出，但 UID 2000 独立守护进程仍在后台常驻，100% 保持无感剪贴板同步。"
                } else {
                    tvShizukuBadge.text = "● 特权直连 + 独立守护双激活"
                    tvShizukuBadge.setTextColor(Color.parseColor("#059669"))
                    tvShizukuDesc.text = "已激活 Shizuku 与 UID 2000 独立守护双通道，即便离开 Wi-Fi 无线调试关闭也不断连。"
                }
                btnShizukuAuth.text = "链路自检"
                btnShizukuAuth.isEnabled = true
            }
            ShizukuClipboardManager.State.UNAUTHORIZED -> {
                tvShizukuBadge.text = "● 待授权"
                tvShizukuBadge.setTextColor(Color.parseColor("#D97706"))
                tvShizukuDesc.text = "检测到 Shizuku 正在运行，点击右侧按钮授权即可驻留 UID 2000 独立守护进程。"
                btnShizukuAuth.text = "去授权"
                btnShizukuAuth.isEnabled = true
            }
            ShizukuClipboardManager.State.NOT_RUNNING -> {
                tvShizukuBadge.text = "● 待激活 (开机仅需一次)"
                tvShizukuBadge.setTextColor(Color.parseColor("#475569"))
                tvShizukuDesc.text = "首次启动一次 Shizuku 即可注入独立 Shell 守护进程；只要手机不重启，后续Shizuku 关机也不影响同步。"
                btnShizukuAuth.text = "打开 Shizuku"
                btnShizukuAuth.isEnabled = true
            }
        }
    }

    private fun startSyncService() {
        val intent = Intent(this, SyncForegroundService::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(intent)
        } else {
            startService(intent)
        }
    }

    private fun runShizukuSelfTest() {
        tvShizukuDebug.text = "正在自检剪贴板守护链路..."
        Thread {
            val result = ShizukuClipboardManager.debugReadClipboard()
            runOnUiThread {
                tvShizukuDebug.text = "自检: $result"
            }
        }.start()
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        DebugLogger.log("UI", "用户触发返回键，将 Activity 移至后台 (moveTaskToBack)")
        moveTaskToBack(true)
    }

    override fun onDestroy() {
        super.onDestroy()
        try {
            Shizuku.removeRequestPermissionResultListener(shizukuPermissionListener)
        } catch (_: Throwable) {}
        isDestroyedActivity = true
        DebugLogger.log("APP_LIFECYCLE", "MainActivity.onDestroy")
    }
}
