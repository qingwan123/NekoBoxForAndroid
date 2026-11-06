package io.nekohasekai.sagernet.bg.proto

import io.nekohasekai.sagernet.database.DataStore
import io.nekohasekai.sagernet.database.ProxyEntity
import io.nekohasekai.sagernet.ktx.Logs
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import libcore.Libcore

class UrlTest {

    val link = DataStore.connectionTestURL
    private val timeout = 3000

    suspend fun doTest(profile: ProxyEntity): Int {
        return TestInstance(profile, link, timeout).doTest()
    }

    /**
     * 批量测速（流式）：所有节点共用**一次**内核启动，按 outbound tag 并发拨号，
     * 每测完一个立即回调 onResult，调用方可以即时刷新那一行。
     *
     * 旧实现是「每个节点各起一个内核」，而 1.15 内核单次启动比 1.14 更重，
     * 于是节点一多就明显变慢。
     */
    suspend fun doTestAll(
        profiles: List<ProxyEntity>,
        concurrency: Int,
        onResult: suspend (ProxyEntity, Int, String?) -> Unit,
    ) {
        if (profiles.isEmpty()) return

        // 必须新建一个「包含全部节点」的实例：主实例的配置只包含当前连接的节点
        // （ConfigBuilder 只有 selector 分组才会把所有节点写进去），拿主实例测其他
        // 节点只会 tag 查不到、瞬间全红。
        val instance = TestInstance(profiles.first(), link, timeout, testAll = profiles)
        instance.use {
            it.init()
            it.launch()
            val semaphore = Semaphore(concurrency.coerceAtLeast(1))
            coroutineScope {
                profiles.map { profile ->
                    async(Dispatchers.IO) {
                        semaphore.withPermit {
                            val tag = it.config.profileTagMap[profile.id]
                            var latency = -1
                            var error: String? = null
                            if (tag.isNullOrBlank()) {
                                error = "no outbound for profile ${profile.id}"
                                Logs.w("url test: $error")
                            } else {
                                try {
                                    latency = Libcore.urlTestTag(it.box, tag, link, timeout)
                                    if (latency < 0) error = "timeout"
                                } catch (e: Exception) {
                                    error = e.message ?: e.toString()
                                    Logs.w("url test $tag: ${e.message ?: e}")
                                }
                            }
                            onResult(profile, latency, error)
                        }
                    }
                }.awaitAll()
            }
        }
    }

}