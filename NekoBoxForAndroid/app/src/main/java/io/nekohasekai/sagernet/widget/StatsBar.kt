package io.nekohasekai.sagernet.widget

import android.annotation.SuppressLint
import android.content.Context
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
import androidx.lifecycle.whenStarted
import com.google.android.material.bottomappbar.BottomAppBar
import io.nekohasekai.sagernet.R
import io.nekohasekai.sagernet.bg.BaseService
import io.nekohasekai.sagernet.database.DataStore
import io.nekohasekai.sagernet.ktx.*
import io.nekohasekai.sagernet.ui.MainActivity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

class StatsBar @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null,
    defStyleAttr: Int = R.attr.bottomAppBarStyle,
) : BottomAppBar(context, attrs, defStyleAttr) {
    private lateinit var statusText: TextView
    private lateinit var txText: TextView
    private lateinit var rxText: TextView
    private lateinit var behavior: YourBehavior

    var allowShow = true

    private companion object {
        // 连接后等待服务稳定再自动测速(ms)
        const val AUTO_TEST_DELAY = 1200L
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
        viewTreeObserver.addOnPreDrawListener(anchorSync)
    }

    override fun onDetachedFromWindow() {
        viewTreeObserver.removeOnPreDrawListener(anchorSync)
        super.onDetachedFromWindow()
    }

    override fun getBehavior(): YourBehavior {
        if (!this::behavior.isInitialized) behavior = YourBehavior { allowShow }
        return behavior
    }

    /**
     * 收/起由这里自己管，不再依赖 Material 的 HideBottomViewOnScrollBehavior。
     *
     * 那个实现用内部 currentState 做去重：状态在动画开始前就被改成目标值，而
     * currentAnimator.cancel() 会让位置停在半途 —— 快速连续滚动时状态与位置脱节，
     * 之后 rolling 方向与状态对不上就整体失效（「列表划快了底栏就不动了」）。
     *
     * 这里只用 targetY 一个真相：方向变了才换动画，同向连续滚动直接忽略。
     */
    class YourBehavior(val getAllowShow: () -> Boolean) : Behavior() {

        private var targetY = 0f
        private var animator: ViewPropertyAnimator? = null
        private var animating = false

        private fun hiddenOffset(child: View): Float {
            val lp = child.layoutParams as? ViewGroup.MarginLayoutParams
            return (child.height + (lp?.bottomMargin ?: 0)).toFloat()
        }

        /**
         * 绕开 `BottomAppBar.Behavior` 的 hideOnScroll 门控。
         *
         * 它内部是 `getHideOnScroll() && super.onStartNestedScroll(...)`，而 hideOnScroll
         * 只在 StatsBar.changeState() 里赋值 —— 服务已连接时才创建视图、或状态回调漏掉的
         * 情况下这个字段可能是 false，于是底栏**根本收不到**后面的 pre/scroll 回调，
         * 表现为怎么划都不动、且和快慢无关。这里直接用 allowShow 判断，不再依赖那个字段。
         */
        override fun onStartNestedScroll(
            coordinatorLayout: CoordinatorLayout,
            child: BottomAppBar,
            directTargetChild: View,
            target: View,
            axes: Int,
            type: Int,
        ): Boolean {
            if (!getAllowShow()) return false
            if (axes != androidx.core.view.ViewCompat.SCROLL_AXIS_VERTICAL) return false
            // 列表内容不足一屏时根本没有滚动量：一旦把底栏收起来，就没有「下滑」
            // 能再把它抬回来，看起来就是彻底不响应。节点少的分组最容易踩到。
            if (!target.canScrollVertically(-1) && !target.canScrollVertically(1)) return false
            return true
        }

        private fun moveTo(child: View, y: Float, duration: Long, accelerate: Boolean) {
            // 判据是实际位置 + 动画是否还在跑，而不是一个可能脱节的内部状态：
            // 已经到位就不动；同向且动画仍在跑就不打断；其余情况（被打断、方向相反）一律纠正。
            if (kotlin.math.abs(child.translationY - y) < 1f) {
                targetY = y
                return
            }
            if (targetY == y && animating) return
            targetY = y
            animator?.cancel()
            animating = true
            animator = child.animate()
                .translationY(y)
                .setDuration(duration)
                .setInterpolator(
                    if (accelerate) AccelerateInterpolator() else DecelerateInterpolator()
                )
                .setListener(object : android.animation.AnimatorListenerAdapter() {
                    override fun onAnimationEnd(animation: android.animation.Animator) {
                        animating = false
                    }
                })
                .also { it.start() }
        }

        private fun handleScroll(child: BottomAppBar, dy: Int) {
            if (!getAllowShow() || dy == 0) return
            when {
                // 手指上滑（内容上移）→ 收起
                dy > 0 -> moveTo(child, hiddenOffset(child), 175L, true)
                // 手指下滑 → 抬起
                dy < 0 -> moveTo(child, 0f, 225L, false)
            }
        }

        /**
         * 快速划动时 RecyclerView 往往把 dy 全部消费掉，onNestedScroll 里拿到的增量
         * 可能很小甚至为 0；pre-scroll 在内容滚动之前触发，拿的是完整位移，更可靠。
         * 两条路都接上，任一条有 dy 就能驱动底栏。
         */
        override fun onNestedPreScroll(
            coordinatorLayout: CoordinatorLayout,
            child: BottomAppBar,
            target: View,
            dx: Int,
            dy: Int,
            consumed: IntArray,
            type: Int,
        ) {
            handleScroll(child, dy)
        }

        override fun onNestedScroll(
            coordinatorLayout: CoordinatorLayout, child: BottomAppBar, target: View,
            dxConsumed: Int, dyConsumed: Int, dxUnconsumed: Int, dyUnconsumed: Int,
            type: Int, consumed: IntArray,
        ) {
            handleScroll(child, dyConsumed + dyUnconsumed)
        }

        override fun slideUp(child: BottomAppBar) {
            if (!getAllowShow()) return
            moveTo(child, 0f, 225L, false)
        }

        override fun slideDown(child: BottomAppBar) {
            if (!getAllowShow()) return
            moveTo(child, hiddenOffset(child), 175L, true)
        }

        override fun onStopNestedScroll(
            coordinatorLayout: CoordinatorLayout,
            child: BottomAppBar,
            target: View,
            type: Int,
        ) {
            if (!getAllowShow()) return
            // 列表已经不能滚动（内容不满一屏 / 缩到只剩几个节点）→ 底栏必须留在展开状态，
            // 否则没有下滑能把它抬回来
            if (!target.canScrollVertically(-1) && !target.canScrollVertically(1)) {
                slideUp(child)
                return
            }
            // 动画被打断时停在中途：按离哪端近归位，并把 targetY 校正回来
            val hidden = hiddenOffset(child)
            val y = child.translationY
            if (y > 1f && y < hidden - 1f) {
                targetY = if (y > hidden / 2f) hidden else 0f
                animator?.cancel()
                animator = child.animate()
                    .translationY(targetY)
                    .setDuration(120L)
                    .also { it.start() }
            }
        }
    }


    override fun setOnClickListener(l: OnClickListener?) {
        statusText = findViewById(R.id.status)
        txText = findViewById(R.id.tx)
        rxText = findViewById(R.id.rx)
        super.setOnClickListener(l)
    }

    private fun setStatus(text: CharSequence) {
        statusText.text = text
        TooltipCompat.setTooltipText(this, text)
    }

    fun changeState(state: BaseService.State) {
        val activity = context as MainActivity
        fun postWhenStarted(what: () -> Unit) = activity.lifecycleScope.launch(Dispatchers.Main) {
            delay(100L)
            activity.whenStarted { what() }
        }
        if ((state == BaseService.State.Connected).also { hideOnScroll = it }) {
            postWhenStarted {
                if (allowShow) performShow()
                setStatus(app.getText(R.string.vpn_connected))
                // 连接后自动测一次当前节点的延迟，结果显示在底栏状态里
                postDelayed({
                    if (isAttachedToWindow && isEnabled &&
                        DataStore.serviceState.connected
                    ) {
                        testConnection()
                    }
                }, AUTO_TEST_DELAY)
            }
        } else {
            postWhenStarted {
                performHide()
            }
            updateSpeed(0, 0)
            setStatus(
                context.getText(
                    when (state) {
                        BaseService.State.Connecting -> R.string.connecting
                        BaseService.State.Stopping -> R.string.stopping
                        else -> R.string.not_connected
                    }
                )
            )
        }
    }

    @SuppressLint("SetTextI18n")
    fun updateSpeed(txRate: Long, rxRate: Long) {
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

    fun testConnection() {
        val activity = context as MainActivity
        isEnabled = false
        setStatus(app.getText(R.string.connection_test_testing))
        runOnDefaultDispatcher {
            try {
                val elapsed = activity.urlTest()
                onMainDispatcher {
                    isEnabled = true
                    setStatus(
                        app.getString(
                            if (DataStore.connectionTestURL.startsWith("https://")) {
                                R.string.connection_test_available
                            } else {
                                R.string.connection_test_available_http
                            }, elapsed
                        )
                    )
                }

            } catch (e: Exception) {
                Logs.w(e.toString())
                onMainDispatcher {
                    isEnabled = true
                    setStatus(app.getText(R.string.connection_test_testing))

                    activity.snackbar(
                        app.getString(
                            R.string.connection_test_error, e.readableMessage
                        )
                    ).show()
                }
            }
        }
    }

}
