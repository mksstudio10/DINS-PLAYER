package com.dinsplayer.app.core.network

import android.util.Log
import okhttp3.Dns
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import org.json.JSONObject
import java.io.IOException
import java.net.InetAddress
import java.net.UnknownHostException
import java.util.Collections
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

/**
 * DNS resolver cerdas yang memprioritaskan DNS bawaan sistem,
 * dan secara otomatis beralih ke DNS Cloudflare 1.1.1.1 (DNS-over-HTTPS / DoH)
 * jika DNS sistem gagal (UnknownHostException / timeout) atau mengembalikan IP sinkhole/blokir.
 */
object FallbackDns : Dns {
    private const val TAG = "FallbackDns"

    // Cache memori agar tidak perlu me-resolve berulang kali
    private val ipCache = ConcurrentHashMap<String, List<InetAddress>>()

    // Daftar hostname yang telah dialihkan ke Cloudflare DoH
    private val cloudflareHosts = Collections.synchronizedSet(HashSet<String>())

    // Daftar IP sinkhole / Internet Positif / Nawala yang biasa disuntikkan ISP lokal
    private val SINKHOLE_IPS = setOf(
        "103.80.80.235",
        "103.80.80.236",
        "118.98.96.67",
        "118.98.96.68",
        "36.86.63.181",
        "36.86.63.182",
        "127.0.0.1",
        "0.0.0.0",
        "::1"
    )

