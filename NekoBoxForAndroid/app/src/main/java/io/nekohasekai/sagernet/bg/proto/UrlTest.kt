package io.nekohasekai.sagernet.bg.proto

import io.nekohasekai.sagernet.database.DataStore
import io.nekohasekai.sagernet.database.ProxyEntity
import io.nekohasekai.sagernet.ktx.Logs

class UrlTest {

    companion object {
        /** 单个节点的拨号超时（ms），UI 侧再乘 2 加 2.5s 作为兜底 */
        const val TEST_TIMEOUT_MS = 5000
    }

    val link: String = DataStore.connectionTestURL

    suspend fun doTest(profile: ProxyEntity): Int {
        Logs.d("URLTest ${profile.displayName()}: start, link=$link, timeout=${TEST_TIMEOUT_MS}ms")
        return TestInstance(profile, link, TEST_TIMEOUT_MS).doTest()
    }

}
