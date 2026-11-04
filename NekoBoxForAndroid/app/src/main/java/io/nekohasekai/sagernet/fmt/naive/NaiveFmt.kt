package io.nekohasekai.sagernet.fmt.naive

import io.nekohasekai.sagernet.database.DataStore
import io.nekohasekai.sagernet.fmt.LOCALHOST
import io.nekohasekai.sagernet.ktx.*
import moe.matsuri.nb4a.SingBoxOptions.CustomSingBoxOption
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import org.json.JSONObject

fun parseNaive(link: String): NaiveBean {
    val proto = link.substringAfter("+").substringBefore(":")
    val url = ("https://" + link.substringAfter("://")).toHttpUrlOrNull()
        ?: error("Invalid naive link: $link")
    return NaiveBean().also {
        it.proto = proto
    }.apply {
        serverAddress = url.host
        serverPort = url.port
        username = url.username
        password = url.password
        sni = url.queryParameter("sni")
        certificates = url.queryParameter("cert")
        extraHeaders = url.queryParameter("extra-headers")?.unUrlSafe()?.replace("\r\n", "\n")
        insecureConcurrency = url.queryParameter("insecure-concurrency")?.toIntOrNull()
        name = url.fragment
        initializeDefaultValues()
    }
}

fun NaiveBean.toUri(proxyOnly: Boolean = false): String {
    val builder = linkBuilder().host(finalAddress).port(finalPort)
    if (username.isNotBlank()) {
        builder.username(username)
        if (password.isNotBlank()) {
            builder.password(password)
        }
    }
    if (!proxyOnly) {
        if (sni.isNotBlank()) {
            builder.addQueryParameter("sni", sni)
        }
        if (certificates.isNotBlank()) {
            builder.addQueryParameter("cert", certificates)
        }
        if (extraHeaders.isNotBlank()) {
            builder.addQueryParameter("extra-headers", extraHeaders)
        }
        if (name.isNotBlank()) {
            builder.encodedFragment(name.urlSafe())
        }
        if (insecureConcurrency > 0) {
            builder.addQueryParameter("insecure-concurrency", "$insecureConcurrency")
        }
    }
    return builder.toLink(if (proxyOnly) proto else "naive+$proto", false)
}

fun NaiveBean.buildNaiveConfig(port: Int): String {
    return JSONObject().apply {
        // process ipv6
        finalAddress = finalAddress.wrapIPV6Host()
        serverAddress = serverAddress.wrapIPV6Host()

        // process sni
        if (sni.isNotBlank()) {
            put("host-resolver-rules", "MAP $sni $finalAddress")
            finalAddress = sni
        } else {
            if (serverAddress.isIpAddress()) {
                // for naive, using IP as SNI name hardly happens
                // and host-resolver-rules cannot resolve the SNI problem
                // so do nothing
            } else {
                put("host-resolver-rules", "MAP $serverAddress $finalAddress")
                finalAddress = serverAddress
            }
        }

        put("listen", "socks://$LOCALHOST:$port")
        put("proxy", toUri(true))
        if (extraHeaders.isNotBlank()) {
            put("extra-headers", extraHeaders.split("\n").joinToString("\r\n"))
        }
        if (DataStore.logLevel > 0) {
            put("log", "")
        }
        if (insecureConcurrency > 0) {
            put("insecure-concurrency", insecureConcurrency)
        }
    }.toStringPretty()
}

/**
 * 内置 naive outbound（sing-box 1.14 原生支持），取代原来的 naive 外部插件。
 */
fun buildSingBoxOutboundNaiveBean(bean: NaiveBean): CustomSingBoxOption {
    val headers = linkedMapOf<String, String>()
    bean.extraHeaders.blankAsNull()?.lines()?.forEach { line ->
        val idx = line.indexOf(':')
        if (idx > 0) {
            headers[line.substring(0, idx).trim()] = line.substring(idx + 1).trim()
        }
    }
    val tls = linkedMapOf<String, Any?>().apply {
        put("enabled", true)
        bean.sni.blankAsNull()?.let { put("server_name", it) }
        bean.certificates.blankAsNull()?.let { put("certificate", listOf(it)) }
    }
    val map = linkedMapOf<String, Any?>().apply {
        put("type", "naive")
        put("server", bean.serverAddress)
        put("server_port", bean.serverPort)
        bean.username.blankAsNull()?.let { put("username", it) }
        bean.password.blankAsNull()?.let { put("password", it) }
        if (bean.insecureConcurrency != null && bean.insecureConcurrency!! > 0) {
            put("insecure_concurrency", bean.insecureConcurrency)
        }
        if (headers.isNotEmpty()) put("extra_headers", headers)
        if (bean.proto == "quic") put("quic", true)
        put("tls", tls)
    }
    return CustomSingBoxOption(
        moe.matsuri.nb4a.utils.JavaUtil.gson.toJson(map)
    )
}
