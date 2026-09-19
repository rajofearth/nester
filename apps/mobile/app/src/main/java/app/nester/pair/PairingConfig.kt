package app.nester.pair

import java.net.URLDecoder

data class PairingConfig(
    val host: String,
    val port: Int,
    val token: String,
) {
    val baseUrl: String get() = "http://$host:$port"
}

sealed interface PairParseResult {
    data class Ok(val config: PairingConfig) : PairParseResult
    data class Invalid(val reason: String) : PairParseResult
}

object PairingParser {

    fun parse(raw: String): PairParseResult {
        val uri = raw.trim()
        if (!uri.startsWith("nester://pair?")) {
            return PairParseResult.Invalid("not a nester pair URI")
        }
        val query = uri.removePrefix("nester://pair?")
        val params = mutableMapOf<String, String>()
        for (part in query.split("&")) {
            if (part.isEmpty()) continue
            val idx = part.indexOf('=')
            if (idx <= 0) continue
            val key = URLDecoder.decode(part.substring(0, idx), Charsets.UTF_8)
            val value = URLDecoder.decode(part.substring(idx + 1), Charsets.UTF_8)
            params[key] = value
        }
        val host = params["host"]?.trim().orEmpty()
        val portRaw = params["port"]?.trim().orEmpty()
        val token = params["token"]?.trim().orEmpty()
        if (host.isEmpty()) return PairParseResult.Invalid("missing host")
        val port = portRaw.toIntOrNull()
            ?: return PairParseResult.Invalid("invalid port")
        if (port !in 1..65535) return PairParseResult.Invalid("port out of range")
        if (token.length < 16) return PairParseResult.Invalid("token too short")
        return PairParseResult.Ok(PairingConfig(host = host, port = port, token = token))
    }
}
