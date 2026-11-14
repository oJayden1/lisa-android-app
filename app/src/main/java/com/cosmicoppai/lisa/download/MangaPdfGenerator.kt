package com.cosmicoppai.lisa.download

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.pdf.PdfDocument
import java.io.File
import java.io.FileOutputStream

object MangaPdfGenerator {

    fun convertImagesToPdf(imageFiles: List<File>, outputPdf: File): Long {
        outputPdf.parentFile?.mkdirs()
        if (outputPdf.exists()) outputPdf.delete()

        val document = PdfDocument()
        try {
            imageFiles.forEachIndexed { index, imgFile ->
                val bitmap = decodeSampledBitmap(imgFile, maxDimension = 2200) ?: return@forEachIndexed
                try {
                    val pageInfo = PdfDocument.PageInfo.Builder(
                        bitmap.width,
                        bitmap.height,
                        index + 1
                    ).create()
                    val page = document.startPage(pageInfo)
                    page.canvas.drawBitmap(bitmap, 0f, 0f, null)
                    document.finishPage(page)
                } finally {
                    bitmap.recycle()
                }
            }
            FileOutputStream(outputPdf).use { out ->
                document.writeTo(out)
            }
        } finally {
            document.close()
        }
        return outputPdf.length()
    }

    private fun decodeSampledBitmap(file: File, maxDimension: Int): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.absolutePath, bounds)
        val width = bounds.outWidth
        val height = bounds.outHeight
        if (width <= 0 || height <= 0) return null

        var inSampleSize = 1
        while (width / inSampleSize > maxDimension || height / inSampleSize > maxDimension) {
            inSampleSize *= 2
        }
        val opts = BitmapFactory.Options().apply { this.inSampleSize = inSampleSize }
        return BitmapFactory.decodeFile(file.absolutePath, opts)
    }
}