    // Client terisolasi untuk memanggil DoH tanpa rekursi DNS dan bebas dari blokir DNS sistem
    private val dohClient: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .dns(object : Dns {
                override fun lookup(hostname: String): List<InetAddress> {
                    return when (hostname) {
                        "1.1.1.1" -> listOf(InetAddress.getByAddress("1.1.1.1", byteArrayOf(1, 1, 1, 1)))
                        "1.0.0.1" -> listOf(InetAddress.getByAddress("1.0.0.1", byteArrayOf(1, 0, 0, 1)))
                        "8.8.8.8" -> listOf(InetAddress.getByAddress("8.8.8.8", byteArrayOf(8, 8, 8, 8.toByte())))
                        "8.8.4.4" -> listOf(InetAddress.getByAddress("8.8.4.4", byteArrayOf(8, 8, 4, 4)))
                        "cloudflare-dns.com" -> listOf(InetAddress.getByAddress("cloudflare-dns.com", byteArrayOf(1, 1, 1, 1)))
                        "dns.google" -> listOf(InetAddress.getByAddress("dns.google", byteArrayOf(8, 8, 8, 8.toByte())))
                        else -> Dns.SYSTEM.lookup(hostname)
                    }
                }
            })
            .connectTimeout(6, TimeUnit.SECONDS)
            .readTimeout(6, TimeUnit.SECONDS)
            .followRedirects(true)
            .build()
    }

    fun isUsingCloudflare(host: String): Boolean = cloudflareHosts.contains(host)

    fun markHostFailed(host: String) {
        cloudflareHosts.add(host)
        ipCache.remove(host)
    }

    /**
     * Konfigurasi praktis untuk memasang FallbackDns dan Interceptor ke OkHttpClient.Builder
     */
    fun configure(builder: OkHttpClient.Builder): OkHttpClient.Builder {
        return builder
            .dns(this)
            .addInterceptor(DnsFallbackInterceptor())
    }

    private val IPV4_REGEX = Regex("""^\d{1,3}\.\d{1,3}\.\d{1,3}\.\d{1,3}$""")

    override fun lookup(hostname: String): List<InetAddress> {
        // Jangan lakukan DoH untuk IP address literal atau localhost
        if (hostname.equals("localhost", ignoreCase = true) || IPV4_REGEX.matches(hostname) || hostname.contains(":")) {
            return Dns.SYSTEM.lookup(hostname)
        }

        // Cek cache
        ipCache[hostname]?.let { return it }

        // 1. Jika host ini sudah ditandai gagal dengan DNS sistem, gunakan Cloudflare DoH langsung
        if (cloudflareHosts.contains(hostname)) {
            val dohResult = resolveWithDoh(hostname)
            if (dohResult.isNotEmpty()) {
                ipCache[hostname] = dohResult
                return dohResult
            }
        }

        // 2. Coba DNS bawaan sistem terlebih dahulu
        try {
            val systemIps = Dns.SYSTEM.lookup(hostname)

            // Periksa apakah IP yang dikembalikan adalah IP sinkhole / pemblokiran ISP
            val isPoisoned = systemIps.any { ip ->
                val addr = ip.hostAddress
                addr != null && addr in SINKHOLE_IPS
            }
            if (isPoisoned) {
                Log.w(TAG, "DNS Sistem mengembalikan IP blokir/sinkhole untuk $hostname. Beralih ke Cloudflare 1.1.1.1 DoH...")
                cloudflareHosts.add(hostname)
                val dohResult = resolveWithDoh(hostname)
                if (dohResult.isNotEmpty()) {
                    ipCache[hostname] = dohResult
                    return dohResult
                }
            }

            ipCache[hostname] = systemIps
            return systemIps
        } catch (e: Exception) {
            Log.w(TAG, "DNS Sistem gagal untuk $hostname (${e.message}). Beralih ke Cloudflare 1.1.1.1 DoH...")
            cloudflareHosts.add(hostname)
            val dohResult = resolveWithDoh(hostname)
            if (dohResult.isNotEmpty()) {
                ipCache[hostname] = dohResult
                return dohResult
            }
            throw UnknownHostException("Gagal me-resolve $hostname: DNS sistem dan Cloudflare DoH keduanya gagal")
        }
    }

    /**
     * Query DNS-over-HTTPS ke Cloudflare 1.1.1.1 (dengan fallback ke 1.0.0.1 dan Google 8.8.8.8)
     */
    private fun resolveWithDoh(hostname: String): List<InetAddress> {
        val endpoints = listOf(
            "https://1.1.1.1/dns-query?name=$hostname&type=A",
            "https://1.0.0.1/dns-query?name=$hostname&type=A",
            "https://8.8.8.8/resolve?name=$hostname&type=A"
        )

        for (endpoint in endpoints) {
            try {
                val req = Request.Builder()
                    .url(endpoint)
                    .header("Accept", "application/dns-json")
                    .build()

                dohClient.newCall(req).execute().use { response ->
                    if (response.isSuccessful) {
                        val body = response.body?.string() ?: return@use
                        val json = JSONObject(body)
                        val answers = json.optJSONArray("Answer") ?: return@use
                        val ips = mutableListOf<InetAddress>()
                        for (i in 0 until answers.length()) {
                            val item = answers.getJSONObject(i)
                            val type = item.optInt("type")
                            val data = item.optString("data").trimEnd('.')
                            // Type 1 = A (IPv4), Type 28 = AAAA (IPv6)
                            if (type == 1 || type == 28) {
                                try {
                                    val parsed = InetAddress.getByName(data)
                                    val addr = parsed.hostAddress
                                    if (addr != null && addr !in SINKHOLE_IPS) {
                                        ips.add(InetAddress.getByAddress(hostname, parsed.address))
                                    }
                                } catch (_: Exception) {}
                            }
                        }
                        if (ips.isNotEmpty()) {
                            Log.i(TAG, "Sukses resolve $hostname via DoH ($endpoint) -> ${ips.map { it.hostAddress }}")
                            return ips
                        }
                    }
                }
            } catch (e: Exception) {
                Log.w(TAG, "DoH gagal pada $endpoint untuk $hostname: ${e.message}")
            }
        }
        return emptyList()
    }
}

/**
 * Interceptor untuk menangkap kegagalan koneksi/SSL akibat pembelokan DNS,
 * lalu otomatis me-retry request dengan Cloudflare 1.1.1.1 DoH.
 */
class DnsFallbackInterceptor : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        val host = request.url.host

        return try {
            chain.proceed(request)
        } catch (e: IOException) {
            if (!FallbackDns.isUsingCloudflare(host)) {
                Log.w("DnsFallbackInterceptor", "Koneksi ke $host gagal (${e.javaClass.simpleName}: ${e.message}). Me-retry dengan Cloudflare 1.1.1.1...")
                FallbackDns.markHostFailed(host)
                // Retry request dengan rute baru (yang kini akan menggunakan Cloudflare DoH)
                chain.proceed(request)
            } else {
                throw e
            }
        }
    }
}
