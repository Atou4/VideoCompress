package com.example.video_compress

import android.content.Context
import android.net.Uri
import android.util.Log
import com.otaliastudios.transcoder.Transcoder
import com.otaliastudios.transcoder.TranscoderListener
import com.otaliastudios.transcoder.source.TrimDataSource
import com.otaliastudios.transcoder.source.UriDataSource
import com.otaliastudios.transcoder.strategy.DefaultAudioStrategy
import com.otaliastudios.transcoder.strategy.DefaultVideoStrategy
import com.otaliastudios.transcoder.strategy.RemoveTrackStrategy
import com.otaliastudios.transcoder.strategy.TrackStrategy
import io.flutter.embedding.engine.plugins.FlutterPlugin
import io.flutter.plugin.common.BinaryMessenger
import com.otaliastudios.transcoder.internal.utils.Logger
import io.flutter.plugin.common.MethodCall
import io.flutter.plugin.common.MethodChannel
import io.flutter.plugin.common.MethodChannel.MethodCallHandler
import java.io.File
import java.text.SimpleDateFormat
import java.util.*
import java.util.concurrent.Future
import java.util.concurrent.Executors
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit

/**
 * VideoCompressPlugin
 */
class VideoCompressPlugin : MethodCallHandler, FlutterPlugin {

    private var _context: Context? = null
    private var _channel: MethodChannel? = null
    private val TAG = "VideoCompressPlugin"
    private val LOG = Logger(TAG)
    private var transcodeFuture: Future<Void>? = null
    var channelName = "video_compress"

    // Optimized thread pool for faster task execution
    private val threadPool = ThreadPoolExecutor(
        4, // Increased core pool size for concurrent tasks
        8, // Increased max pool size for bursty workloads
        30L, // Reduced keep-alive time to free resources faster
        TimeUnit.SECONDS,
        LinkedBlockingQueue<Runnable>(),
        Executors.defaultThreadFactory()
    )

    override fun onMethodCall(call: MethodCall, result: MethodChannel.Result) {
        val context = _context
        val channel = _channel

        if (context == null || channel == null) {
            Log.w(TAG, "Calling VideoCompress plugin before initialization")
            return
        }

        when (call.method) {
            "getByteThumbnail" -> {
                val path = call.argument<String>("path")
                val quality = call.argument<Int>("quality")!!
                val position = call.argument<Int>("position")!!
                ThumbnailUtility(channelName).getByteThumbnail(path!!, quality, position.toLong(), result)
            }
            "getFileThumbnail" -> {
                val path = call.argument<String>("path")
                val quality = call.argument<Int>("quality")!!
                val position = call.argument<Int>("position")!!
                ThumbnailUtility("video_compress").getFileThumbnail(context, path!!, quality, position.toLong(), result)
            }
            "getMediaInfo" -> {
                val path = call.argument<String>("path")
                result.success(Utility(channelName).getMediaInfoJson(context, path!!).toString())
            }
            "deleteAllCache" -> {
                result.success(Utility(channelName).deleteAllCache(context, result))
            }
            "setLogLevel" -> {
                val logLevel = call.argument<Int>("logLevel")!!
                Logger.setLogLevel(logLevel)
                result.success(true)
            }
            "cancelCompression" -> {
                transcodeFuture?.cancel(true)
                result.success(false)
            }
            "compressVideo" -> {
                val path = call.argument<String>("path")!!
                val quality = call.argument<Int>("quality")!!
                val deleteOrigin = call.argument<Boolean>("deleteOrigin")!!
                val startTime = call.argument<Int>("startTime")
                val duration = call.argument<Int>("duration")
                val includeAudio = call.argument<Boolean>("includeAudio") ?: true
                val frameRate = 20 // Fixed at 20fps for faster compression

                // Use cache directory for faster I/O
                val tempDir: String = context.cacheDir.absolutePath
                val out = SimpleDateFormat("yyyyMMdd_HHmmss").format(Date())
                val destPath: String = "$tempDir${File.separator}VID_$out${path.hashCode()}.mp4"

                val videoTrackStrategy: TrackStrategy = getOptimizedVideoStrategy(quality)
                val audioTrackStrategy: TrackStrategy = getOptimizedAudioStrategy(includeAudio)

                val dataSource = if (startTime != null || duration != null) {
                    val source = UriDataSource(context, Uri.parse(path))
                    TrimDataSource(source, (1000 * 1000 * (startTime ?: 0)).toLong(), (1000 * 1000 * (duration ?: 0)).toLong())
                } else {
                    UriDataSource(context, Uri.parse(path))
                }

                // Maximize transcoder speed
                Transcoder.setFastestSpeed(true)

                transcodeFuture = Transcoder.into(destPath)
                    .addDataSource(dataSource)
                    .setAudioTrackStrategy(audioTrackStrategy)
                    .setVideoTrackStrategy(videoTrackStrategy)
                    .setExecutor(threadPool)
                    .setListener(object : TranscoderListener {
                        override fun onTranscodeProgress(progress: Double) {
                            threadPool.execute {
                                channel.invokeMethod("updateProgress", progress * 100.00)
                            }
                        }
                        override fun onTranscodeCompleted(successCode: Int) {
                            threadPool.execute {
                                channel.invokeMethod("updateProgress", 100.00)
                                val json = Utility(channelName).getMediaInfoJson(context, destPath)
                                json.put("isCancel", false)
                                result.success(json.toString())
                                if (deleteOrigin) {
                                    File(path).delete()
                                }
                            }
                        }
                        override fun onTranscodeCanceled() {
                            result.success(null)
                        }
                        override fun onTranscodeFailed(exception: Throwable) {
                            Log.e(TAG, "Compression failed", exception)
                            result.success(null)
                        }
                    }).transcode()
            }
            else -> {
                result.notImplemented()
            }
        }
    }

