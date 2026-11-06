package li.gkd.app.network

import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.HttpTimeoutConfig
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.http.ContentType
import io.ktor.serialization.kotlinx.json.json
import kotlinx.serialization.json.Json

object NetworkClients {
    private val sharedClient = lazy { create() }
    val client: HttpClient
        get() = sharedClient.value

    fun close() {
        if (sharedClient.isInitialized()) sharedClient.value.close()
    }

    private fun create() =
        HttpClient(OkHttp) {
            install(ContentNegotiation) {
                json(
                    Json {
                        ignoreUnknownKeys = true
                        explicitNulls = false
                        encodeDefaults = true
                    },
                    ContentType.Any,
                )
            }
            // 订阅、图片、APK 下载都是长耗时请求，默认不设整体超时；
            // 需要短超时的地方（更新检查）用 per-request 的 timeout {} 单独指定。
            install(HttpTimeout) {
                requestTimeoutMillis = HttpTimeoutConfig.INFINITE_TIMEOUT_MS
            }
            engine { clientCacheSize = 0 }
        }
}
