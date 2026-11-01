package com.crossclip.app.network

import android.content.Context
import android.net.wifi.WifiManager
import android.util.Log
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.Inet4Address
import java.net.InetAddress
import java.net.NetworkInterface
import java.util.concurrent.TimeUnit

class LanDiscovery(
    private val context: Context,
    private val udpPort: Int = 18234,
    private val onDeviceFound: (deviceId: String, deviceName: String, ip: String, httpPort: Int) -> Unit
) {
    private val TAG = "CrossClipDiscovery"

    @Volatile
    private var isSearching = false
    @Volatile
    var isConnected = false

    private val isSubnetScanning = java.util.concurrent.atomic.AtomicBoolean(false)
    private val scanGeneration = java.util.concurrent.atomic.AtomicLong(0)
    private val sleepMonitor = Object()

    @Volatile
    private var socket: DatagramSocket? = null
    private var multicastLock: WifiManager.MulticastLock? = null
    private var scanExecutor: java.util.concurrent.ExecutorService? = null
    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(450, TimeUnit.MILLISECONDS)
        .readTimeout(450, TimeUnit.MILLISECONDS)
        .build()

    private fun acquireMulticastLock() {
        try {
            val wifiManager = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
            if (wifiManager != null) {
                multicastLock = wifiManager.createMulticastLock("CrossClipMulticastLock").apply {
                    setReferenceCounted(true)
                    acquire()
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "获取 MulticastLock 异常: ${e.message}")
        }
    }

    private fun releaseMulticastLock() {
        try {
            if (multicastLock?.isHeld == true) {
                multicastLock?.release()
            }
        } catch (e: Exception) {
            Log.w(TAG, "释放 MulticastLock 异常: ${e.message}")
        }
    }

    private fun getBroadcastAddresses(): List<InetAddress> {
        val addresses = mutableListOf<InetAddress>()
        try {
            val interfaces = NetworkInterface.getNetworkInterfaces()
            while (interfaces.hasMoreElements()) {
                val networkInterface = interfaces.nextElement()
                if (networkInterface.isLoopback || !networkInterface.isUp) continue
                for (interfaceAddress in networkInterface.interfaceAddresses) {
                    val broadcast = interfaceAddress.broadcast
                    if (broadcast != null) {
                        addresses.add(broadcast)
                    }
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "获取广播地址异常: ${e.message}")
        }
        try {
            addresses.add(InetAddress.getByName("255.255.255.255"))
        } catch (_: Exception) {}
        return addresses.distinct()
    }

    private fun getLocalIpv4List(): List<String> {
        val ips = mutableListOf<String>()
        try {
            val interfaces = NetworkInterface.getNetworkInterfaces()
            while (interfaces.hasMoreElements()) {
                val networkInterface = interfaces.nextElement()
                if (networkInterface.isLoopback || !networkInterface.isUp) continue
                for (addr in networkInterface.inetAddresses) {
                    if (addr is Inet4Address && !addr.isLoopbackAddress) {
                        ips.add(addr.hostAddress ?: "")
                    }
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "获取本地 IPv4 异常: ${e.message}")
        }
        return ips
    }

    @Volatile
    private var currentHintIp: String = ""
    @Volatile
    private var currentDeviceId: String = "android"
    @Volatile
    private var currentDeviceName: String = "安卓手机"

    fun startDiscovery(deviceId: String = "android", deviceName: String = "安卓手机", hintIp: String = "") {
        if (hintIp.isNotEmpty()) {
            currentHintIp = hintIp
        }
        currentDeviceId = deviceId
        currentDeviceName = deviceName
        if (isSearching) {
            requestImmediateScan(hintIp)
            return
        }
        isSearching = true
        acquireMulticastLock()

        if (scanExecutor == null || scanExecutor?.isShutdown == true || scanExecutor?.isTerminated == true) {
            scanExecutor = java.util.concurrent.Executors.newFixedThreadPool(20)
        }

        // 1. 启动 UDP 广播探测与应答监听
        Thread {
            try {
                socket = DatagramSocket(null).apply {
                    reuseAddress = true
                    broadcast = true
                    bind(java.net.InetSocketAddress(udpPort))
                }
            } catch (e: Exception) {
                try {
                    socket = DatagramSocket().apply { broadcast = true }
                } catch (ex: Exception) {
                    Log.e(TAG, "创建 UDP Socket 失败: ${ex.message}")
                }
            }

            // 监听应答线程
            Thread {
                val buf = ByteArray(2048)
                while (isSearching) {
                    try {
                        val packet = DatagramPacket(buf, buf.size)
                        socket?.receive(packet)
                        val text = String(packet.data, 0, packet.length, Charsets.UTF_8)
                        val fromIp = packet.address.hostAddress ?: ""
                        if (fromIp.isNotEmpty() && !fromIp.startsWith("127.")) {
                            val localIps = getLocalIpv4List()
                            if (localIps.contains(fromIp)) {
                                continue
                            }

                            val json = JSONObject(text)
                            val msgType = json.optString("type", "")
                            if (msgType != "OFFER") {
                                continue
                            }

                            val devId = json.optString("device_id", "")
                            if (devId == currentDeviceId || devId.startsWith("android_")) {
                                continue
                            }

                            val actualDevId = if (devId.isNotEmpty()) devId else "pc_${fromIp.replace('.', '_')}"
                            val httpPort = json.optInt("http_port", 18236)
                            val pcName = json.optString("device_name", "Windows 电脑")

                            var targetIp = fromIp
                            val ipsArray = json.optJSONArray("ips")
                            if (ipsArray != null && ipsArray.length() > 0) {
                                for (i in 0 until ipsArray.length()) {
                                    val candidateIp = ipsArray.optString(i)
                                    if (candidateIp.isNotEmpty() && isSameSubnetAny(candidateIp, localIps)) {
                                        targetIp = candidateIp
                                        break
                                    }
                                }
                            }

                            Log.i(TAG, "通过 UDP 发现电脑: $pcName ($actualDevId) -> $targetIp:$httpPort")
                            onDeviceFound(actualDevId, pcName, targetIp, httpPort)
                        }
                    } catch (e: Exception) {
                        if (!isSearching) break
                    }
                }
            }.apply {
                isDaemon = true
                name = "CrossClip-UdpRecv"
                start()
            }

            // 广播发送与周期扫描主循环
            while (isSearching) {
                sendUdpBroadcastBurst()

                if (!isConnected) {
                    if (currentHintIp.isNotEmpty()) {
                        probeSingleIpNow(currentHintIp)
                    }
                    fastSubnetScan()
                    var interrupted = false
                    synchronized(sleepMonitor) {
                        try {
                            sleepMonitor.wait(8000L)
                        } catch (_: InterruptedException) {
                            interrupted = true
                        }
                    }
                    if (interrupted) break
                } else {
                    var interrupted = false
                    synchronized(sleepMonitor) {
                        try {
                            sleepMonitor.wait(45000L)
                        } catch (_: InterruptedException) {
                            interrupted = true
                        }
                    }
                    if (interrupted) break
                }
            }
        }.apply {
            isDaemon = true
            name = "CrossClip-UdpLoop"
            start()
        }
    }

    /**
     * 立即发送一簇 UDP 广播探测包 (双发防 Wi-Fi 切换初期丢包)
     */
    fun sendUdpBroadcastBurst() {
        val reqMsg = JSONObject().apply {
            put("type", "DISCOVER")
            put("device_id", currentDeviceId)
            put("device_name", currentDeviceName)
        }.toString().toByteArray(Charsets.UTF_8)

        val broadcastAddrs = getBroadcastAddresses()
        for (round in 0..1) {
            for (addr in broadcastAddrs) {
                try {
                    val packet = DatagramPacket(reqMsg, reqMsg.size, addr, udpPort)
                    socket?.send(packet)
                } catch (_: Exception) {}
            }
            if (round == 0) {
                try {
                    Thread.sleep(100)
                } catch (_: InterruptedException) {}
            }
        }
    }

    /**
     * 不受子网扫描锁限制，立即对指定历史 IP 执行毫秒级单点 HTTP 探活
     */
    fun probeSingleIpNow(targetIp: String) {
        if (targetIp.isBlank() || targetIp.startsWith("127.")) return
        currentHintIp = targetIp
        Thread {
            probeIp(targetIp)
        }.apply {
            isDaemon = true
            name = "CrossClip-HintProbe"
            start()
        }
    }

    /**
     * 外部事件驱动触发立即探活 (如 Wi-Fi 恢复、屏幕点亮、手动刷新时调用)
     */
    fun requestImmediateScan(hintIp: String = "") {
        if (hintIp.isNotEmpty()) {
            currentHintIp = hintIp
        }
        if (!isSearching) return

        // 1. 推进扫描世代，使旧一轮卡住的离线探测任务立即让路，并释放扫描锁以允许新子网扫描
        scanGeneration.incrementAndGet()
        isSubnetScanning.set(false)

        // 2. 唤醒主循环休眠
        synchronized(sleepMonitor) {
            sleepMonitor.notifyAll()
        }

        // 3. 立即并发执行：单点探测历史 IP + UDP 广播 + 全子网扫描
        Thread {
            if (currentHintIp.isNotEmpty()) {
                probeIp(currentHintIp)
            }
            sendUdpBroadcastBurst()
            if (!isConnected) {
                fastSubnetScan()
            }
        }.apply {
            isDaemon = true
            name = "CrossClip-ImmediateScan"
            start()
        }
    }

    private fun isSameSubnetAny(ip: String, localIps: List<String>): Boolean {
        val ipParts = ip.split(".")
        if (ipParts.size != 4) return false
        val prefix = "${ipParts[0]}.${ipParts[1]}.${ipParts[2]}."
        return localIps.any { it.startsWith(prefix) }
    }

    /**
     * 快速并发扫描当前子网所有 IP 的 18236 端口 (带世代保护)
     */
    private fun fastSubnetScan() {
        val executor = scanExecutor ?: return
        if (!isSubnetScanning.compareAndSet(false, true)) {
            return
        }

        val gen = scanGeneration.incrementAndGet()
        val localIps = getLocalIpv4List()
        val prefixesToScan = mutableSetOf<String>()

        for (localIp in localIps) {
            val parts = localIp.split(".")
            if (parts.size == 4) {
                prefixesToScan.add("${parts[0]}.${parts[1]}.${parts[2]}")
            }
        }

        if (currentHintIp.isNotEmpty()) {
            val hintParts = currentHintIp.split(".")
            if (hintParts.size == 4) {
                prefixesToScan.add("${hintParts[0]}.${hintParts[1]}.${hintParts[2]}")
                try {
                    executor.submit {
                        if (gen == scanGeneration.get() && isSearching && !isConnected) {
                            probeIp(currentHintIp)
                        }
                    }
                } catch (_: java.util.concurrent.RejectedExecutionException) {}
            }
        }

        Thread {
            try {
                val tasks = mutableListOf<java.util.concurrent.Future<*>>()
                for (subnetPrefix in prefixesToScan) {
                    for (i in 1..254) {
                        val targetIp = "$subnetPrefix.$i"
                        if (localIps.contains(targetIp)) continue
                        try {
                            val future = executor.submit {
                                if (gen != scanGeneration.get() || !isSearching || isConnected) return@submit
                                probeIp(targetIp)
                            }
                            tasks.add(future)
                        } catch (_: java.util.concurrent.RejectedExecutionException) {
                            break
                        }
                    }
                }
                for (task in tasks) {
                    if (gen != scanGeneration.get() || isConnected) break
                    try {
                        task.get(800, TimeUnit.MILLISECONDS)
                    } catch (_: Exception) {}
                }
            } finally {
                if (gen == scanGeneration.get()) {
                    isSubnetScanning.set(false)
                }
            }
        }.apply {
            isDaemon = true
            name = "CrossClip-SubnetSupervisor"
            start()
        }
    }

    private fun probeIp(targetIp: String) {
        try {
            val req = Request.Builder()
                .url("http://$targetIp:18236/ping")
                .get()
                .build()
            val resp = httpClient.newCall(req).execute()
            if (resp.isSuccessful) {
                val bodyStr = resp.body?.string() ?: ""
                val json = JSONObject(bodyStr)
                val devName = json.optString("device_name", "Windows 电脑")
                val devId = json.optString("device_id", "").ifEmpty { "pc_${targetIp.replace('.', '_')}" }
                if (!devId.startsWith("android_") && devId != currentDeviceId) {
                    Log.i(TAG, "通过主动探测发现电脑: $devName ($devId) -> $targetIp:18236")
                    onDeviceFound(devId, devName, targetIp, 18236)
                }
            }
            resp.close()
        } catch (_: Exception) {}
    }

    fun stopDiscovery() {
        isSearching = false
        synchronized(sleepMonitor) {
            sleepMonitor.notifyAll()
        }
        try {
            scanExecutor?.shutdownNow()
        } catch (_: Exception) {}
        scanExecutor = null
        try {
            socket?.close()
        } catch (_: Exception) {}
        socket = null
        releaseMulticastLock()
    }
}
