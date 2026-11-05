package io.nekohasekai.sagernet.widget

import android.annotation.SuppressLint
import android.content.Context
import android.text.format.Formatter
import android.util.AttributeSet
import android.view.View
import android.view.ViewGroup
import android.view.ViewTreeObserver
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

    // 底栏滑动时同步位移的目标（layout_main.xml 里的启动按钮）
    private var fab: View? = null

    var allowShow = true

    private companion object {
        // 连接后等待服务稳定再自动测速(ms)
        const val AUTO_TEST_DELAY = 1200L
    }

    /**
     * 速度栏滑入滑出靠的是它自己的 translationY。CoordinatorLayout 在布局 FAB 时
     * 用的是 anchor 的 **layout 边界**（getTop/getBottom），**不包含 translationY** ——
     * 所以无论怎么 requestLayout()，FAB 都不会跟着抬起来，这就是「有时候按钮抬不起来」
     * 的真正原因（能抬起的那几次是被别的原因顺手触发了 layout）。
     *
     * 可靠做法：让 FAB 的 translationY 与底栏严格相等。两者的相对位置不变，
     * BottomAppBar 自己画的圆弧 cradle 也随底栏一起移动，外观与原来完全一致。
     * 这里同时用 setter + 每帧 preDraw 两条路，覆盖 ViewPropertyAnimator 绕过 setter 的情况。
     */
    override fun setTranslationY(translationY: Float) {
        super.setTranslationY(translationY)
        syncFabTranslation(translationY)
    }

    private fun syncFabTranslation(translationY: Float) {
        val target = fab ?: return
        if (target.translationY != translationY) {
            target.translationY = translationY
        }
    }

    private val anchorSync = ViewTreeObserver.OnPreDrawListener {
        syncFabTranslation(translationY)
        true
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        fab = (parent as? ViewGroup)?.findViewById(R.id.fab)
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

    class YourBehavior(val getAllowShow: () -> Boolean) : Behavior() {

        override fun onNestedScroll(
            coordinatorLayout: CoordinatorLayout, child: BottomAppBar, target: View,
            dxConsumed: Int, dyConsumed: Int, dxUnconsumed: Int, dyUnconsumed: Int,
            type: Int, consumed: IntArray,
        ) {
            super.onNestedScroll(
                coordinatorLayout,
                child,
                target,
                dxConsumed,
                dyConsumed + dyUnconsumed,
                dxUnconsumed,
                0,
                type,
                consumed
            )
        }

        override fun slideUp(child: BottomAppBar) {
            if (!getAllowShow()) return
            super.slideUp(child)
        }

        override fun slideDown(child: BottomAppBar) {
            if (!getAllowShow()) return
            super.slideDown(child)
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
