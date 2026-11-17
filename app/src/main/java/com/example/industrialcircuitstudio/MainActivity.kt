package com.example.industrialcircuitstudio

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.lightColorScheme
import com.example.industrialcircuitstudio.ui.EditorScreen

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme(colorScheme = lightColorScheme(
                primary = androidx.compose.ui.graphics.Color(0xFF16324F),
                secondary = androidx.compose.ui.graphics.Color(0xFF00A6A6),
                surface = androidx.compose.ui.graphics.Color(0xFFF4F6F8)
            )) { EditorScreen() }
        }
    }
}