    /**
     * Get optimized video strategy for faster compression
     */
    private fun getOptimizedVideoStrategy(quality: Int): TrackStrategy {
        return when (quality) {
            0 -> { // High quality
                DefaultVideoStrategy.Builder()
                    .keyFrameInterval(10f) // Increased for faster encoding
                    .bitRate(720 * 1280 * 1.toLong()) // Reduced bitrate
                    .frameRate(20) // Fixed lower frame rate
                    .build()
            }
            1 -> { // Medium quality
                DefaultVideoStrategy.Builder()
                    .keyFrameInterval(10f)
                    .bitRate(360 * 640 * 0.8.toLong()) // Lower bitrate
                    .frameRate(20)
                    .build()
            }
            2 -> { // Low quality - fastest
                DefaultVideoStrategy.Builder()
                    .keyFrameInterval(12f) // Longer interval for speed
                    .bitRate(360 * 640 * 0.5.toLong()) // Minimal bitrate
                    .frameRate(20)
                    .build()
            }
            3 -> { // Custom quality
                DefaultVideoStrategy.Builder()
                    .keyFrameInterval(10f)
                    .bitRate(720 * 1280 * 1.toLong())
                    .frameRate(20)
                    .build()
            }
            4 -> { // Faster 480p
                DefaultVideoStrategy.Builder()
                    .keyFrameInterval(12f)
                    .bitRate(480 * 640 * 0.7.toLong())
                    .frameRate(20)
                    .build()
            }
            5 -> { // Faster 540p
                DefaultVideoStrategy.Builder()
                    .keyFrameInterval(10f)
                    .bitRate(540 * 960 * 0.7.toLong())
                    .frameRate(20)
                    .build()
            }
            6 -> { // Faster 720p
                DefaultVideoStrategy.Builder()
                    .keyFrameInterval(10f)
                    .bitRate(720 * 1280 * 0.8.toLong())
                    .frameRate(20)
                    .build()
            }
            7 -> { // Faster 1080p
                DefaultVideoStrategy.Builder()
                    .keyFrameInterval(8f) // Slightly lower for quality
                    .bitRate(1080 * 1920 * 0.8.toLong())
                    .frameRate(20)
                    .build()
            }
            else -> { // Default - very fast
                DefaultVideoStrategy.Builder()
                    .keyFrameInterval(12f)
                    .bitRate(480 * 640 * 0.5.toLong())
                    .frameRate(20)
                    .build()
            }
        }
    }

    /**
     * Get optimized audio strategy for faster compression
     */
    private fun getOptimizedAudioStrategy(includeAudio: Boolean): TrackStrategy {
        return if (includeAudio) {
            DefaultAudioStrategy.builder()
                .channels(1) // Mono for speed
                .sampleRate(32000) // Reduced sample rate
                .bitRate(32 * 1000) // Lower bitrate for faster processing
                .build()
        } else {
            RemoveTrackStrategy()
        }
    }

    override fun onAttachedToEngine(binding: FlutterPlugin.FlutterPluginBinding) {
        init(binding.applicationContext, binding.binaryMessenger)
    }

    override fun onDetachedFromEngine(binding: FlutterPlugin.FlutterPluginBinding) {
        _channel?.setMethodCallHandler(null)
        _context = null
        _channel = null
        threadPool.shutdown()
    }

    private fun init(context: Context, messenger: BinaryMessenger) {
        val channel = MethodChannel(messenger, channelName)
        channel.setMethodCallHandler(this)
        _context = context
        _channel = channel
    }

    companion object {
        private const val TAG = "video_compress"
    }
}