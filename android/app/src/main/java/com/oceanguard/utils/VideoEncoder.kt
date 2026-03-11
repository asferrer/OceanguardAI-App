package com.oceanguard.ai.utils

import android.graphics.Bitmap
import android.graphics.Matrix
import android.graphics.Rect
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.MediaMuxer
import android.util.Log
import android.view.Surface
import java.io.File

private const val TAG = "VideoEncoder"
private const val MIME_TYPE = "video/avc"
private const val DEQUEUE_TIMEOUT_US = 10_000L

class VideoEncoder(
    private val outputFile: File,
    private val width: Int,
    private val height: Int,
    private val frameRate: Int,
    private val bitRate: Int = width * height * 4
) {
    private var encoder: MediaCodec? = null
    private var muxer: MediaMuxer? = null
    private var inputSurface: Surface? = null
    private var trackIndex: Int = -1
    private var muxerStarted: Boolean = false

    fun start() {
        val format = MediaFormat.createVideoFormat(MIME_TYPE, width, height).apply {
            setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
            setInteger(MediaFormat.KEY_BIT_RATE, bitRate)
            setInteger(MediaFormat.KEY_FRAME_RATE, frameRate)
            setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1)
        }

        encoder = MediaCodec.createEncoderByType(MIME_TYPE).also { codec ->
            codec.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            inputSurface = codec.createInputSurface()
            codec.start()
        }

        muxer = MediaMuxer(outputFile.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
        Log.d(TAG, "Encoder started: ${width}x${height} @ ${frameRate}fps, bitrate=$bitRate")
    }

    fun encodeFrame(bitmap: Bitmap, presentationTimeUs: Long) {
        val surface = inputSurface ?: error("Encoder not started")
        drawBitmapToSurface(bitmap, surface)
        drainEncoder(endOfStream = false, presentationTimeUs = presentationTimeUs)
    }

    fun finish() {
        try {
            encoder?.signalEndOfInputStream()
            drainEncoder(endOfStream = true, presentationTimeUs = 0L)
        } finally {
            release()
        }
    }

    fun release() {
        runCatching { encoder?.stop() }
        runCatching { encoder?.release() }
        runCatching { inputSurface?.release() }
        runCatching { if (muxerStarted) muxer?.stop() }
        runCatching { muxer?.release() }

        encoder = null
        inputSurface = null
        muxer = null
        muxerStarted = false
        trackIndex = -1
        Log.d(TAG, "Encoder released")
    }

    private fun drawBitmapToSurface(bitmap: Bitmap, surface: Surface) {
        val canvas = surface.lockCanvas(null)
        try {
            val destRect = Rect(0, 0, width, height)
            val matrix = Matrix().apply {
                val scaleX = width.toFloat() / bitmap.width
                val scaleY = height.toFloat() / bitmap.height
                setScale(scaleX, scaleY)
            }
            canvas.drawBitmap(bitmap, matrix, null)
        } finally {
            surface.unlockCanvasAndPost(canvas)
        }
    }

    private fun drainEncoder(endOfStream: Boolean, presentationTimeUs: Long) {
        val codec = encoder ?: return
        val muxerInstance = muxer ?: return
        val bufferInfo = MediaCodec.BufferInfo()

        var keepDraining = true
        while (keepDraining) {
            val outputBufferId = codec.dequeueOutputBuffer(bufferInfo, DEQUEUE_TIMEOUT_US)
            when {
                outputBufferId == MediaCodec.INFO_TRY_AGAIN_LATER -> {
                    if (!endOfStream) keepDraining = false
                }
                outputBufferId == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                    if (muxerStarted) error("Format changed after muxer started")
                    trackIndex = muxerInstance.addTrack(codec.outputFormat)
                    muxerInstance.start()
                    muxerStarted = true
                    Log.d(TAG, "Muxer started, track index=$trackIndex")
                }
                outputBufferId == MediaCodec.INFO_OUTPUT_BUFFERS_CHANGED -> {
                    // Deprecated in API 21+, safe to ignore
                }
                outputBufferId >= 0 -> {
                    keepDraining = processOutputBuffer(
                        codec, muxerInstance, outputBufferId, bufferInfo,
                        presentationTimeUs, endOfStream
                    )
                }
            }
        }
    }

    private fun processOutputBuffer(
        codec: MediaCodec,
        muxer: MediaMuxer,
        bufferId: Int,
        bufferInfo: MediaCodec.BufferInfo,
        presentationTimeUs: Long,
        endOfStream: Boolean
    ): Boolean {
        if (bufferInfo.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0) {
            bufferInfo.size = 0
        }

        if (bufferInfo.size > 0 && muxerStarted) {
            val encodedData = codec.getOutputBuffer(bufferId)
                ?: error("Output buffer $bufferId is null")
            encodedData.position(bufferInfo.offset)
            encodedData.limit(bufferInfo.offset + bufferInfo.size)

            if (bufferInfo.presentationTimeUs == 0L) {
                bufferInfo.presentationTimeUs = presentationTimeUs
            }

            muxer.writeSampleData(trackIndex, encodedData, bufferInfo)
        }

        codec.releaseOutputBuffer(bufferId, false)

        val isEos = bufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0
        if (isEos && endOfStream) return false

        return true
    }
}
