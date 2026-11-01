package com.crossclip.app.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import com.crossclip.app.service.SyncForegroundService
import com.crossclip.app.util.DebugLogger

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        val action = intent?.action ?: return
        if (action == Intent.ACTION_BOOT_COMPLETED ||
            action == Intent.ACTION_MY_PACKAGE_REPLACED ||
            action == "android.intent.action.QUICKBOOT_POWERON"
        ) {
            DebugLogger.init(context.applicationContext)
            DebugLogger.log("BOOT", "收到系统启动或更新广播 ($action)，自动拉起前台同步守护服务")
            try {
                val svcIntent = Intent(context, SyncForegroundService::class.java)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    context.startForegroundService(svcIntent)
                } else {
                    context.startService(svcIntent)
                }
            } catch (e: Exception) {
                DebugLogger.log("BOOT", "开机拉起服务失败: ${e.message}", e)
            }
        }
    }
}
