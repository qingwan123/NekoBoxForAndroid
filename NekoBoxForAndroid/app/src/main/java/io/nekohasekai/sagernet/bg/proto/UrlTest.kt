package io.nekohasekai.sagernet.bg.proto

import io.nekohasekai.sagernet.database.DataStore
import io.nekohasekai.sagernet.database.ProxyEntity

class UrlTest {

    val link = DataStore.connectionTestURL
    private val timeout = 3000

    suspend fun doTest(profile: ProxyEntity): Int {
        return TestInstance(profile, link, timeout).doTest()
    }

    /**
     * 批量测速：所有节点共用一次内核启动，按 outbound tag 并发测。
     * 返回 entityId -> 延迟（-1 失败）。节点很多时比逐个启动内核快一个数量级。
     */
    suspend fun doTestAll(profiles: List<ProxyEntity>, concurrency: Int): Map<Long, Int> {
        if (profiles.isEmpty()) return emptyMap()
        return TestInstance(profiles.first(), link, timeout, testAll = profiles)
            .doTestAll(concurrency)
    }

}