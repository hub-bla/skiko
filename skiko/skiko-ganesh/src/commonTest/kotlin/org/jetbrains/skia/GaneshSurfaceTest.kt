package org.jetbrains.skia

import org.jetbrains.skia.gpu.ganesh.makeFromImage
import org.jetbrains.skia.gpu.ganesh.makeRenderTarget
import org.jetbrains.skia.gpu.ganesh.recordingContext
import org.jetbrains.skia.impl.use
import org.jetbrains.skiko.Arch
import org.jetbrains.skiko.KotlinBackend
import org.jetbrains.skiko.OS
import org.jetbrains.skiko.hostArch
import org.jetbrains.skiko.hostOs
import org.jetbrains.skiko.kotlinBackend
import org.jetbrains.skiko.tests.TestGlContext
import org.jetbrains.skiko.tests.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFails
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class GaneshSurfaceTest {
    @Test
    fun recordingContextReturnsNullForRasterSurfaceAndCanvas() = runTest {
        Surface.makeRasterN32Premul(8, 8).use { surface ->
            assertNull(surface.recordingContext)
            assertNull(surface.canvas.recordingContext)
        }
    }

    @Test
    fun recordingContextIsBorrowedForRenderTargetSurfaceAndCanvas() = runTest {
        if (!TestGlContext.isAvailable()) return@runTest
        if (hostOs == OS.Linux && kotlinBackend == KotlinBackend.Native && hostArch == Arch.Arm64) return@runTest

        TestGlContext.run {
            DirectContext.makeGL().useContext { context ->
                val surface = Surface.makeRenderTarget(
                    context,
                    budgeted = false,
                    ImageInfo.makeN32Premul(16, 16),
                )
                assertNotNull(surface.recordingContext).also { assertFails { it.close() } }
                assertNotNull(surface.canvas.recordingContext).also { assertFails { it.close() } }
            }
        }
    }

    @Test
    fun renderTargetCanBeReadWithContext() = runTest {
        if (!TestGlContext.isAvailable()) return@runTest
        if (hostOs == OS.Linux && kotlinBackend == KotlinBackend.Native && hostArch == Arch.Arm64) return@runTest

        val pixels = TestGlContext.run {
            DirectContext.makeGL().useContext { context ->
                val surface = Surface.makeRenderTarget(
                    context,
                    budgeted = false,
                    ImageInfo.makeN32Premul(16, 16),
                )
                surface.canvas.drawRect(
                    Rect(4f, 4f, 12f, 12f),
                    Paint().apply { color = Color.RED },
                )
                Bitmap.makeFromImage(surface.makeImageSnapshot(), context)
            }
        }
        assertEquals(Color.RED, pixels.getColor(8, 8))
    }
}