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
        onResult: suspend (ProxyEntity, Int) -> Unit,
    ) {
        if (profiles.isEmpty()) return

        // 已经连接代理时不能新建测速实例：新实例发出的 socket 会被运行中的 tun 捕获，
        // 测速必然超时（用户看到的就是「连接代理后没法测速」）。
        // 直接复用主实例的 outbound 测，测的就是当前代理本身。
        if (DataStore.serviceState.connected) {
            val semaphore = Semaphore(concurrency.coerceAtLeast(1))
            coroutineScope {
                profiles.map { profile ->
                    async(Dispatchers.IO) {
                        semaphore.withPermit {
                            val latency = try {
                                // 主实例里节点的 outbound tag 是 c-<chainId>-<profileId>
                                Libcore.urlTestNodeMain(profile.id, link, timeout)
                            } catch (e: Exception) {
                                Logs.w(e)
                                -1
                            }
                            onResult(profile, latency)
                        }
                    }
                }.awaitAll()
            }
            return
        }

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
                            val latency = if (tag.isNullOrBlank()) {
                                -1
                            } else {
                                try {
                                    Libcore.urlTestTag(it.box, tag, link, timeout)
                                } catch (e: Exception) {
                                    Logs.w(e)
                                    -1
                                }
                            }
                            onResult(profile, latency)
                        }
                    }
                }.awaitAll()
            }
        }
    }

}