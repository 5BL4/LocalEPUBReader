package com.epubreader.app.ui.spike

import android.annotation.SuppressLint
import android.app.Activity
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import android.webkit.JavascriptInterface
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.epubreader.app.core.log.AppLogger
import java.util.concurrent.atomic.AtomicInteger

/**
 * Throwaway spike to verify RecyclerView + multiple WebView feasibility.
 *
 * Verifies:
 *  - WebView creation/destruction in RecyclerView adapter lifecycle
 *  - Height reporting via onPageFinished + evaluateJavascript
 *  - JS bridge callback isolation (no crosstalk between recycled WebViews)
 *  - Stress test: 20 rapid setAdapter + scroll + notifyDataSetChanged cycles
 *
 * Launch via adb:
 *   adb shell am start -n com.epubreader.app/.ui.spike.WebViewSpikeActivity
 */
@SuppressLint("SetJavaScriptEnabled")
class WebViewSpikeActivity : Activity() {

    companion object {
        private const val TAG = "WebViewSpike"
        private const val ITEM_COUNT = 3
    }

    private val createdCount = AtomicInteger(0)
    private val destroyedCount = AtomicInteger(0)

    private lateinit var recyclerView: RecyclerView
    private lateinit var infoText: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        AppLogger.i(TAG, "Activity onCreate")

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
        }

        // -- Top bar: stress button + info --
        val topBar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(16, 16, 16, 16)
        }

        val stressButton = Button(this).apply {
            text = "循环创建/销毁 20 次"
            setOnClickListener { runStressTest() }
        }
        topBar.addView(stressButton)

        infoText = TextView(this).apply {
            text = "就绪"
            setPadding(32, 0, 0, 0)
        }
        topBar.addView(infoText)
        root.addView(topBar)

        // -- RecyclerView --
        recyclerView = RecyclerView(this).apply {
            layoutManager = LinearLayoutManager(this@WebViewSpikeActivity)
            adapter = SpikeAdapter()
        }
        root.addView(recyclerView, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            0, 1f
        ))

        setContentView(root)
        updateInfoLabel()
    }

    override fun onDestroy() {
        super.onDestroy()
        AppLogger.i(TAG, "Activity onDestroy — created=${createdCount.get()} destroyed=${destroyedCount.get()}")
    }

    // -------------------------------------------------------------------------
    // Stress test
    // -------------------------------------------------------------------------

    private fun runStressTest() {
        AppLogger.i(TAG, "========== 开始压力测试 20 次循环 ==========")
        val handler = Handler(Looper.getMainLooper())

        for (i in 0 until 20) {
            handler.postDelayed({
                val newAdapter = SpikeAdapter()
                recyclerView.adapter = newAdapter
                recyclerView.scrollToPosition(i % ITEM_COUNT)
                newAdapter.notifyDataSetChanged()
                AppLogger.d(TAG, "压力测试: iteration=$i, adapter swapped")

                if (i == 19) {
                    // Last iteration — log results after a short delay for layout to settle
                    handler.postDelayed({
                        AppLogger.i(TAG, "========== 压力测试完成 ==========")
                        AppLogger.i(TAG, "RecyclerView childCount=${recyclerView.childCount}")
                        AppLogger.i(TAG, "已创建 WebView 总数=${createdCount.get()}")
                        AppLogger.i(TAG, "已销毁 WebView 总数=${destroyedCount.get()}")
                        AppLogger.i(TAG, "差值(创建-销毁)=${createdCount.get() - destroyedCount.get()}")
                        updateInfoLabel()
                    }, 300)
                }
            }, i * 200L)
        }
    }

    private fun updateInfoLabel() {
        infoText.text = "created=${createdCount.get()}  destroyed=${destroyedCount.get()}"
    }

    // -------------------------------------------------------------------------
    // HTML builder
    // -------------------------------------------------------------------------

    private fun buildHtml(itemIndex: Int): String {
        val paragraphs = buildString {
            // Generate enough text to make the body ~2000px tall
            repeat(40) { i ->
                append("<p>Item $itemIndex — 段落 ${i + 1}: ")
                append("这是一段用于填充 WebView 内容的长文本。目标是让整体高度达到约 2000px，")
                append("以便验证 WebView 内部滚动和 RecyclerView 外部滚动的嵌套行为。")
                append("Lorem ipsum dolor sit amet, consectetur adipiscing elit.")
                append("</p>\n")
            }
        }

        return """
<!DOCTYPE html>
<html>
<head>
<meta charset="utf-8">
<meta name="viewport" content="width=device-width, initial-scale=1.0">
<style>
  body { font-size: 16px; padding: 16px; margin: 0; background: #fff; }
  h2 { color: #333; }
  p { line-height: 1.8; margin: 12px 0; color: #555; }
</style>
</head>
<body>
<h2>WebView Item $itemIndex</h2>
$paragraphs
<script>
document.addEventListener('DOMContentLoaded', function() {
  if (window.SpikeBridge) {
    window.SpikeBridge.onSpikeEvent('loaded:$itemIndex');
  }
});
// Fallback: if DOMContentLoaded already fired (script at bottom of body),
// call immediately
if (document.readyState === 'complete' || document.readyState === 'interactive') {
  if (window.SpikeBridge) {
    window.SpikeBridge.onSpikeEvent('loaded:$itemIndex');
  }
}
</script>
</body>
</html>
        """.trimIndent()
    }

    // -------------------------------------------------------------------------
    // JS Bridge
    // -------------------------------------------------------------------------

    inner class SpikeJsBridge(
        val webViewHash: Int,
        var itemIndex: Int
    ) {
        @JavascriptInterface
        fun onSpikeEvent(msg: String) {
            AppLogger.i(TAG, "bridge:$itemIndex:$msg  (WV=$webViewHash)")
        }
    }

    // -------------------------------------------------------------------------
    // Adapter
    // -------------------------------------------------------------------------

    inner class SpikeAdapter : RecyclerView.Adapter<SpikeAdapter.VH>() {

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
            val screenHeight = resources.displayMetrics.heightPixels
            val itemHeight = screenHeight.coerceAtLeast(1200)

            val wv = WebView(this@WebViewSpikeActivity).apply {
                layoutParams = ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    itemHeight
                )
                settings.javaScriptEnabled = true

                webViewClient = object : WebViewClient() {
                    override fun onPageFinished(view: WebView, url: String) {
                        super.onPageFinished(view, url)
                        val h = view.height
                        view.evaluateJavascript("document.body.scrollHeight") { result ->
                            AppLogger.i(TAG, "高度上报: webView.height=$h  contentHeight=$result  WV=${view.hashCode()}")
                        }
                    }
                }
            }

            val count = createdCount.incrementAndGet()
            AppLogger.i(TAG, "WebView 创建 #$count,  hashCode=${wv.hashCode()}")

            // Create bridge (itemIndex will be updated in onBindViewHolder)
            val bridge = SpikeJsBridge(wv.hashCode(), 0)

            // Register bridge (API 17+ requires @JavascriptInterface)
            wv.addJavascriptInterface(bridge, "SpikeBridge")

            return VH(wv, bridge)
        }

        override fun onBindViewHolder(holder: VH, position: Int) {
            holder.bridge.itemIndex = position
            val html = buildHtml(position)
            holder.wv.loadDataWithBaseURL(null, html, "text/html", "UTF-8", null)
            AppLogger.d(TAG, "bind: position=$position  WV=${holder.wv.hashCode()}")
        }

        override fun onViewRecycled(holder: VH) {
            val idx = holder.bridge.itemIndex
            val hash = holder.wv.hashCode()
            holder.wv.destroy()
            val count = destroyedCount.incrementAndGet()
            AppLogger.i(TAG, "recycled: itemIndex=$idx  WV=$hash  销毁 #$count")
            updateInfoLabel()
            super.onViewRecycled(holder)
        }

        override fun getItemCount(): Int = ITEM_COUNT

        inner class VH(view: View, val bridge: SpikeJsBridge) : RecyclerView.ViewHolder(view) {
            val wv: WebView = view as WebView
        }
    }
}
