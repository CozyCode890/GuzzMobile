package io.github.cozycode890.guzz

import android.app.Application

class GuzzApp : Application() {
    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
        // Gắn log trước mọi việc khác để lỗi lúc khởi động cũng vào tệp.
        AppLogging.start(container)
    }
}
