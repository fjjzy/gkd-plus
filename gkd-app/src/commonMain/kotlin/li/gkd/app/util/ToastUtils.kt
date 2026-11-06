package li.gkd.app.util

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import li.gkd.app.app.applicationScope
import li.gkd.app.logging.LogUtils
import li.gkd.app.platform.writeClipboardText
import li.songe.codeorigin.CallSite

object ToastUtils {
    fun show(
        text: CharSequence,
        forced: Boolean = false,
        delayMillis: Long = 0L,
        @CallSite loc: String = "",
    ) {
        applicationScope().launch(Dispatchers.Main.immediate) {
            if (delayMillis > 0) delay(delayMillis)
            showPlatformToast(text, forced)
            if (loc.isNotEmpty()) LogUtils.i(text, loc = loc)
        }
    }

    /** 只写剪贴板、不弹「已复制」，调用方自己还要 toast 业务提示时用。 */
    fun copyTextSilently(text: String) {
        writeClipboardText(text)
    }
}

expect fun showPlatformToast(text: CharSequence, forced: Boolean)
