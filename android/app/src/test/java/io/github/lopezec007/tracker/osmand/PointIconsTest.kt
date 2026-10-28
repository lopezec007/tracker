package io.github.lopezec007.tracker.osmand

import android.graphics.Color
import androidx.test.core.app.ApplicationProvider
import io.github.lopezec007.tracker.ui.common.IconGlyphs
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
// Robolectric's legacy graphics are no-ops; the native mode really draws.
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class PointIconsTest {
    @Test
    fun fileNameChangesWithIconAndColour() {
        assertEquals("default-ff1e88e5.png", PointIcons.fileName("", 0xFF1E88E5.toInt()))
        assertEquals("ic_action_car_dark-ff1e88e5.png", PointIcons.fileName("ic_action_car_dark", 0xFF1E88E5.toInt()))
        assertNotEquals(PointIcons.fileName("ic_action_car_dark", Color.RED), PointIcons.fileName("ic_action_car_dark", Color.BLUE))
    }

    @Test
    fun rendersTheGlyphOnTheColour() {
        val bg = 0xFF1E88E5.toInt()
        val bmp = PointIcons.render(IconGlyphs.glyph("ic_action_home_dark"), bg, 64)
        assertEquals(64, bmp.width)
        assertEquals(64, bmp.height)
        // A corner is pure background (the crop to a disc happens in OsmAnd).
        assertEquals(bg, bmp.getPixel(1, 1))
        // Somewhere inside, the white glyph was painted.
        var white = 0
        for (x in 0 until 64) for (y in 0 until 64) if (bmp.getPixel(x, y) == Color.WHITE) white++
        assertTrue("expected white glyph pixels, got $white", white > 50)
    }

    @Test
    fun uriIsServedFromTheCacheAndRenderedOnce() {
        val ctx = ApplicationProvider.getApplicationContext<android.content.Context>()
        val icons = PointIcons(ctx)
        val uri = icons.uriFor("ic_action_car_dark", Color.RED, "net.osmand.plus")
        assertEquals("content", uri.scheme)
        assertEquals(ctx.packageName + ".files", uri.authority)
        assertTrue(uri.toString(), uri.toString().endsWith("/point-icons/ic_action_car_dark-ffff0000.png"))
        val file = File(File(ctx.cacheDir, PointIcons.CACHE_DIR), "ic_action_car_dark-ffff0000.png")
        assertTrue(file.exists())
        val stamp = file.lastModified()
        icons.uriFor("ic_action_car_dark", Color.RED, "net.osmand.plus")
        assertEquals(stamp, file.lastModified())
    }
}
