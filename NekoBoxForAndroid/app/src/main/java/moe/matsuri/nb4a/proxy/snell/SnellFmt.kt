package moe.matsuri.nb4a.proxy.snell

import io.nekohasekai.sagernet.ktx.blankAsNull
import io.nekohasekai.sagernet.ktx.linkBuilder
import io.nekohasekai.sagernet.ktx.toLink
import io.nekohasekai.sagernet.ktx.urlSafe
import moe.matsuri.nb4a.SingBoxOptions.CustomSingBoxOption
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

/**
 * sing-box 1.14 的 snell outbound 是 version 4 / version 6 的联合结构，
 * GUI 里没有对应的生成类，这里直接拼 JSON 交给 CustomSingBoxOption。
 */
fun buildSingBoxOutboundSnellBean(bean: SnellBean): CustomSingBoxOption {
    val map = linkedMapOf<String, Any?>().apply {
        put("type", "snell")
        put("server", bean.serverAddress)
        put("server_port", bean.serverPort)
        put("version", bean.version ?: 4)
        put("psk", bean.psk ?: "")
        if ((bean.version ?: 4) == 4) {
            bean.obfsMode.blankAsNull()?.let { put("obfs_mode", it) }
            bean.obfsHost.blankAsNull()?.let { put("obfs_host", it) }
        } else {
            bean.mode.blankAsNull()?.let { put("mode", it) }
            bean.userKey.blankAsNull()?.let { put("userkey", it) }
            if (bean.reuse == true) put("reuse", true)
        }
    }
    return CustomSingBoxOption(moe.matsuri.nb4a.utils.JavaUtil.gson.toJson(map))
}

fun SnellBean.toUri(): String {
    val builder = linkBuilder()
        .host(serverAddress)
        .port(serverPort)
        .username(psk)
    if (!name.isNullOrBlank()) {
        builder.encodedFragment(name.urlSafe())
    }
    builder.addQueryParameter("version", (version ?: 4).toString())
    if ((version ?: 4) == 4) {
        obfsMode.blankAsNull()?.let { builder.addQueryParameter("obfs", it) }
        obfsHost.blankAsNull()?.let { builder.addQueryParameter("obfs-host", it) }
    } else {
        mode.blankAsNull()?.let { builder.addQueryParameter("mode", it) }
        userKey.blankAsNull()?.let { builder.addQueryParameter("userkey", it) }
    }
    return builder.toLink("snell")
}

fun parseSnell(url: String): SnellBean {
    val link = url.replace("snell://", "https://").toHttpUrlOrNull()
        ?: error("invalid snell link $url")
    return SnellBean().apply {
        serverAddress = link.host
        serverPort = link.port
        name = link.fragment
        psk = link.username
        version = link.queryParameter("version")?.toIntOrNull() ?: 4
        if (version == 4) {
            obfsMode = link.queryParameter("obfs") ?: "none"
            obfsHost = link.queryParameter("obfs-host") ?: ""
        } else {
            mode = link.queryParameter("mode") ?: "default"
            userKey = link.queryParameter("userkey") ?: ""
        }
    }
}
