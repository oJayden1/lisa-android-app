package com.cosmicoppai.lisa.download

import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMuxer
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.nio.ByteBuffer
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

object Mp4Remuxer {

    fun decryptAes128(encrypted: ByteArray, key: ByteArray, iv: ByteArray): ByteArray {
        val cipher = Cipher.getInstance("AES/CBC/PKCS5Padding")
        val keySpec = SecretKeySpec(key, "AES")
        val ivSpec = IvParameterSpec(iv)
        cipher.init(Cipher.DECRYPT_MODE, keySpec, ivSpec)
        return cipher.doFinal(encrypted)
    }

    /**
     * Concatenates decrypted segments into a temporary transport stream file and remuxes
     * video + audio tracks losslessly into a standalone .mp4 using Android's MediaExtractor & MediaMuxer.
     */
    fun remuxSegmentsToMp4(
        segmentFiles: List<File>,
        tempCombinedFile: File,
        outputMp4File: File
    ): Long {
        outputMp4File.parentFile?.mkdirs()
        if (outputMp4File.exists()) outputMp4File.delete()

        FileOutputStream(tempCombinedFile).use { out ->
            val buf = ByteArray(64 * 1024)
            segmentFiles.forEach { seg ->
                if (seg.exists()) {
                    FileInputStream(seg).use { input ->
                        while (true) {
                            val read = input.read(buf)
                            if (read <= 0) break
                            out.write(buf, 0, read)
                        }
                    }
                }
            }
        }

        val extractor = MediaExtractor()
        var muxer: MediaMuxer? = null
        try {
            extractor.setDataSource(tempCombinedFile.absolutePath)
            val trackCount = extractor.trackCount
            if (trackCount <= 0) {
                throw IllegalStateException("No media tracks detected in combined stream")
            }

            muxer = MediaMuxer(outputMp4File.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
            val trackIndexMap = mutableMapOf<Int, Int>()

            for (i in 0 until trackCount) {
                val format = extractor.getTrackFormat(i)
                val mime = format.getString(MediaFormat.KEY_MIME).orEmpty()
                if (mime.startsWith("video/") || mime.startsWith("audio/")) {
                    val muxerTrack = muxer.addTrack(format)
                    trackIndexMap[i] = muxerTrack
                    extractor.selectTrack(i)
                }
            }

            if (trackIndexMap.isEmpty()) {
                throw IllegalStateException("No audio/video tracks supported for MP4 muxing")
            }

            muxer.start()
            val bufferSize = 2 * 1024 * 1024
            val dstBuf = ByteBuffer.allocateDirect(bufferSize)
            val bufferInfo = MediaCodec.BufferInfo()

            while (true) {
                dstBuf.clear()
                val sampleSize = extractor.readSampleData(dstBuf, 0)
                if (sampleSize < 0) break

                val extractorTrack = extractor.sampleTrackIndex
                val muxerTrack = trackIndexMap[extractorTrack]
                if (muxerTrack != null) {
                    bufferInfo.offset = 0
                    bufferInfo.size = sampleSize
                    bufferInfo.presentationTimeUs = extractor.sampleTime.coerceAtLeast(0L)
                    val sampleFlags = extractor.sampleFlags
                    var codecFlags = 0
                    if ((sampleFlags and MediaExtractor.SAMPLE_FLAG_SYNC) != 0) {
                        codecFlags = codecFlags or MediaCodec.BUFFER_FLAG_KEY_FRAME
                    }
                    if ((sampleFlags and MediaExtractor.SAMPLE_FLAG_PARTIAL_FRAME) != 0) {
                        codecFlags = codecFlags or MediaCodec.BUFFER_FLAG_PARTIAL_FRAME
                    }
                    bufferInfo.flags = codecFlags
                    muxer.writeSampleData(muxerTrack, dstBuf, bufferInfo)
                }
                extractor.advance()
            }
        } finally {
            runCatching { muxer?.stop() }
            runCatching { muxer?.release() }
            runCatching { extractor.release() }
        }

        return outputMp4File.length()
    }
}
