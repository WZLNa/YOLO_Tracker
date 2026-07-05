package com.wzln.yoloTracker

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import com.wzln.yoloTracker.ui.CameraScreen
import com.wzln.yoloTracker.ui.theme.YoloTrackerTheme

class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        setContent {
            YoloTrackerTheme {
                CameraScreen()
            }
        }
    }
}
