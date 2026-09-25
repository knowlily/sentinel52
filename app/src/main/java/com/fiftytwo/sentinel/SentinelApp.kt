package com.fiftytwo.sentinel

import android.app.Application
import com.fiftytwo.sentinel.data.SentinelStore
import com.fiftytwo.sentinel.service.Notifier

class SentinelApp : Application() {

    override fun onCreate() {
        super.onCreate()
        SentinelStore.init(this)
        Notifier.ensureChannel(this)
        // 这里**故意不探测**后端状态：探测要调 Dhizuku 的 ContentProvider 和 Stellar 的 binder，
        // 而 onCreate 跑在主线程上。状态由界面（IO 线程）和监控服务各自探。
    }
}
