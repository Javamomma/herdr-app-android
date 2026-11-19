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
        fun cancelled() { if (Thread.currentThread().isInterrupted) throw InterruptedException() }

        /** The picture upright at the longest side [side]. */
        fun render(side: Int): Bitmap {
            cancelled()
            val (tw, th) = Attach.target(seenW, seenH, side)
            val sample = if (swaps) Attach.sampleSize(bounds.outWidth, bounds.outHeight, th, tw) else Attach.sampleSize(bounds.outWidth, bounds.outHeight, tw, th)
            val decoded = try {
                resolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, BitmapFactory.Options().apply { inSampleSize = sample }) }
            } catch (_: OutOfMemoryError) { throw Unusable(Attach.Failure.TooLarge) } catch (e: java.io.IOException) { throw Unusable(Attach.Failure.Unreadable) }
                ?: throw Unusable(Attach.Failure.Unreadable)
            // One pass: scale to the target, then turn upright.
            val matrix = Matrix().apply {
                postScale((if (swaps) th else tw).toFloat() / decoded.width, (if (swaps) tw else th).toFloat() / decoded.height)
                postConcat(upright)
            }
            val bitmap = try { Bitmap.createBitmap(decoded, 0, 0, decoded.width, decoded.height, matrix, true) } catch (_: OutOfMemoryError) { throw Unusable(Attach.Failure.TooLarge) }
            if (bitmap !== decoded) decoded.recycle()
            return bitmap
        }

        val out = dir(context)
        var bitmap: Bitmap? = null
        try {
            fun write(file: File, b: Bitmap, format: Bitmap.CompressFormat, quality: Int): File {
                cancelled()
                try {
                    val flat = if (format == Bitmap.CompressFormat.JPEG && b.hasAlpha()) Bitmap.createBitmap(b.width, b.height, Bitmap.Config.ARGB_8888).also {
                        Canvas(it).apply { drawColor(Color.WHITE); drawBitmap(b, 0f, 0f, null) }
                    } else b
                    try { file.outputStream().use { flat.compress(format, quality, it) } } finally { if (flat !== b) flat.recycle() }
                } catch (_: java.io.IOException) {
                    throw Unusable(Attach.Failure.Local)
                } catch (_: OutOfMemoryError) {
                    throw Unusable(Attach.Failure.TooLarge)
                }
                return file
            }
            var current = render(Attach.MAX_SIDE).also { bitmap = it }
            val first = Attach.LADDER.first()
            // A PNG source stays PNG when it is small, or not much bigger than the JPEG.
            if (Attach.wantsPng(sourceMime)) {
                val png = write(File(out, "p.png"), current, Bitmap.CompressFormat.PNG, 100)
                val keep = png.length() <= Attach.PNG_KEEP_BYTES ||
                    Attach.chooseFormat(png.length().toInt(), write(File(out, "p.jpg"), current, Bitmap.CompressFormat.JPEG, first.quality).length().toInt()) == Attach.Format.Png
                if (keep && png.length() <= Attach.MAX_BYTES) return Ready(png, Attach.fileName(nowMs, ZoneId.systemDefault(), Attach.Format.Png))
                png.delete()
            }
            var side = Attach.MAX_SIDE
            val jpeg = Attach.fit { step ->
                if (step.side != side) { current.recycle(); current = render(step.side).also { bitmap = it }; side = step.side }
                val f = write(File(out, "p.jpg"), current, Bitmap.CompressFormat.JPEG, step.quality)
                f to f.length()
            } ?: throw Unusable(Attach.Failure.TooLarge)
            return Ready(jpeg, Attach.fileName(nowMs, ZoneId.systemDefault(), Attach.Format.Jpeg))
        } finally {
            bitmap?.recycle()
        }
    }
}
