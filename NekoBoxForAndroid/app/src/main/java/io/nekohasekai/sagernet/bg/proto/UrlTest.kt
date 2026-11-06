package io.nekohasekai.sagernet.bg.proto

import com.google.gson.Gson
import io.nekohasekai.sagernet.ui.MainActivity
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.coroutines.suspendCoroutine
import io.nekohasekai.sagernet.database.DataStore
import io.nekohasekai.sagernet.database.ProxyEntity

class UrlTest {

    val link = DataStore.connectionTestURL
    private val timeout = 3000

    suspend fun doTest(profile: ProxyEntity): Int {
        return TestInstance(profile, link, timeout).doTest()
    }

    /**
     * 批量测速（流式）：**交给 bg 进程执行**。
     *
     * 必须放在 bg 进程：只有那里 AutoDetectInterfaceControl 会直接调
     * VpnService.protect；UI 进程走的是 protect_path unix socket，跨进程连不上
     * （错误还被忽略），于是连接代理时测速实例的 socket 全被自己的 tun 捕获，
     * 表现出来就是「一点测速，所有节点瞬间超时」。
     */
    suspend fun doTestAll(
        profiles: List<ProxyEntity>,
        concurrency: Int,
        activity: MainActivity,
        onResult: (ProxyEntity, Int, String?) -> Unit,
    ) {
        if (profiles.isEmpty()) return
        val byId = profiles.associateBy { it.id }
        val idsJson = Gson().toJson(profiles.map { it.id })
        suspendCoroutine<Unit> { cont ->
            activity.urlTestReceiver = { id, latency, error ->
                byId[id]?.let { onResult(it, latency, error.ifEmpty { null }) }
            }
            activity.urlTestFinishReceiver = {
                activity.urlTestReceiver = null
                activity.urlTestFinishReceiver = null
                cont.resume(Unit)
            }
            try {
                activity.urlTestAll(idsJson, link, timeout, concurrency)
            } catch (e: Exception) {
                activity.urlTestReceiver = null
                activity.urlTestFinishReceiver = null
                cont.resumeWithException(e)
            }
        }
    }

}