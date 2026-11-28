package dev.multisync.app

import android.view.View
import org.junit.Assert.assertNotNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode
import org.robolectric.Shadows.shadowOf
import android.os.Looper

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
@LooperMode(LooperMode.Mode.PAUSED)
class MainActivityTest {
    @Test fun activityCreatesAndLaysOutMiuixInterfaceWithoutConnectionOrRoot() {
        val controller = Robolectric.buildActivity(MainActivity::class.java).setup()
        try {
            val content = controller.get().findViewById<View>(android.R.id.content)
            content.measure(View.MeasureSpec.makeMeasureSpec(1080, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(1920, View.MeasureSpec.EXACTLY))
            content.layout(0, 0, 1080, 1920)
            shadowOf(Looper.getMainLooper()).idle()
            assertNotNull(content)
        } finally { controller.pause().stop().destroy() }
    }
}
