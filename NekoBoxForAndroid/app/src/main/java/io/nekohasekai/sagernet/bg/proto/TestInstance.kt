package io.nekohasekai.sagernet.bg.proto

import com.google.gson.Gson
import io.nekohasekai.sagernet.BuildConfig
import io.nekohasekai.sagernet.bg.GuardedProcessPool
import io.nekohasekai.sagernet.database.ProxyEntity
import io.nekohasekai.sagernet.fmt.buildConfig
import io.nekohasekai.sagernet.ktx.Logs
import io.nekohasekai.sagernet.ktx.runOnDefaultDispatcher
import io.nekohasekai.sagernet.ktx.tryResume
import io.nekohasekai.sagernet.ktx.tryResumeWithException
import kotlinx.coroutines.delay
import libcore.Libcore
import moe.matsuri.nb4a.net.LocalResolverImpl
import kotlin.coroutines.suspendCoroutine

class TestInstance(
    profile: ProxyEntity,
    val link: String,
    private val timeout: Int,
    // 非空时表示「批量测速」：一次启动内核，测全部节点
    private val testAll: List<ProxyEntity>? = null,
) : BoxInstance(profile) {

    suspend fun doTest(): Int {
        return suspendCoroutine { c ->
            processes = GuardedProcessPool {
                Logs.w(it)
                c.tryResumeWithException(it)
            }
            runOnDefaultDispatcher {
                use {
                    try {
                        init()
                        launch()
                        if (processes.processCount > 0) {
                            // wait for plugin start
                            delay(500)
                        }
                        c.tryResume(Libcore.urlTest(box, link, timeout))
                    } catch (e: Exception) {
                        c.tryResumeWithException(e)
                    }
                }
            }
        }
    }

    override fun buildConfig() {
        config = buildConfig(profile, true, testAll = testAll)
    }

    /**
     * 批量测速：所有节点共用一个内核实例，按 outbound tag 并发拨号。
     * 返回 entityId -> 延迟（-1 表示失败）。
     */
    suspend fun doTestAll(concurrency: Int): Map<Long, Int> = suspendCoroutine { c ->
        processes = GuardedProcessPool {
            Logs.w(it)
            c.tryResumeWithException(it)
        }
        runOnDefaultDispatcher {
            use {
                try {
                    init()
                    launch()
                    if (processes.processCount > 0) {
                        // wait for plugin start
                        delay(500)
                    }
                    val ids = testAll!!.map { it.id }.filter { config.profileTagMap.containsKey(it) }
                    val tags = ids.map { config.profileTagMap.getValue(it) }
                    val tagsJson = Gson().toJson(tags)
                    val resultJson = Libcore.urlTestBatch(box, tagsJson, link, timeout, concurrency)
                    val results = Gson().fromJson(resultJson, Array<BatchUrlTestResult>::class.java)
                    val map = LinkedHashMap<Long, Int>()
                    ids.forEachIndexed { index, id ->
                        val r = results?.getOrNull(index)
                        map[id] = if (r == null || !r.error.isNullOrEmpty()) -1 else r.latency
                    }
                    c.tryResume(map)
                } catch (e: Exception) {
                    c.tryResumeWithException(e)
                }
            }
        }
    }

    data class BatchUrlTestResult(val tag: String = "", val latency: Int = 0, val error: String? = null)

    override suspend fun loadConfig() {
        // don't call destroyAllJsi here
        if (BuildConfig.DEBUG) Logs.d(config.config)
        box = Libcore.newSingBoxInstance(config.config, LocalResolverImpl)
    }

}
