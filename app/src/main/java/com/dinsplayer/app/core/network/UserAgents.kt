package com.dinsplayer.app.core.network

import java.util.Base64

/**
 * User-Agent chain used when the app itself fetches a playlist or panel API.
 *
 * Order: [GLOBAL_1], then [GLOBAL_2], then the premium agent. The premium agent is a last
 * resort for servers that whitelist it. It must never reach the UI, a preset list, a log line
 * or a stored channel/playlist field, so it is only exposed through [fetchChain].
 */
object UserAgents {

    const val GLOBAL_1 =
        "Mozilla/5.0 (Linux; Android 14; Mobile) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/130.0.6723.60 Mobile Safari/537.36"

    const val GLOBAL_2 =
        "DinsPlayer/1.0 (Android; Linux; U; ExoPlayer) Chrome/129.0.0.0 Safari/537.36"

    // XOR + Base64 only keeps the string out of plain-text searches of the APK. It is
    // obfuscation, not protection: anyone who decompiles the app can still recover it.
    private const val PREMIUM_ENCODED =
        "HlX/lHjYDGo/TsGVTdkEZjcTo8kYlEVSNFjjiEHQVjMKTvSKQcEAKHp56Yh42AxqP064x2vcH3w3Wb7WGo1DI3QMv9cI5wx1O074yB2HWj1pCg=="

    private val PREMIUM_KEY = byteArrayOf(0x5A, 0x3C, 0x91.toByte(), 0xE7.toByte(), 0x28, 0xB4.toByte(), 0x6D, 0x13)

    private val premium: String by lazy {
        val data = Base64.getDecoder().decode(PREMIUM_ENCODED)
        String(ByteArray(data.size) { i -> (data[i].toInt() xor PREMIUM_KEY[i % PREMIUM_KEY.size].toInt()).toByte() })
    }

    /** True for the premium agent, so callers can keep it out of logs and saved fields. */
    fun isPremium(ua: String?): Boolean = ua != null && ua == premium

    /**
     * Agents to try in order. [preferred] (a user-chosen agent) goes first when set.
     * [legacy] agents the caller already had are kept after the premium agent so existing
     * behaviour is not lost.
     */
    fun fetchChain(preferred: String? = null, legacy: List<String> = emptyList()): List<String> {
        val chain = LinkedHashSet<String>()
        preferred?.takeIf { it.isNotBlank() }?.let { chain.add(it) }
        chain.add(GLOBAL_1)
        chain.add(GLOBAL_2)
        chain.add(premium)
        chain.addAll(legacy)
        return chain.toList()
    }
}

