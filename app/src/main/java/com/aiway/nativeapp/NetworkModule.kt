package com.aiway.nativeapp

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import okhttp3.Dns
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.Response
import okhttp3.dnsoverhttps.DnsOverHttps
import java.io.IOException
import java.net.ConnectException
import java.net.InetAddress
import java.net.NoRouteToHostException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.util.concurrent.TimeUnit
import javax.net.ssl.SSLException

/**
 * Network layer with several independent fallbacks, tried in order:
 *  1) system DNS
 *  2) DNS-over-HTTPS (Cloudflare, then Google, then Quad9) bootstrapped by fixed IPs
 *  3) last known good IPs for the host (cached on disk)
 *  4) wait-for-network + retry with backoff when the request never left the device
 */
object NetworkModule {

    fun buildClient(context: Context): OkHttpClient {
        val app = context.applicationContext
        val bootstrap = OkHttpClient.Builder()
            .connectTimeout(8, TimeUnit.SECONDS)
            .readTimeout(8, TimeUnit.SECONDS)
            .build()

        val doh = listOf(
            DnsOverHttps.Builder().client(bootstrap)
                .url("https://cloudflare-dns.com/dns-query".toHttpUrl())
                .bootstrapDnsHosts(ip("1.1.1.1"), ip("1.0.0.1"))
                .includeIPv6(false).build(),
            DnsOverHttps.Builder().client(bootstrap)
                .url("https://dns.google/dns-query".toHttpUrl())
                .bootstrapDnsHosts(ip("8.8.8.8"), ip("8.8.4.4"))
                .includeIPv6(false).build(),
            DnsOverHttps.Builder().client(bootstrap)
                .url("https://dns.quad9.net/dns-query".toHttpUrl())
                .bootstrapDnsHosts(ip("9.9.9.9"), ip("149.112.112.112"))
                .includeIPv6(false).build()
        )

        return OkHttpClient.Builder()
            .dns(FallbackDns(app, doh))
            .addInterceptor(ResilienceInterceptor(app))
            .connectTimeout(20, TimeUnit.SECONDS)
            .readTimeout(5, TimeUnit.MINUTES)
            .writeTimeout(60, TimeUnit.SECONDS)
            .retryOnConnectionFailure(true)
            .build()
    }

    private fun ip(v: String): InetAddress = InetAddress.getByName(v)

    private class FallbackDns(context: Context, private val doh: List<DnsOverHttps>) : Dns {
        private val prefs = context.getSharedPreferences("aiway_dns_cache", Context.MODE_PRIVATE)

        override fun lookup(hostname: String): List<InetAddress> {
            var last: Throwable? = null

            runCatching { Dns.SYSTEM.lookup(hostname) }
                .onSuccess { if (it.isNotEmpty()) { remember(hostname, it); return sortV4First(it) } }
                .onFailure { last = it }

            for (resolver in doh) {
                runCatching { resolver.lookup(hostname) }
                    .onSuccess { if (it.isNotEmpty()) { remember(hostname, it); return sortV4First(it) } }
                    .onFailure { last = it }
            }

            val cached = cachedFor(hostname)
            if (cached.isNotEmpty()) return cached

            throw UnknownHostException("Unable to resolve host \"$hostname\" (system DNS + DoH + cache failed)").also {
                last?.let { t -> it.initCause(t) }
            }
        }

        private fun sortV4First(list: List<InetAddress>) = list.sortedBy { if (it.address.size == 4) 0 else 1 }

        private fun remember(host: String, list: List<InetAddress>) {
            val v = list.mapNotNull { it.hostAddress }.take(6).joinToString(",")
            if (v.isNotBlank()) prefs.edit().putString(host, v).apply()
        }

        private fun cachedFor(host: String): List<InetAddress> {
            val raw = prefs.getString(host, null).orEmpty()
            return raw.split(',').filter { it.isNotBlank() }.mapNotNull { runCatching { InetAddress.getByName(it) }.getOrNull() }
        }
    }

    /** Waits for a validated network, then retries only failures where the request never reached the server. */
    private class ResilienceInterceptor(private val context: Context) : Interceptor {
        override fun intercept(chain: Interceptor.Chain): Response {
            var attempt = 0
            var lastError: IOException? = null
            while (attempt < 4) {
                waitForNetwork(if (attempt == 0) 6_000 else 20_000)
                try {
                    return chain.proceed(chain.request())
                } catch (e: IOException) {
                    lastError = e
                    if (!neverSent(e) || chain.call().isCanceled()) throw e
                    attempt++
                    if (attempt < 4) Thread.sleep(800L * (1L shl (attempt - 1)))
                }
            }
            throw lastError ?: IOException("network failure")
        }

        private fun neverSent(e: IOException): Boolean =
            e is UnknownHostException || e is ConnectException || e is NoRouteToHostException ||
                (e is SocketTimeoutException && e.message?.contains("connect", true) == true) ||
                (e is SSLException && e.message?.contains("handshake", true) == true)

        private fun waitForNetwork(maxMs: Long) {
            val deadline = System.currentTimeMillis() + maxMs
            while (!online() && System.currentTimeMillis() < deadline) Thread.sleep(400)
        }

        private fun online(): Boolean {
            val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
            val caps = cm.getNetworkCapabilities(cm.activeNetwork) ?: return false
            return caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
        }
    }

    fun friendlyError(t: Throwable): String {
        var c: Throwable? = t
        while (c != null) {
            when (c) {
                is UnknownHostException -> return "تعذر الوصول لخوادم OpenAI (DNS). جرّب Wi-Fi آخر أو أوقف VPN/الـ Private DNS ثم أعد المحاولة."
                is SocketTimeoutException -> return "انتهت مهلة الاتصال. تأكد من جودة الإنترنت وأعد المحاولة."
                is ConnectException -> return "فشل الاتصال بالخادم. تحقق من الإنترنت أو الجدار الناري."
                is SSLException -> return "مشكلة في اتصال TLS. تأكد من تاريخ ووقت الهاتف وشبكة الإنترنت."
            }
            c = c.cause
        }
        return t.message ?: "حدث خطأ غير متوقع"
    }

    data class Check(val label: String, val ok: Boolean, val detail: String)

    /** Quick connectivity self-test shown in Settings. Must run off the main thread. */
    fun diagnose(client: OkHttpClient): List<Check> {
        val out = mutableListOf<Check>()
        out += runCatching { Dns.SYSTEM.lookup("auth.openai.com").first().hostAddress.orEmpty() }
            .fold({ Check("DNS النظام", true, it) }, { Check("DNS النظام", false, "فشل — سيُستخدم DNS المشفّر تلقائياً") })
        listOf("auth.openai.com" to "https://auth.openai.com/.well-known/jwks.json",
            "api.openai.com" to "https://api.openai.com/v1/models").forEach { (name, url) ->
            out += runCatching {
                client.newCall(okhttp3.Request.Builder().url(url).get().build()).execute().use { "HTTP ${it.code}" }
            }.fold({ Check("اتصال $name", true, it) }, { Check("اتصال $name", false, friendlyError(it)) })
        }
        return out
    }
}
