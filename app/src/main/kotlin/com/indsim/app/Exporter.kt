package com.indsim.app

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.pdf.PdfDocument
import androidx.compose.ui.graphics.Canvas
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.drawscope.CanvasDrawScope
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.geometry.Size
import androidx.core.content.FileProvider
import com.indsim.app.render.RenderState
import com.indsim.app.render.drawEditor
import com.indsim.canvas.Camera
import com.indsim.canvas.Grid
import com.indsim.editor.Document
import com.indsim.editor.Geo
import com.indsim.editor.ProjectStore
import java.io.File
import java.io.FileOutputStream
import kotlin.math.max
import kotlin.math.min

/** خروجی نقشه به صورت تصویر PNG و PDF؛ فایل در کش ساخته و با FileProvider به اشتراک گذاشته می‌شود. */
object Exporter {
    private const val MARGIN = 3

    fun render(doc: Document, realistic: Boolean, maxSide: Int = 3200): Bitmap? {
        val b = Geo.contentBounds(doc) ?: return null
        val wGrid = b.maxX - b.minX + 2 * MARGIN
        val hGrid = b.maxY - b.minY + 2 * MARGIN
        val wWorld = wGrid * Grid.BASE_STEP.toDouble()
        val hWorld = hGrid * Grid.BASE_STEP.toDouble()
        val scale = min(2.0, maxSide / max(wWorld, hWorld))
        val pw = max(64, (wWorld * scale).toInt())
        val ph = max(64, (hWorld * scale).toInt())
        val cam = Camera(
            scale,
            -((b.minX - MARGIN) * Grid.BASE_STEP.toDouble()) * scale,
            -((b.minY - MARGIN) * Grid.BASE_STEP.toDouble()) * scale,
        )
        val image = ImageBitmap(pw, ph)
        val canvas = Canvas(image)
        val st = RenderState(doc = doc, camera = cam, real = realistic, showGrid = false, background = Color.White)
        CanvasDrawScope().draw(Density(1f), LayoutDirection.Ltr, canvas, Size(pw.toFloat(), ph.toFloat())) {
            drawEditor(st)
        }
        return image.asAndroidBitmap()
    }

    private fun exportDir(ctx: Context) = File(ctx.cacheDir, "exports").also { it.mkdirs() }

    fun savePng(ctx: Context, bmp: Bitmap, name: String): File {
        val f = File(exportDir(ctx), ProjectStore.safeName(name) + ".png")
        FileOutputStream(f).use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
        return f
    }

    fun savePdf(ctx: Context, bmp: Bitmap, name: String): File {
        val f = File(exportDir(ctx), ProjectStore.safeName(name) + ".pdf")
        val pdf = PdfDocument()
        val page = pdf.startPage(PdfDocument.PageInfo.Builder(bmp.width, bmp.height, 1).create())
        page.canvas.drawBitmap(bmp, 0f, 0f, null)
        pdf.finishPage(page)
        FileOutputStream(f).use { pdf.writeTo(it) }
        pdf.close()
        return f
    }

    fun share(ctx: Context, file: File, mime: String) {
        val uri = FileProvider.getUriForFile(ctx, "${ctx.packageName}.files", file)
        val send = Intent(Intent.ACTION_SEND).apply {
            type = mime
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        val chooser = Intent.createChooser(send, file.name).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        ctx.startActivity(chooser)
    }

}
