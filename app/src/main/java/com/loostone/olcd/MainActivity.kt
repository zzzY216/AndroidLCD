package com.loostone.olcd

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import com.loostone.olcd.ui.MainScreen
import com.loostone.olcd.ui.theme.OLCDTheme
import dagger.hilt.android.AndroidEntryPoint

/**
 * 主 Activity。
 *
 * `@AndroidEntryPoint` 让 Hilt 能向这个 Activity 及其 Compose 树注入依赖。
 */
@AndroidEntryPoint
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            OLCDTheme {
                MainScreen()
            }
        }
    }
}
