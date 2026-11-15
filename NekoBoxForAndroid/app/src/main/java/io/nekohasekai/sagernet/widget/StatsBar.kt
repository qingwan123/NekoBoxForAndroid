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
import android.view.ViewPropertyAnimator
import android.view.ViewTreeObserver
import android.view.animation.AccelerateInterpolator
import android.view.animation.DecelerateInterpolator
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
import kotlin.math.abs

/**
 * 底部速度栏。
 *
 * 外观保持原样（三行：上行、下行、状态文字），只把**交互**换成 OwnBox 那套：
 * - 可见性 = 在节点页 && 已连接 && 未被手势收起（未连接时绝不出现）
 * - 连接成功后自动静默测一次握手延迟，结果显示在状态行
 * - 点击底栏 = 立即重测；切节点 / 回到前台 / 改测试链接都会静默重测
 * - 节点列表上滑收起、下滑弹出（列表不可滚时用手势位移驱动）
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

        const val SHOW_ANIM_MS = 225L
        const val HIDE_ANIM_MS = 175L
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
    private lateinit var txText: TextView
    private lateinit var rxText: TextView
    private lateinit var behavior: YourBehavior

    private var currentState = BaseService.State.Idle

    // 默认隐藏：非节点页（工具/设置/分组…）不该出现速度栏。
    var allowShow = false
        set(value) {
            if (field == value) return
            field = value
            if (value) scrollHidden = false
            applyVisibility(animate = isLaidOut)
        }

    // 手势收起状态（上滑收起、下滑弹出）
    private var scrollHidden = false
    private var scrollDir = 0
    private var scrollAccum = 0
    private val scrollThresholdPx =
        (SCROLL_TOGGLE_THRESHOLD_DP * resources.displayMetrics.density).toInt().coerceAtLeast(8)

    // 位移动画状态（用截止时间而不是布尔量：动画被 cancel 时 endAction 不一定回调）
    private var animationEndAt = 0L
    private var animator: ViewPropertyAnimator? = null

    // 延迟测速状态
    private var lastLatency = -1
    private var lastTestFailed = false
    private var lastTestAt = 0L
    private var testing = false
    private var testSeq = 0
    private var testJob: Job? = null
    private var autoTestRunnable: Runnable? = null

    override fun getBehavior(): YourBehavior {
        if (!this::behavior.isInitialized) behavior = YourBehavior()
        return behavior
    }

    /**
     * 收/起完全由手势驱动（见 ConfigurationFragment 的滚动/触摸监听），
     * 不参与列表的嵌套滚动 —— 内容不足一屏时没有滚动量，嵌套滚动一个回调都不会派发。
     */
    class YourBehavior : Behavior() {

        override fun onStartNestedScroll(
            coordinatorLayout: CoordinatorLayout,
            child: BottomAppBar,
            directTargetChild: View,
            target: View,
            axes: Int,
            type: Int,
        ): Boolean = false
    }

    private fun isConnected(): Boolean =
        currentState == BaseService.State.Connected || DataStore.serviceState.connected

    private fun shouldShow(): Boolean = allowShow && isConnected() && !scrollHidden

    private fun hiddenOffset(): Float {
        val lp = layoutParams as? ViewGroup.MarginLayoutParams
        return (height + (lp?.bottomMargin ?: 0)).toFloat()
    }

    /**
     * 速度栏滑入滑出靠的是 translationY，而 CoordinatorLayout 在布局 FAB 时用的是
     * anchor 的屏幕坐标（含 translationY）。translationY 变化只重绘不重新布局，
     * FAB 要等下一次 layout 才被抬起 —— 表现就是「有时候按钮抬不起来」。
     */
    override fun setTranslationY(translationY: Float) {
        super.setTranslationY(translationY)
        requestAnchorRelayout()
    }

    private var lastTranslationY = Float.NaN

    private fun requestAnchorRelayout() {
        val parentView = parent as? ViewGroup ?: return
        if (parentView.isLayoutRequested) return
        parentView.post { if (!parentView.isLayoutRequested) parentView.requestLayout() }
    }

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
        applyVisibility(animate = false)
    }

    override fun onDetachedFromWindow() {
        viewTreeObserver.removeOnPreDrawListener(anchorSync)
        cancelAutoTest()
        testJob?.cancel()
        super.onDetachedFromWindow()
    }

    override fun onLayout(changed: Boolean, left: Int, top: Int, right: Int, bottom: Int) {
        super.onLayout(changed, left, top, right, bottom)
        // 布局阶段兜底：未连接 / 不在节点页时直接放到屏幕外，避免「装好就显示」和闪一下
        if (SystemClock.uptimeMillis() < animationEndAt) return
        translationY = if (shouldShow()) 0f else hiddenOffset()
    }

    private fun initViews() {
        if (this::statusText.isInitialized) return
        statusText = findViewById(R.id.status)
        txText = findViewById(R.id.tx)
        rxText = findViewById(R.id.rx)
        statusText.isSelected = true
    }

    private fun applyVisibility(animate: Boolean) {
        initViews()
        val target = if (shouldShow()) 0f else hiddenOffset()
        if (!isLaidOut || height == 0) {
            // 还没布局：先摆好位置，onLayout 会再兜一次
            animationEndAt = 0L
            translationY = target
            return
        }
        val duration =
            if (target == 0f) SHOW_ANIM_MS else HIDE_ANIM_MS
        if (!animate || abs(translationY - target) < 1f) {
            animator?.cancel()
            animationEndAt = 0L
            translationY = target
            return
        }
        animator?.cancel()
        animationEndAt = SystemClock.uptimeMillis() + duration + 50L
        animator = animate()
            .translationY(target)
            .setDuration(duration)
            .setInterpolator(
                if (target == 0f) DecelerateInterpolator() else AccelerateInterpolator()
            )
            .withEndAction { animationEndAt = 0L }
            .also { it.start() }
    }

    private fun render() {
        initViews()
        if (!isConnected()) {
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
        val https = DataStore.connectionTestURL.startsWith("https://", true)
        statusText.text = when {
            testing -> app.getText(R.string.connection_test_testing)

            lastLatency > 0 -> app.getString(
                if (https) R.string.connection_test_available
                else R.string.connection_test_available_http,
                lastLatency
            )

            lastTestFailed -> app.getText(R.string.connection_test_fail)

            else -> app.getText(R.string.vpn_connected)
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

        testJob = activity.lifecycleScope.launch(Dispatchers.IO) {
            var latency = -1
            var error: Exception? = null
            try {
                latency = activity.urlTest()
            } catch (e: Exception) {
                error = e
            }
            val finishedAt = SystemClock.elapsedRealtime()
            runOnUi {
                if (seq != testSeq) return@runOnUi
                testing = false
                lastTestAt = finishedAt
                if (latency > 0) {
                    lastLatency = latency
                    lastTestFailed = false
                } else {
                    lastLatency = -1
                    lastTestFailed = true
                    if (error != null) Logs.w("stats bar url test failed: ${error.readableMessage}")
                }
                render()
                if (!silent && error != null) {
                    activity.snackbar(
                        app.getString(R.string.connection_test_error, error.readableMessage)
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
            val nowConnected = isConnected()
            if (!nowConnected) {
                resetLatencyState()
                updateSpeedInternal(0, 0)
            }
            if (nowConnected && !wasConnected) {
                scrollHidden = false
                render()
                applyVisibility(animate = true)
                scheduleAutoTest()
            } else {
                render()
                applyVisibility(animate = wasConnected && !nowConnected)
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
            applyVisibility(animate = false)
            if (isConnected() && lastLatency <= 0 && !testing) {
                testConnection(silent = true)
            }
        }
    }

    fun updateSpeed(txRate: Long, rxRate: Long) {
        runOnUi { updateSpeedInternal(txRate, rxRate) }
    }

    @SuppressLint("SetTextI18n")
    private fun updateSpeedInternal(txRate: Long, rxRate: Long) {
        initViews()
        txText.text = "▲  ${
            context.getString(R.string.speed, Formatter.formatFileSize(context, txRate))
        }"
        rxText.text = "▼  ${
            context.getString(R.string.speed, Formatter.formatFileSize(context, rxRate))
        }"
    }

    // ---------------- 手势驱动收/起 ----------------

    private fun resetScrollDriverState() {
        scrollDir = 0
        scrollAccum = 0
    }

    /**
     * @param dy 节点列表滚动量 / 手指位移：>0 收起，<0 弹出
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
        applyVisibility(animate = true)
    }

    /** 换分组 / 重建列表后调用：内容可能不再可滚，别把底栏永久留在收起状态 */
    fun resetScrollHidden() {
        if (!scrollHidden) return
        scrollHidden = false
        resetScrollDriverState()
        runOnUi { applyVisibility(animate = true) }
    }
}
