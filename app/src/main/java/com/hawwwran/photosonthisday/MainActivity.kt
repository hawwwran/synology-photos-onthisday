package com.hawwwran.photosonthisday

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import com.hawwwran.photosonthisday.ui.AppRoot
import com.hawwwran.photosonthisday.ui.theme.OnThisDayTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val graph = (application as OnThisDayApp).graph
        setContent {
            OnThisDayTheme {
                AppRoot(graph)
            }
        }
    }
}
