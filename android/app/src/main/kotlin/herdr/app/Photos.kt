package herdr.app

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.media.ExifInterface
import android.net.Uri
import herdr.core.Attach
import java.io.File
import java.time.ZoneId

/**
 * Turns a picked or shared picture into what is sent: read through the content URI's stream (no storage
 * permission), turned upright, shrunk (longest side [Attach.MAX_SIDE]), re-encoded without location or any
 * other metadata, and written to one temp file in the app's cache. The caller deletes it after the upload.
 */
object Photos {
    class Ready(val file: File, val name: String)

    /** Why a picture could not be prepared. */
    class Unusable(val why: Attach.Failure) : Exception(why.notice)

    private fun dir(context: Context) = File(context.cacheDir, "attach").apply { mkdirs() }

    /** Removes whatever an earlier upload left (a killed app): called before each one and at start. */
    fun sweep(context: Context) {
        runCatching { File(context.cacheDir, "attach").listFiles()?.forEach { it.delete() } }
    }

    fun prepare(context: Context, uri: Uri, nowMs: Long = System.currentTimeMillis()): Ready {
        val resolver = context.contentResolver
        sweep(context)
        val sourceMime = runCatching { resolver.getType(uri) }.getOrNull()

        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        runCatching { resolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) } }
            .getOrElse { throw Unusable(Attach.Failure.Unreadable) }
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) throw Unusable(Attach.Failure.Unreadable)

        val orientation = runCatching {
            resolver.openInputStream(uri)?.use { ExifInterface(it).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL) }
        }.getOrNull() ?: ExifInterface.ORIENTATION_NORMAL
        val upright = Matrix()
        val swaps = when (orientation) {
            ExifInterface.ORIENTATION_ROTATE_90 -> { upright.postRotate(90f); true }
            ExifInterface.ORIENTATION_ROTATE_180 -> { upright.postRotate(180f); false }
            ExifInterface.ORIENTATION_ROTATE_270 -> { upright.postRotate(270f); true }
            ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> { upright.postScale(-1f, 1f); false }
            ExifInterface.ORIENTATION_FLIP_VERTICAL -> { upright.postScale(1f, -1f); false }
            ExifInterface.ORIENTATION_TRANSPOSE -> { upright.postRotate(90f); upright.postScale(-1f, 1f); true }
            ExifInterface.ORIENTATION_TRANSVERSE -> { upright.postRotate(270f); upright.postScale(-1f, 1f); true }
            else -> false
        }
        // Sizes are worked out as the person sees the picture, then mapped back to the stored one.
        val seenW = if (swaps) bounds.outHeight else bounds.outWidth
        val seenH = if (swaps) bounds.outWidth else bounds.outHeight
        val (tw, th) = Attach.target(seenW, seenH)
        val sample = if (swaps) Attach.sampleSize(bounds.outWidth, bounds.outHeight, th, tw) else Attach.sampleSize(bounds.outWidth, bounds.outHeight, tw, th)

        val decoded = try {
            resolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, BitmapFactory.Options().apply { inSampleSize = sample }) }
        } catch (_: OutOfMemoryError) { null } catch (_: Exception) { null }
            ?: throw Unusable(Attach.Failure.Unreadable)

        // One pass: scale to the target, then turn upright.
        val matrix = Matrix().apply {
            postScale((if (swaps) th else tw).toFloat() / decoded.width, (if (swaps) tw else th).toFloat() / decoded.height)
            postConcat(upright)
        }
        val bitmap = try { Bitmap.createBitmap(decoded, 0, 0, decoded.width, decoded.height, matrix, true) } catch (_: OutOfMemoryError) { throw Unusable(Attach.Failure.Unreadable) }
        if (bitmap !== decoded) decoded.recycle()

        val out = dir(context)
        try {
            val png = if (Attach.wantsPng(sourceMime)) File(out, "p.png").also { f -> f.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) } } else null
            fun jpeg(quality: Int): File {
                val flat = if (bitmap.hasAlpha()) Bitmap.createBitmap(bitmap.width, bitmap.height, Bitmap.Config.ARGB_8888).also {
                    Canvas(it).apply { drawColor(Color.WHITE); drawBitmap(bitmap, 0f, 0f, null) }
                } else bitmap
                return File(out, "p.jpg").also { f -> f.outputStream().use { flat.compress(Bitmap.CompressFormat.JPEG, quality, it) }; if (flat !== bitmap) flat.recycle() }
            }
            val chosen: Pair<File, Attach.Format> = if (png != null) {
                // A small PNG is kept without a JPEG; a big one is compared with the JPEG.
                val j = if (png.length() > Attach.MAX_BYTES || png.length() > Attach.PNG_KEEP_BYTES) jpeg(Attach.JPEG_QUALITIES.first()) else null
                if (Attach.chooseFormat(png.length().toInt(), j?.length()?.toInt() ?: Int.MAX_VALUE) == Attach.Format.Png) png to Attach.Format.Png
                else (j ?: jpeg(Attach.JPEG_QUALITIES.first())) to Attach.Format.Jpeg
            } else {
                jpeg(Attach.JPEG_QUALITIES.first()) to Attach.Format.Jpeg
            }
            var file = chosen.first
            if (chosen.second == Attach.Format.Jpeg) {
                for (q in Attach.JPEG_QUALITIES.drop(1)) {
                    if (file.length() <= Attach.MAX_BYTES) break
                    file = jpeg(q)
                }
            }
            if (file.length() > Attach.MAX_BYTES) throw Unusable(Attach.Failure.TooLarge)
            if (png != null && png !== file) png.delete()
            return Ready(file, Attach.fileName(nowMs, ZoneId.systemDefault(), chosen.second))
        } finally {
            bitmap.recycle()
        }
    }
}
