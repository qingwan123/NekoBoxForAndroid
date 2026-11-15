package io.nekohasekai.sagernet.widget

import android.annotation.SuppressLint
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.text.format.Formatter
import android.util.AttributeSet
import android.view.View
import android.view.ViewGroup
import android.view.ViewTreeObserver
import android.widget.ImageView
import android.widget.TextView
import androidx.appcompat.widget.TooltipCompat
import androidx.coordinatorlayout.widget.CoordinatorLayout
import androidx.lifecycle.lifecycleScope
import com.google.android.material.bottomappbar.BottomAppBar
import io.nekohasekai.sagernet.R
import io.nekohasekai.sagernet.bg.BaseService
import io.nekohasekai.sagernet.database.DataStore
import io.nekohasekai.sagernet.ktx.Logs
import io.nekohasekai.sagernet.ktx.app
import io.nekohasekai.sagernet.ktx.readableMessage
import io.nekohasekai.sagernet.ui.MainActivity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.math.abs

/**
 * 底部速度 / 延迟栏。
 *
 * 交互（对齐 OwnBox）：
 * - 已连接后自动静默测一次当前节点的握手延迟，结果显示在右肩（`HTTPS 握手延迟 / 128ms`）；
 * - 点击底栏任意位置 = 立即重测（显示「测试中…」）；
 * - 点右侧刷新图标 = 同上；
 * - 节点列表上滑收起、下滑弹出；只有节点页会显示它。
 */
