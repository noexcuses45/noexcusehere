package com.noexcusehere.vpn

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import com.noexcusehere.vpn.ui.AppRoot
import com.noexcusehere.vpn.ui.KestrelTheme
import com.noexcusehere.vpn.ui.MainViewModel

class MainActivity : ComponentActivity() {

    private val viewModel: MainViewModel by viewModels { MainViewModel.Factory }

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        setContent {
            KestrelTheme {
                AppRoot(viewModel)
            }
        }
    }
}
