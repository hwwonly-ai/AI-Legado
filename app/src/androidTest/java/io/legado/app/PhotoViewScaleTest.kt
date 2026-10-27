package io.legado.app

import android.content.Context
import android.graphics.Bitmap
import android.view.ScaleGestureDetector
import android.view.View
import android.widget.FrameLayout
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.legado.app.ui.widget.image.PhotoView
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.math.abs

@RunWith(AndroidJUnit4::class)
class PhotoViewScaleTest {

    @Test
    fun repeatedPinchOperationsContinueZoomingAndKeepScale() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = ApplicationProvider.getApplicationContext<Context>()
        lateinit var photoView: PhotoView

        instrumentation.runOnMainSync {
            photoView = PhotoView(context).apply {
                setMaxScale(5f)
                setImageBitmap(Bitmap.createBitmap(800, 800, Bitmap.Config.ARGB_8888))
            }
            FrameLayout(context).apply {
                id = android.R.id.content
                addView(photoView, FrameLayout.LayoutParams(1000, 1000))
                measure(
                    View.MeasureSpec.makeMeasureSpec(1000, View.MeasureSpec.EXACTLY),
                    View.MeasureSpec.makeMeasureSpec(1000, View.MeasureSpec.EXACTLY)
                )
                layout(0, 0, 1000, 1000)
            }

            val listenerField = PhotoView::class.java.getDeclaredField("mScaleListener").apply {
                isAccessible = true
            }
            val listener = listenerField.get(photoView)
            val begin = listener.javaClass.getDeclaredMethod(
                "onScaleBegin",
                ScaleGestureDetector::class.java
            ).apply { isAccessible = true }
            val scale = listener.javaClass.getDeclaredMethod(
                "onScale",
                ScaleGestureDetector::class.java
            ).apply { isAccessible = true }
            val detector = FakeScaleDetector(context)

            val initial = photoView.getInfo().mScale
            begin.invoke(listener, detector)
            detector.factor = 2f
            scale.invoke(listener, detector)
            val firstMove = photoView.getInfo().mScale
            detector.factor = 1.2f
            scale.invoke(listener, detector)
            val firstEnd = photoView.getInfo().mScale

            begin.invoke(listener, detector)
            detector.factor = 1.1f
            scale.invoke(listener, detector)
            val secondEnd = photoView.getInfo().mScale

            begin.invoke(listener, detector)
            detector.factor = 1.1f
            scale.invoke(listener, detector)
            val thirdEnd = photoView.getInfo().mScale

            begin.invoke(listener, detector)
            detector.factor = 10f
            scale.invoke(listener, detector)
            val cappedScale = photoView.getInfo().mScale

            assertTrue("single gesture should update continuously", firstMove > initial)
            assertTrue("single gesture should keep increasing", firstEnd > firstMove)
            assertTrue("second gesture should continue from first scale", secondEnd > firstEnd)
            assertTrue("third gesture should continue from second scale", thirdEnd > secondEnd)
            assertEquals("map image scale should be capped", 5f, cappedScale, 0.001f)
            assertEquals(
                "completed gesture should retain its scale",
                cappedScale,
                photoView.getInfo().mScale,
                0.001f
            )
            assertEquals(5f, photoView.getMaxScale(), 0f)
        }
    }

    private class FakeScaleDetector(context: Context) : ScaleGestureDetector(
        context,
        object : OnScaleGestureListener {
            override fun onScale(detector: ScaleGestureDetector): Boolean = false
            override fun onScaleBegin(detector: ScaleGestureDetector): Boolean = false
            override fun onScaleEnd(detector: ScaleGestureDetector) = Unit
        }
    ) {
        var factor = 1f

        override fun getScaleFactor(): Float = factor
        override fun getFocusX(): Float = 500f
        override fun getFocusY(): Float = 500f
    }
}