class StatsBar @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null,
    defStyleAttr: Int = R.attr.bottomAppBarStyle,
) : BottomAppBar(context, attrs, defStyleAttr) {
    private companion object {
        // 连接后等内核稳定再自动测速（ms）
        const val AUTO_TEST_DELAY_MS = 1200L

        // 静默测速的最小间隔，避免连接/切节点时连打好几次
        const val SILENT_RETEST_MIN_INTERVAL_MS = 500L

        // 单次测速兜底超时：bg 进程可能中途退出，回调永远不来
        const val TEST_TIMEOUT_MS = 20_000L

        const val SCROLL_TOGGLE_THRESHOLD_DP = 8f
    }

    private val mainHandler by lazy { Handler(Looper.getMainLooper()) }

    private fun runOnUi(block: () -> Unit) {
        if (Looper.myLooper() == Looper.getMainLooper()) {
            block()
        } else {
            mainHandler.post(block)
        }
    }

    private lateinit var statusText: TextView
    private lateinit var statusTitle: TextView
    private lateinit var txText: TextView
    private lateinit var rxText: TextView
    private var retestButton: ImageView? = null
    private lateinit var behavior: YourBehavior

    private var currentState = BaseService.State.Idle

    // 默认隐藏：非节点页（工具/设置/分组…）不该出现速度栏。
    // 之前默认 true，Activity 重建（比如工具页导入备份后重启）时底栏会自己冒出来。
    var allowShow = false
        set(value) {
            field = value
            if (!value) {
                scrollHidden = false
                resetScrollDriverState()
                hideImmediately()
            } else {
                runOnUi { render() }
            }
        }

    // 手势收起状态（上滑收起、下滑弹出）
    private var scrollHidden = false
    private var scrollDir = 0
    private var scrollAccum = 0
    private val scrollThresholdPx =
        (SCROLL_TOGGLE_THRESHOLD_DP * resources.displayMetrics.density).toInt().coerceAtLeast(8)

    // 延迟测速状态
    private var lastLatency = -1
    private var lastTestFailed = false
    private var lastTestAt = 0L
    private var testing = false
    private var testSeq = 0
    private var testJob: Job? = null
    private var autoTestRunnable: Runnable? = null

    override fun getBehavior(): YourBehavior {
        if (!this::behavior.isInitialized) behavior = YourBehavior { allowShow }
        return behavior
    }

    /**
     * 底栏收/起改由**手势**驱动（见 ConfigurationFragment 里的滚动/触摸监听），
     * 不再参与列表的嵌套滚动 —— 内容不足一屏时列表没有滚动量，嵌套滚动一个
     * 回调都不会派发，底栏就永远不动。
     */
    class YourBehavior(val getAllowShow: () -> Boolean) : Behavior() {

        // 不接收列表滚动：收/起只由手势触发
        override fun onStartNestedScroll(
            coordinatorLayout: CoordinatorLayout,
            child: BottomAppBar,
            directTargetChild: View,
            target: View,
            axes: Int,
            type: Int,
        ): Boolean = false

        override fun slideUp(child: BottomAppBar) {
            if (!getAllowShow()) return
            super.slideUp(child)
        }

        override fun slideDown(child: BottomAppBar) {
            super.slideDown(child)
        }
    }

    private fun hiddenOffset(): Float {
        val lp = layoutParams as? ViewGroup.MarginLayoutParams
        return (height + (lp?.bottomMargin ?: 0)).toFloat()
    }

    private fun hideImmediately() {
        if (height == 0) return // 还没布局，交给 onLayout 兜底
        clearAnimation()
        translationY = hiddenOffset()
    }

    override fun onLayout(changed: Boolean, left: Int, top: Int, right: Int, bottom: Int) {
        super.onLayout(changed, left, top, right, bottom)
        // 首次布局 / 不该显示 / 已手势收起时，直接放到屏幕外，避免闪一下
        if ((!allowShow || scrollHidden) && height > 0) {
            translationY = hiddenOffset()
        }
    }

    /**
     * 速度栏滑入滑出靠的是它自己的 translationY，而 CoordinatorLayout 在布局
     * FAB 时用的是 anchor 的屏幕坐标（包含 translationY）。translationY 变化
     * 只会触发重绘、不会触发重新布局，所以 FAB 要等到下一次 layout（比如文本
     * 变化引起的高度变化）才会被抬起 —— 表现出来就是「有时候按钮抬不起来」。
     *
     * 这里在位移变化时主动请求父容器重新布局：FAB 位置由系统按 anchor 重算，
     * BottomAppBar 的圆弧 cradle 也会跟着重画，外观和位置都不需要我们干预。
     */
    override fun setTranslationY(translationY: Float) {
        super.setTranslationY(translationY)
        requestAnchorRelayout()
    }

    private var lastTranslationY = Float.NaN

    private fun requestAnchorRelayout() {
        val parentView = parent as? ViewGroup ?: return
        if (parentView.isLayoutRequested) return
        // 放到下一帧再布局，避免在绘制/布局过程中直接 requestLayout
        parentView.post { if (!parentView.isLayoutRequested) parentView.requestLayout() }
    }

    /**
     * ViewPropertyAnimator 在硬件加速下不一定走 setTranslationY，
     * 所以额外在每帧绘制前检查位移是否变化，变化了就请求重新布局。
     */
    private val anchorSync = ViewTreeObserver.OnPreDrawListener {
        val current = translationY
        if (current != lastTranslationY) {
            lastTranslationY = current
            requestAnchorRelayout()
        }
        true
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        initViews()
        viewTreeObserver.addOnPreDrawListener(anchorSync)
    }

    override fun onDetachedFromWindow() {
        viewTreeObserver.removeOnPreDrawListener(anchorSync)
        cancelAutoTest()
        testJob?.cancel()
        super.onDetachedFromWindow()
    }

    private fun initViews() {
        if (this::statusText.isInitialized) return
        statusText = findViewById(R.id.status)
        statusTitle = findViewById(R.id.status_title)
        txText = findViewById(R.id.tx)
        rxText = findViewById(R.id.rx)
        retestButton = findViewById<ImageView>(R.id.btn_retest)

        statusText.isSelected = true
        statusTitle.isSelected = true

        val onCardClick = OnClickListener {
            if (isConnected()) testConnection(silent = false)
        }
        super.setOnClickListener(onCardClick)
        findViewById<View>(R.id.stats_content_container)?.setOnClickListener(onCardClick)
        retestButton?.setOnClickListener {
            if (isConnected()) testConnection(silent = false)
        }
    }

    override fun setOnClickListener(l: OnClickListener?) {
        initViews()
        super.setOnClickListener(l)
    }

    private fun isConnected(): Boolean =
        currentState == BaseService.State.Connected || DataStore.serviceState.connected

    private fun isHttpsTest(): Boolean = DataStore.connectionTestURL.startsWith("https://", true)

    private fun render() {
        initViews()
        val connected = isConnected()
        retestButton?.visibility = if (connected) View.VISIBLE else View.GONE
        if (!connected) {
            statusTitle.visibility = View.GONE
            statusText.text = context.getText(
                when (currentState) {
                    BaseService.State.Connecting -> R.string.connecting
                    BaseService.State.Stopping -> R.string.stopping
                    else -> R.string.not_connected
                }
            )
            TooltipCompat.setTooltipText(this, statusText.text)
            return
        }
        statusTitle.text = context.getText(
            if (isHttpsTest()) R.string.connection_test_latency_https
            else R.string.connection_test_latency_http
        )
        when {
            testing -> {
                statusTitle.visibility = View.VISIBLE
                statusText.text = context.getText(R.string.connection_test_testing)
            }

            lastLatency > 0 -> {
                statusTitle.visibility = View.VISIBLE
                statusText.text = "${lastLatency}ms"
            }

            lastTestFailed -> {
                statusTitle.visibility = View.VISIBLE
                statusText.text = context.getText(R.string.connection_test_fail)
            }

            else -> {
                statusTitle.visibility = View.GONE
                statusText.text = context.getText(R.string.vpn_connected)
            }
        }
        TooltipCompat.setTooltipText(this, statusText.text)
    }

    /**
     * 测一次当前节点的握手延迟。
     *
     * @param silent 自动触发（连接成功 / 切换节点 / 回到前台）时为 true：
     *   不打断进行中的测试、500ms 内不重复打、失败不弹 snackbar。
     */
    fun testConnection(silent: Boolean = false) {
        val activity = context as? MainActivity ?: return
        if (!isConnected()) return
        val now = SystemClock.elapsedRealtime()
        if (silent) {
            if (testing) return
            if (lastLatency > 0 && now - lastTestAt < SILENT_RETEST_MIN_INTERVAL_MS) return
        } else {
            testJob?.cancel()
        }

        val seq = ++testSeq
        testing = true
        render()
        if (!silent) {
            retestButton?.let { button ->
                button.animate().cancel()
                button.animate().rotationBy(360f).setDuration(500L).start()
            }
        }

        testJob = activity.lifecycleScope.launch(Dispatchers.IO) {
            var latency = -1
            var error: Exception? = null
            try {
                // 内核侧有 DataStore.connectionTestTimeout 兜底；bg 进程挂掉会抛 DeadObjectException
                latency = activity.urlTest()
            } catch (e: Exception) {
                error = e
            }
            val finishedAt = SystemClock.elapsedRealtime()
            runOnUi {
                if (seq != testSeq) return@runOnUi
                testing = false
                if (!isConnected()) {
                    render()
                    return@runOnUi
                }
                lastTestAt = finishedAt
                if (latency > 0) {
                    lastLatency = latency
                    lastTestFailed = false
                } else {
                    lastLatency = -1
                    lastTestFailed = true
                    if (error != null) {
                        Logs.w("stats bar url test failed: ${error.readableMessage}")
                    }
                }
                render()
                if (!silent && error != null) {
                    activity.snackbar(
                        context.getString(R.string.connection_test_error, error.readableMessage)
                    ).show()
                }
            }
        }
    }

    private fun cancelAutoTest() {
        autoTestRunnable?.let { mainHandler.removeCallbacks(it) }
        autoTestRunnable = null
    }

    private fun resetLatencyState() {
        lastLatency = -1
        lastTestFailed = false
        lastTestAt = 0L
        testing = false
        testSeq++
        testJob?.cancel()
        testJob = null
        cancelAutoTest()
    }

    fun changeState(state: BaseService.State) {
        runOnUi {
            initViews()
            val wasConnected = currentState == BaseService.State.Connected
            currentState = state
            hideOnScroll = state == BaseService.State.Connected
            if (state == BaseService.State.Connected) {
                if (!wasConnected) scrollHidden = false
                if (allowShow) performShow()
                render()
                // 连接后自动测一次当前节点的延迟，结果显示在右肩
                if (!wasConnected) scheduleAutoTest()
            } else {
                resetLatencyState()
                updateSpeed(0, 0)
                render()
                performHide()
            }
        }
    }

    private fun scheduleAutoTest() {
        cancelAutoTest()
        val runnable = Runnable {
            if (isAttachedToWindow && isConnected() && !testing) {
                testConnection(silent = true)
            }
        }
        autoTestRunnable = runnable
        mainHandler.postDelayed(runnable, AUTO_TEST_DELAY_MS)
    }

    /** 回到前台时调用：没有有效延迟就静默补测一次 */
    fun refreshDisplay() {
        runOnUi {
            initViews()
            render()
            if (isConnected() && lastLatency <= 0 && !testing) {
                testConnection(silent = true)
            }
        }
    }

    @SuppressLint("SetTextI18n")
    fun updateSpeed(txRate: Long, rxRate: Long) {
        runOnUi {
            initViews()
            txText.text = "▲  ${
                context.getString(
                    R.string.speed, Formatter.formatFileSize(context, txRate)
                )
            }"
            rxText.text = "▼  ${
                context.getString(
                    R.string.speed, Formatter.formatFileSize(context, rxRate)
                )
            }"
        }
    }

    // ---------------- 手势驱动收/起 ----------------

    private fun resetScrollDriverState() {
        scrollDir = 0
        scrollAccum = 0
    }

    /**
     * @param dy 节点列表的滚动量 / 手指位移量：>0 表示内容上滚（收起），<0 表示下滑（弹出）
     */
    fun onListScrolled(dy: Int) {
        if (!allowShow || !isConnected() || dy == 0) return
        val dir = if (dy > 0) 1 else -1
        if (dir != scrollDir) {
            scrollDir = dir
            scrollAccum = 0
        }
        scrollAccum += dy
        val wantHidden = scrollAccum > 0
        if (wantHidden == scrollHidden) {
            if (abs(scrollAccum) > scrollThresholdPx) {
                scrollAccum = dir * scrollThresholdPx
            }
            return
        }
        if (abs(scrollAccum) < scrollThresholdPx) return
        scrollHidden = wantHidden
        resetScrollDriverState()
        if (wantHidden) performHide() else performShow()
    }

    /** 换分组 / 重建列表后调用：内容可能不再可滚，别把底栏永久留在收起状态 */
    fun resetScrollHidden() {
        if (!scrollHidden) return
        scrollHidden = false
        resetScrollDriverState()
        runOnUi { if (allowShow && isConnected()) performShow() }
    }
}
