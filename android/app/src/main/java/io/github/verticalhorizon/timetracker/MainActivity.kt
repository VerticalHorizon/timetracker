package io.github.verticalhorizon.timetracker

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import io.github.verticalhorizon.timetracker.ui.App
import io.github.verticalhorizon.timetracker.ui.theme.ZenTheme

class MainActivity : ComponentActivity() {
    private val vm: AppViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent { ZenTheme { App(vm) } }
    }

    // The app talks to the tracker only while it is on screen. That loses nothing:
    // the tracker keeps counting, and the next connection picks the time up.
    override fun onStart() {
        super.onStart()
        vm.onForeground()
    }

    override fun onStop() {
        if (!isChangingConfigurations) vm.onBackground()
        super.onStop()
    }
}
