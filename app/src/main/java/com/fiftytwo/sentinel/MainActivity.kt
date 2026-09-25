package com.fiftytwo.sentinel

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import com.fiftytwo.sentinel.data.SentinelStore
import com.fiftytwo.sentinel.service.MonitorRunner
import com.fiftytwo.sentinel.service.Notifier
import com.fiftytwo.sentinel.ui.MainScreen
import com.fiftytwo.sentinel.ui.MainViewModel
import com.fiftytwo.sentinel.ui.SentinelTheme

class MainActivity : ComponentActivity() {

    // 界面和 Activity 共用同一个 ViewModel：Activity 要在 onResume 里刷新电池优化状态
    private val viewModel: MainViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        SentinelStore.init(this)
        Notifier.ensureChannel(this)
        alignMonitoringState()
        // 从通知进来看不到状态栏底下那块内容，所以开边到边 + 界面自己吃 insets
        enableEdgeToEdge()
        setContent {
            SentinelTheme {
                MainScreen(viewModel)
            }
        }
    }

    override fun onResume() {
        super.onResume()
        // 用户去系统界面点过「允许」再回来，这里才能读到新状态
        viewModel.refreshBattery()
    }

    /**
     * 把「说在监控」和「真的在监控」对齐。
     *
     * 持久化的监控开关只是**意愿**：应用被更新、被系统杀掉、或从后台重启之后，
     * 那个开关还是 true，但服务已经没了。Android 12+ 不允许后台广播
     * （`MY_PACKAGE_REPLACED` 这类）直接拉起前台服务，启动请求会被系统拒掉，
     * 于是界面显示「正在盯着名单」而实际没人在盯——装上来的东西就不会被摘掉。
     *
     * 界面在前台时启动前台服务是允许的，所以每次打开界面都补一次：
     * 服务已经在跑的话，这一下只是让它立刻补扫一轮，没有副作用。
     */
    private fun alignMonitoringState() {
        if (SentinelStore.state.value.monitoring) {
            MonitorRunner.start(this)
        }
    }
}
