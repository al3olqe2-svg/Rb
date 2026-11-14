package com.rb.app.scanner

import android.os.SystemClock
import androidx.camera.core.ExperimentalGetImage
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import com.google.mlkit.vision.barcode.BarcodeScanning
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import java.util.concurrent.Executor
import java.util.concurrent.atomic.AtomicBoolean

/** One frame in flight, throttled. Both tasks finish before closing the ImageProxy or their clients. */
@androidx.annotation.OptIn(ExperimentalGetImage::class)
class SimAnalyzer(private val callbackExecutor: Executor,
    private val result: (SerialRecognition.Reading) -> Unit, private val error: () -> Unit, private val group: Boolean = false,
) : ImageAnalysis.Analyzer {
    private val barcode = BarcodeScanning.getClient()
    private val text = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
    private val closed = AtomicBoolean(false)
    private val running = AtomicBoolean(false)
    private var lastFrame = 0L
    override fun analyze(proxy: ImageProxy) {
        val now = SystemClock.elapsedRealtime()
        if (closed.get() || now - lastFrame < 450 || !running.compareAndSet(false, true)) { proxy.close(); return }
        lastFrame = now
        fun finish() {
            proxy.close(); running.set(false)
            if (closed.get()) release()
        }
        try {
            val media = proxy.image
            if (media == null) { finish(); return }
            val input = InputImage.fromMediaImage(media, proxy.imageInfo.rotationDegrees)
            barcode.process(input).addOnCompleteListener(callbackExecutor) { bars ->
                if (closed.get()) { finish(); return@addOnCompleteListener }
                try {
                    text.process(input).addOnCompleteListener(callbackExecutor) { ocr ->
                        try {
                            if (!closed.get()) {
                                if (!bars.isSuccessful || !ocr.isSuccessful) error()
                                // A failed OCR task cannot corroborate a barcode: require explicit review.
                                val reading = if (group) {
                                    fun points(points: Array<android.graphics.Point>?) = points.orEmpty().map {
                                        GroupLabelScanner.Point(it.x.toDouble(), it.y.toDouble())
                                    }
                                    GroupLabelScanner.read(
                                        if (bars.isSuccessful) bars.result.mapNotNull { bar -> bar.rawValue?.let {
                                            GroupLabelScanner.Observation(it, points(bar.cornerPoints))
                                        } } else emptyList(),
                                        if (ocr.isSuccessful) ocr.result.textBlocks.flatMap { it.lines }.map {
                                            GroupLabelScanner.Observation(it.text, points(it.cornerPoints), it.angle.toDouble())
                                        } else emptyList())
                                } else SerialRecognition.read(
                                    if (bars.isSuccessful) bars.result.mapNotNull { it.rawValue } else emptyList(),
                                    if (ocr.isSuccessful) ocr.result.textBlocks.flatMap { it.lines }.map { it.text } else emptyList())
                                result(if (bars.isSuccessful && ocr.isSuccessful) reading else reading.copy(automatic = false, groupUpperCandidate = null))
                            }
                        } finally { finish() }
                    }
                } catch (_: Exception) { if (!closed.get()) error(); finish() }
            }
        } catch (_: Exception) { if (!closed.get()) error(); finish() }
    }
    fun close() { closed.set(true); if (!running.get()) release() }
    private val released = AtomicBoolean(false)
    private fun release() { if (released.compareAndSet(false, true)) { barcode.close(); text.close() } }
}
