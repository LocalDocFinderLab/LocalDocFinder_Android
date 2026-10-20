package com.example.privacy

import okhttp3.Interceptor
import okhttp3.Response
import okhttp3.ResponseBody
import okio.Buffer
import okio.BufferedSource
import okio.ForwardingSource
import okio.buffer
import java.io.IOException
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap

/**
 * The only door between the app and the internet. Every HTTP client in the app carries [interceptor], which
 * enforces the privacy promise in code instead of by convention:
 *
 *  - HTTPS only,
 *  - GET/HEAD only and never with a request body, so there is no way to upload a document, a search or an
 *    index through this app,
 *  - only the hosts that model downloads and app updates need.
 *
 * Anything else is refused before a byte leaves the phone and is written to [NetworkAudit] as blocked.
 */
object NetworkGuard {

    /** Hosts (and their subdomains) the app may talk to: Hugging Face for models, GitHub for app updates. */
    private val allowedHostSuffixes = listOf(
        "huggingface.co",
        "hf.co",
        "github.com",
        "githubusercontent.com"
    )

    /** Hosts the user typed in themselves (a custom update manifest URL); allowed for this process only. */
    private val userAllowedHosts: MutableSet<String> = ConcurrentHashMap.newKeySet()

    val allowedHostsDescription: String
        get() = allowedHostSuffixes.joinToString(", ")

    fun allowUserHost(host: String) {
        if (host.isNotBlank()) userAllowedHosts.add(host.lowercase(Locale.ROOT))
    }

    fun isHostAllowed(host: String): Boolean {
        val h = host.lowercase(Locale.ROOT)
        return h in userAllowedHosts || allowedHostSuffixes.any { h == it || h.endsWith(".$it") }
    }

    /** Why a request must not be sent, or null when it may go ahead. Pure, so it is unit-tested. */
    fun blockReason(method: String, isHttps: Boolean, host: String, hasBody: Boolean): String? = when {
        !isHttps -> "Blocked: plain HTTP is never used"
        hasBody || (method != "GET" && method != "HEAD") -> "Blocked: the app only downloads, it never uploads"
        !isHostAllowed(host) -> "Blocked: $host is not an allowed host"
        else -> null
    }

    fun interceptor(purpose: NetworkAudit.Purpose): Interceptor = Interceptor { chain ->
        val request = chain.request()
        val url = request.url
        val sent = headerBytes(request.headers.byteCount(), request.method, url.encodedPath)
        val reason = blockReason(request.method, url.isHttps, url.host, request.body != null)
        if (reason != null) {
            NetworkAudit.record(purpose, request.method, url.host, url.encodedPath, sent, blockedReason = reason)
            throw IOException(reason)
        }
        val entry = NetworkAudit.record(purpose, request.method, url.host, url.encodedPath, sent)
        val response = try {
            chain.proceed(request)
        } catch (e: IOException) {
            NetworkAudit.failed(entry, e)
            throw e
        }
        val body = response.body
        NetworkAudit.responded(entry, response.code)
        if (body == null) response else response.newBuilder().body(CountingBody(body, entry)).build()
    }

    private fun headerBytes(headers: Long, method: String, path: String): Long = headers + method.length + path.length + 12

    /** Counts the bytes that really arrive, including bodies of unknown length (streamed model downloads). */
    private class CountingBody(private val delegate: ResponseBody, private val entry: NetworkAudit.Entry) : ResponseBody() {
        private val counted: BufferedSource by lazy {
            object : ForwardingSource(delegate.source()) {
                override fun read(sink: Buffer, byteCount: Long): Long {
                    val n = super.read(sink, byteCount)
                    if (n > 0) NetworkAudit.received(entry, n)
                    return n
                }
            }.buffer()
        }

        override fun contentType() = delegate.contentType()
        override fun contentLength() = delegate.contentLength()
        override fun source(): BufferedSource = counted
    }
}
