package li.gkd.app.util

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import io.ktor.client.HttpClient
import kotlinx.serialization.json.Json
import li.gkd.app.network.NetworkClients

/** 通用 JSON；AI 相关代码沿用旧 util.json 的名字。 */
val json = Json {
    ignoreUnknownKeys = true
    explicitNulls = false
    encodeDefaults = true
}

/** 复用全局 Ktor 客户端，避免 AI 设置页每次新建连接池。 */
val client: HttpClient
    get() = NetworkClients.client

/** 保留显式 null 的快照 JSON；与 SnapshotStore 落盘格式保持一致。 */
val keepNullJson = Json {
    ignoreUnknownKeys = true
    explicitNulls = true
    encodeDefaults = true
}

private class ThrottleTimer(private val interval: Long = 500L) {
    private var lastAccessTime: Long = 0L

    fun expired(): Boolean {
        val t = System.currentTimeMillis()
        if (t - lastAccessTime > interval) {
            lastAccessTime = t
            return true
        }
        return false
    }
}

@Composable
fun throttle(fn: () -> Unit): () -> Unit {
    val timer = remember { ThrottleTimer() }
    return remember(fn) {
        {
            if (timer.expired()) fn()
        }
    }
}

@Composable
fun <T> throttle(fn: (T) -> Unit): (T) -> Unit {
    val timer = remember { ThrottleTimer() }
    return remember(fn) {
        { value ->
            if (timer.expired()) fn(value)
        }
    }
}

/** 兼容 GKD-Plus 旧代码的简短 toast；统一走 [ToastUtils.show]。 */
fun toast(text: CharSequence, forced: Boolean = false) {
    ToastUtils.show(text, forced)
}
