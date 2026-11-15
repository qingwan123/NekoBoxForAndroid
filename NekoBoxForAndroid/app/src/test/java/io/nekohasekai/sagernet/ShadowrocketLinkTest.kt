package io.nekohasekai.sagernet

import io.nekohasekai.sagernet.fmt.v2ray.VMessBean
import io.nekohasekai.sagernet.fmt.v2ray.normalizeShadowrocketLink
import io.nekohasekai.sagernet.fmt.v2ray.parseDuckSoft
import io.nekohasekai.sagernet.fmt.v2ray.rebuildShadowrocketLink
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Shadowrocket（小火箭）导出的 VLESS + REALITY + Vision 链接：
 *
 *   vless://<base64(:uuid@8.217.81.238:443)>?remarks=..&tls=1&peer=www.microsoft.com
 *        &allowInsecure=1&udp=1&xtls=2&pbk=..&sid=..&fingerprint=chrome
 *
 * 标准格式里 authority 是明文的，参数名也不同（sni / flow / fp），所以先把
 * authority 解码、参数改成标准名，再走 parseDuckSoft。
 */
class ShadowrocketLinkTest {

    // 从用户链接 base64 解出来的正是这一段（注意前面有个冒号）
    private val decodedAuthority = ":1e025716-6359-43f8-8a3a-a9b395d9374d@8.217.81.238:443"
    private val rawQuery =
        "remarks=%E4%BF%9D%E5%AE%89%E9%83%A8%E2%86%92%E9%83%A8%E9%95%BF%E4%B8%93%E7%BA%BF%2001" +
                "&tls=1&peer=www.microsoft.com&allowInsecure=1&udp=1&xtls=2" +
                "&pbk=_JsgLwu7_dqCxPtT9d9flH26WAMjUSIoLaZ-3Yjh5FE&sid=a11c8625eab6a867" +
                "&fingerprint=chrome"

    private fun parse(rebuilt: String): VMessBean {
        val bean = VMessBean().apply { alterId = -1 }
        bean.parseDuckSoft(rebuilt.replace("vless://", "https://").toHttpUrl())
        bean.initializeDefaultValues()
        return bean
    }

    @Test
    fun shadowrocketRealityVisionLink() {
        val rebuilt = rebuildShadowrocketLink("vless", decodedAuthority, rawQuery)
        println("rebuilt: $rebuilt")
        assertTrue(rebuilt != null)

        val bean = parse(rebuilt!!)
        println(
            "uuid=${bean.uuid} host=${bean.serverAddress} port=${bean.serverPort} " +
                    "tls=${bean.security} sni=${bean.sni} insecure=${bean.allowInsecure} " +
                    "pbk=${bean.realityPubKey} sid=${bean.realityShortId} " +
                    "fp=${bean.utlsFingerprint} flow=${bean.encryption} name=${bean.name}"
        )

        assertEquals("1e025716-6359-43f8-8a3a-a9b395d9374d", bean.uuid)
        assertEquals("8.217.81.238", bean.serverAddress)
        assertEquals(443, bean.serverPort)
        assertEquals("tls", bean.security)
        assertEquals("www.microsoft.com", bean.sni)
        assertEquals(true, bean.allowInsecure)
        assertEquals("_JsgLwu7_dqCxPtT9d9flH26WAMjUSIoLaZ-3Yjh5FE", bean.realityPubKey)
        assertEquals("a11c8625eab6a867", bean.realityShortId)
        assertEquals("chrome", bean.utlsFingerprint)
        // xtls=2 就是 vision
        assertEquals("xtls-rprx-vision", bean.encryption)
        assertEquals("保安部→部长专线 01", bean.name)
    }

    @Test
    fun plainLinkIsNotTouched() {
        // 已经是明文 authority 的链接不能被动（normalize 在解码之前就返回）
        val plain = "vless://1e025716-6359-43f8-8a3a-a9b395d9374d@1.2.3.4:443?tls=1&peer=a.com"
        assertEquals(plain, normalizeShadowrocketLink(plain))
    }
}
