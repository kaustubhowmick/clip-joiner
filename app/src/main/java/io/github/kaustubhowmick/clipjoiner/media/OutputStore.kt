package io.github.kaustubhowmick.clipjoiner.media

import android.content.ContentValues
import android.content.Context
import android.media.MediaMuxer
import android.media.MediaScannerConnection
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.os.ParcelFileDescriptor
import android.provider.MediaStore
import java.io.File
import java.io.IOException
import kotlin.coroutines.resume
import kotlinx.coroutines.suspendCancellableCoroutine

data class SavedVideo(val uri: Uri, val displayName: String, val sizeBytes: Long)

/** A video being written into Movies/Clip Joiner; [commit] publishes it, [discard] removes it. */
interface PendingOutput {
    fun openMuxer(): MediaMuxer
    suspend fun commit(): SavedVideo
    fun discard()
}

object OutputStore {
    const val FOLDER = "Clip Joiner"

    fun create(context: Context, displayName: String): PendingOutput =
        if (Build.VERSION.SDK_INT >= 29) MediaStoreOutput(context, displayName) else LegacyOutput(context, displayName)

    private class MediaStoreOutput(private val context: Context, private val name: String) : PendingOutput {
        private val resolver = context.contentResolver
        private val uri: Uri = resolver.insert(
            MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY),
            ContentValues().apply {
                put(MediaStore.Video.Media.DISPLAY_NAME, name)
                put(MediaStore.Video.Media.MIME_TYPE, "video/mp4")
                put(MediaStore.Video.Media.RELATIVE_PATH, "${Environment.DIRECTORY_MOVIES}/$FOLDER")
                put(MediaStore.Video.Media.IS_PENDING, 1)
            },
        ) ?: throw IOException("Couldn't create the output file")
        private var pfd: ParcelFileDescriptor? = null

        override fun openMuxer(): MediaMuxer {
            val fd = resolver.openFileDescriptor(uri, "rw") ?: throw IOException("Couldn't open the output file")
            pfd = fd
            return MediaMuxer(fd.fileDescriptor, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
        }

        override suspend fun commit(): SavedVideo {
            val size = pfd?.statSize ?: 0L
            pfd?.close()
            resolver.update(uri, ContentValues().apply { put(MediaStore.Video.Media.IS_PENDING, 0) }, null, null)
            return SavedVideo(uri, name, size)
        }

        override fun discard() {
            try { pfd?.close() } catch (_: IOException) {}
            resolver.delete(uri, null, null)
        }
    }

    /** Android 8–9: a plain file in the public Movies folder, then a media scan. */
    private class LegacyOutput(private val context: Context, private val name: String) : PendingOutput {
        @Suppress("DEPRECATION")
        private val file = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MOVIES), "$FOLDER/$name")

        override fun openMuxer(): MediaMuxer {
            file.parentFile?.mkdirs()
            return MediaMuxer(file.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
        }

        override suspend fun commit(): SavedVideo {
            val uri = suspendCancellableCoroutine { cont ->
                MediaScannerConnection.scanFile(context, arrayOf(file.absolutePath), arrayOf("video/mp4")) { _, uri ->
                    cont.resume(uri ?: Uri.fromFile(file))
                }
            }
            return SavedVideo(uri, name, file.length())
        }

        override fun discard() {
            file.delete()
        }
    }
}
