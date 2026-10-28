// The marker images OsmAnd draws for our points. Its AidlMapLayer, when the
// layer has imagePoints on, loads each point's POINT_IMAGE_URI_PARAM through
// the content resolver, circle-crops it and draws it on the map (40 dp at
// close zooms, 20 dp further out, a plain coloured circle beyond that); a
// point without an image gets OsmAnd's generic user placeholder. So each
// entity's icon is rendered here as a PNG, the glyph in white on a square of
// the entity's colour (the crop makes it a disc), cached by icon and colour
// and served from the cache directory by the app's FileProvider with a read
// grant to the OsmAnd package.
package io.github.lopezec007.tracker.osmand

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.Paint
import android.net.Uri
import androidx.compose.ui.graphics.asAndroidPath
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.PathParser
import androidx.compose.ui.graphics.vector.VectorGroup
import androidx.compose.ui.graphics.vector.VectorPath
import androidx.core.content.FileProvider
import io.github.lopezec007.tracker.ui.common.IconGlyphs
import java.io.File
import java.util.Locale

class PointIcons(private val context: Context) {
    private val dir = File(context.cacheDir, CACHE_DIR)

    /**
     * The content URI of the image for [icon] (an OsmAnd icon name from
     * EntityAppearance, or "" for the default glyph) on [color] (ARGB),
     * rendered on first use, readable by [osmandPackage].
     */
    fun uriFor(icon: String, color: Int, osmandPackage: String): Uri {
        val file = File(dir, fileName(icon, color))
        if (!file.exists()) {
            dir.mkdirs()
            val tmp = File(dir, file.name + ".tmp")
            tmp.outputStream().use { render(IconGlyphs.glyph(icon), color, SIZE_PX).compress(Bitmap.CompressFormat.PNG, 100, it) }
            if (!tmp.renameTo(file)) throw IllegalStateException("cannot write $file")
        }
        val uri = FileProvider.getUriForFile(context, context.packageName + AUTHORITY_SUFFIX, file)
        // A grant lasts until reboot; repeating it on every push is cheap.
        context.grantUriPermission(osmandPackage, uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
        return uri
    }

    companion object {
        const val CACHE_DIR = "point-icons"
        const val AUTHORITY_SUFFIX = ".files"

        /** Rendered size; OsmAnd scales to 40 dp, so this stays crisp on dense screens. */
        const val SIZE_PX = 160

        /** The glyph's share of the image; the rest is the coloured disc around it. */
        const val GLYPH_FRACTION = 0.6f

        /** The cache file for an icon and colour; a new colour or icon is a new file, so OsmAnd's own per-URI cache never goes stale. */
        fun fileName(icon: String, color: Int): String =
            (icon.ifEmpty { "default" }) + "-" + String.format(Locale.ROOT, "%08x", color) + ".png"

        /** [vector] in white, centred on a [size]-pixel square filled with [background]. */
        fun render(vector: ImageVector, background: Int, size: Int): Bitmap {
            val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(bitmap)
            canvas.drawColor(background)
            val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFFFFFFFF.toInt(); style = Paint.Style.FILL }
            val glyphPx = size * GLYPH_FRACTION
            val scale = glyphPx / maxOf(vector.viewportWidth, vector.viewportHeight)
            val matrix = Matrix().apply {
                setScale(scale, scale)
                postTranslate((size - vector.viewportWidth * scale) / 2f, (size - vector.viewportHeight * scale) / 2f)
            }
            drawGroup(vector.root, matrix, canvas, paint)
            return bitmap
        }

        private fun drawGroup(group: VectorGroup, matrix: Matrix, canvas: Canvas, paint: Paint) {
            val m = Matrix(matrix)
            // Material icons carry no group transforms, but honour them if present.
            if (group.scaleX != 1f || group.scaleY != 1f || group.translationX != 0f || group.translationY != 0f || group.rotation != 0f) {
                val local = Matrix().apply {
                    postTranslate(-group.pivotX, -group.pivotY)
                    postScale(group.scaleX, group.scaleY)
                    postRotate(group.rotation)
                    postTranslate(group.pivotX + group.translationX, group.pivotY + group.translationY)
                }
                m.preConcat(local)
            }
            for (node in group) {
                when (node) {
                    is VectorGroup -> drawGroup(node, m, canvas, paint)
                    is VectorPath -> {
                        val path = PathParser().addPathNodes(node.pathData).toPath().asAndroidPath()
                        path.transform(m)
                        canvas.drawPath(path, paint)
                    }
                }
            }
        }
    }
}
