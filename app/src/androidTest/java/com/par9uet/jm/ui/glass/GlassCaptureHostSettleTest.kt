package com.par9uet.jm.ui.glass

import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.navigation.compose.rememberNavController
import com.par9uet.jm.ui.screens.LocalMainNavController
import com.par9uet.jm.ui.theme.LocalExtendedColors
import com.par9uet.jm.ui.theme.extendedColorSchemeFor
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import java.util.concurrent.atomic.AtomicInteger

/**
 * Regression test for the GlassCaptureHost feedback loop: any draw of the source used to
 * re-mark the capture dirty, so the host kept re-recording and redrawing forever and Compose
 * instrumentation on a real device never reached idle. A static source must settle, while a
 * real content change must still refresh the capture (no stale glass).
 */
class GlassCaptureHostSettleTest {
    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private val sourceText = mutableStateOf("静态内容")
    private val drawCount = AtomicInteger(0)
    private var observing = false

    @Test
    fun staticSourceStopsSchedulingDraws() {
        startHost()
        compose.waitUntil(10_000) { true }

        startDrawObserver()
        Thread.sleep(700)
        val firstSample = drawCount.get()
        Thread.sleep(1500)
        val drift = drawCount.get() - firstSample
        observing = false

        // A settled static screen draws ~0 frames; the old loop produced one per frame.
        assertTrue("静态 UI 在 1.5s 内仍重绘 $drift 次（first=$firstSample），捕获循环未收敛", drift <= 3)
    }

    @Test
    fun sourceContentChangeStillRefreshesTheCapture() {
        startHost()
        compose.waitUntil(10_000) { true }

        startDrawObserver()
        val before = drawCount.get()
        sourceText.value = "内容已变化"
        var changed = false
        compose.runOnIdle { changed = true }
        compose.waitUntil(10_000) { changed }
        Thread.sleep(400)
        val after = drawCount.get()
        observing = false

        assertTrue("源内容变化后没有任何新绘制（before=$before, after=$after），玻璃将显示旧内容", after > before)
    }

    private fun startHost() {
        compose.setContent {
            TestTheme {
                GlassCaptureHost(
                    modifier = Modifier.fillMaxSize(),
                    sourceContent = {
                        Column {
                            Text(sourceText.value, fontSize = 18.sp)
                        }
                    },
                    overlayContent = {
                        Box(Modifier.fillMaxSize()) {
                            GlassSurface(surfaceId = "settle-probe") {
                                Box(Modifier.height(48.dp))
                            }
                        }
                    },
                )
            }
        }
    }

    private fun startDrawObserver() {
        observing = true
        compose.activity.window.decorView.viewTreeObserver.addOnDrawListener {
            if (observing) drawCount.incrementAndGet()
        }
    }

    @Composable
    private fun TestTheme(content: @Composable () -> Unit) {
        val colorScheme = lightColorScheme()
        val navController = rememberNavController()
        MaterialTheme(
            colorScheme = colorScheme,
            content = {
                CompositionLocalProvider(
                    LocalExtendedColors provides extendedColorSchemeFor(colorScheme, isDark = false),
                    LocalMainNavController provides navController,
                ) {
                    content()
                }
            },
        )
    }
}
