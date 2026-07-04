package com.example.yolotracker

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import com.example.yolotracker.ui.CameraScreen
import com.example.yolotracker.ui.theme.YoloTrackerTheme

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
