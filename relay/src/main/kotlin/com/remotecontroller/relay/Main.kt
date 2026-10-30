package com.remotecontroller.relay

import com.remotecontroller.shared.network.RelayProtocol
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.defaultForFilePath
import io.ktor.server.application.Application
import io.ktor.server.application.ApplicationCall
import io.ktor.server.application.ApplicationCallPipeline
import io.ktor.server.application.call
import io.ktor.server.application.install
import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty
import io.ktor.server.response.respondBytes
import io.ktor.server.response.respondRedirect
import io.ktor.server.response.respondText
import io.ktor.server.routing.get
import io.ktor.server.routing.routing
import io.ktor.server.websocket.WebSockets
import io.ktor.server.websocket.pingPeriod
import io.ktor.server.websocket.timeout
import io.ktor.server.websocket.webSocket
import kotlin.time.Duration.Companion.seconds

/**
 * Environment:
 * - `PORT` (8080), `HOST` (0.0.0.0)
 * - `RELAY_ORIGIN`: the public https origin, e.g. `https://relay.example.com`. Browsers from any other
 *   origin are refused. Without it the relay compares Origin with the Host header.
 * - `RELAY_TRUST_PROXY=true`: take the client address from X-Forwarded-For (only behind your own proxy).
 *
 * Run it behind a TLS proxy with a public certificate (Caddy, nginx + Let's Encrypt, a PaaS).
 */
data class RelayConfig(
    val host: String,
    val port: Int,
    val publicOrigin: String?,
    val trustProxy: Boolean,
) {
    companion object {
        fun fromEnv(): RelayConfig = RelayConfig(
            host = System.getenv("HOST") ?: "0.0.0.0",
            port = System.getenv("PORT")?.toIntOrNull() ?: 8080,
            publicOrigin = System.getenv("RELAY_ORIGIN")?.trim()?.trimEnd('/')?.takeIf { it.isNotEmpty() },
            trustProxy = System.getenv("RELAY_TRUST_PROXY") == "true",
        )
    }
}

fun main() {
    val config = RelayConfig.fromEnv()
    embeddedServer(Netty, port = config.port, host = config.host) {
        relay(config)
    }.start(wait = true)
}

fun Application.relay(config: RelayConfig) {
    install(WebSockets) {
        pingPeriod = 20.seconds
        timeout = 60.seconds
        maxFrameSize = 8L * 1024 * 1024
    }
    intercept(ApplicationCallPipeline.Plugins) {
        val headers = call.response.headers
        headers.append("Strict-Transport-Security", "max-age=31536000")
        headers.append("X-Content-Type-Options", "nosniff")
        headers.append("X-Frame-Options", "DENY")
        headers.append("Referrer-Policy", "no-referrer")
        headers.append("Cross-Origin-Opener-Policy", "same-origin")
        headers.append("Content-Security-Policy", "frame-ancestors 'none'; base-uri 'none'; object-src 'none'")
        headers.append("Permissions-Policy", "camera=(), microphone=(), geolocation=()")
    }

    val hub = RelayHub(config)
    val page = RelayPage()

    routing {
        get("/") {
            val html = page.index() ?: return@get call.respondText(
                "The web page is not bundled in this build.",
                status = HttpStatusCode.ServiceUnavailable,
            )
            call.respondText(html, ContentType.Text.Html)
        }
        get("/healthz") { call.respondText("ok") }
        get("/web/{file...}") {
            val path = call.parameters.getAll("file").orEmpty().joinToString("/")
            if (path.isEmpty() || path == "index.html") return@get call.respondRedirect("/")
            val bytes = page.asset(path) ?: return@get call.respondText("", status = HttpStatusCode.NotFound)
            call.respondBytes(bytes, page.contentType(path))
        }
        get("/web") { call.respondRedirect("/") }

        webSocket(RelayProtocol.HOST_PATH) {
            hub.host(this, call.clientAddress(config))
        }
        webSocket("${RelayProtocol.ACCEPT_PATH}/{session}") {
            hub.accept(this, call.parameters["session"].orEmpty())
        }
        webSocket("${RelayProtocol.CONNECT_PATH}/{id}/{channel}") {
            hub.connect(
                peer = this,
                id = call.parameters["id"].orEmpty(),
                channel = call.parameters["channel"].orEmpty(),
                origin = call.request.headers[HttpHeaders.Origin],
                hostHeader = call.request.headers[HttpHeaders.Host],
                address = call.clientAddress(config),
            )
        }
    }
}

private fun ApplicationCall.clientAddress(config: RelayConfig): String {
    if (config.trustProxy) {
        request.headers["X-Forwarded-For"]?.substringBefore(',')?.trim()?.takeIf { it.isNotEmpty() }?.let { return it }
    }
    return request.local.remoteAddress
}

/** The :web bundle, with a marker so the page asks for a PC ID instead of talking to its own host. */
private class RelayPage {
    private val loader = RelayPage::class.java.classLoader
    private val indexHtml: String? by lazy {
        loader.getResourceAsStream("web/index.html")?.use { it.readBytes().decodeToString() }
            ?.replace("</head>", "    <meta name=\"rc-relay\" content=\"1\">\n</head>")
    }

    fun index(): String? = indexHtml

    fun asset(path: String): ByteArray? {
        val segments = path.split('/')
        if (segments.any { it.isEmpty() || it == "." || it == ".." || '\\' in it }) return null
        return loader.getResourceAsStream("web/$path")?.use { it.readBytes() }
    }

    fun contentType(path: String): ContentType = when {
        path.endsWith(".wasm") -> ContentType("application", "wasm")
        path.endsWith(".mjs") || path.endsWith(".js") -> ContentType.Application.JavaScript
        else -> ContentType.defaultForFilePath(path)
    }
}
