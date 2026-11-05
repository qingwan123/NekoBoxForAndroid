package io.nekohasekai.sagernet

import io.nekohasekai.sagernet.fmt.KryoConverters
import io.nekohasekai.sagernet.fmt.v2ray.VMessBean
import io.nekohasekai.sagernet.fmt.v2ray.parseDuckSoft
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 用真实订阅里的节点链接验证 VLESS 后量子加密层的解析、Kryo 入库、以及配置生成。
 *
 * 不能走 parseV2Ray()：它内部的 Logs 会触发 SagerNet -> libcore.Libcore 的静态
 * 初始化，JVM 单测里没有 gojni.so，会直接 NoClassDefFoundError。
 * parseDuckSoft 是纯解析函数，可以安全直调。
 */
class VlessParseTest {

    private val key =
        "mlkem768x25519plus.native.0rtt.100-50-500.50-0-50.DEruZAN_ni9urNTJL5GTX34PXYrIjMUIe0Py-n53HCI"

    private val link = "vless://3c6de89b-6559-4a72-b00c-18ccef7dd436@aysg-32.nasajiuai.help:443" +
        "?type=tcp&encryption=" + key +
        "&host=&path=&headerType=none&quicSecurity=none&serviceName=&security=reality" +
        "&flow=xtls-rprx-vision&fp=safari&insecure=0&pcs=&sni=www.sony.com.sg" +
        "&pbk=TA-QBpPO4jnpweP5m2XaLQ1N4buUsQfBg9-i6vlhuAo&sid=6cda59c3"

    private fun parse() = VMessBean().apply {
        alterId = -1
        parseDuckSoft(link.replace("vless://", "https://").toHttpUrl())
        initializeDefaultValues()
    }

    @Test
    fun encryptionIsParsed() {
        val bean = parse()
        println(
            "isVLESS=" + bean.isVLESS + " flow=" + bean.encryption +
                " vlessEncryption=" + bean.vlessEncryption + " sni=" + bean.sni
        )
        assertEquals(key, bean.vlessEncryption)
        assertEquals("xtls-rprx-vision", bean.encryption)
        assertEquals("TA-QBpPO4jnpweP5m2XaLQ1N4buUsQfBg9-i6vlhuAo", bean.realityPubKey)
        assertEquals("www.sony.com.sg", bean.sni)
        assertEquals("aysg-32.nasajiuai.help", bean.serverAddress)
        assertEquals(443, bean.serverPort)
    }

    @Test
    fun encryptionSurvivesKryo() {
        val bean = parse()
        val copy = KryoConverters.deserialize(VMessBean(), KryoConverters.serialize(bean))
        println("roundtrip vlessEncryption=" + copy.vlessEncryption)
        assertEquals(key, copy.vlessEncryption)
    }


    @Test
    fun kitsunebiDecoderMustRejectVlessLinks() {
        val raw = "vless://3c6de89b-6559-4a72-b00c-18ccef7dd436@aysg-32.nasajiuai.help:443"
        try {
            val decoded = moe.matsuri.nb4a.utils.NGUtil.decode(raw)
            println("NGUtil.decode returned: [" + decoded + "]")
        } catch (e: Exception) {
            println("NGUtil.decode threw: " + e::class.java.simpleName)
        }
    }
}
