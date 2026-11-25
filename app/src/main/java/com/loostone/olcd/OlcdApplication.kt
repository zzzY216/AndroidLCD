package com.loostone.olcd

import android.app.Application
import dagger.hilt.android.HiltAndroidApp

/**
 * Application 入口。
 *
 * `@HiltAndroidApp` 会触发 Hilt 生成依赖注入容器。
 */
@HiltAndroidApp
class OlcdApplication : Application()
