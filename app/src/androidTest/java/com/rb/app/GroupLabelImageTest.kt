package com.rb.app

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.google.android.gms.tasks.Tasks
import com.google.mlkit.vision.barcode.BarcodeScanning
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import com.rb.app.scanner.GroupLabelScanner
import com.rb.app.scanner.SerialRecognition
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.TimeUnit

/** Real bundled ML Kit decoding of a generated label, not a physical camera accuracy test. */
@RunWith(AndroidJUnit4::class)
class GroupLabelImageTest {
    private val upper = "8996603430263612700"
    private val lower = "8996603430263612799"
    private fun sample(): Bitmap = InstrumentationRegistry.getInstrumentation().context.assets
        .open("group-label.png").use { requireNotNull(BitmapFactory.decodeStream(it)) }
    private fun read(bitmap: Bitmap): SerialRecognition.Reading {
        val bars = BarcodeScanning.getClient()
        val text = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
        try {
            val input = InputImage.fromBitmap(bitmap, 0)
            val barcodes = Tasks.await(bars.process(input), 45, TimeUnit.SECONDS)
            val ocr = Tasks.await(text.process(input), 45, TimeUnit.SECONDS)
            fun points(points: Array<android.graphics.Point>?) = points.orEmpty().map {
                GroupLabelScanner.Point(it.x.toDouble(), it.y.toDouble())
            }
            return GroupLabelScanner.read(barcodes.mapNotNull { bar -> bar.rawValue?.let {
                GroupLabelScanner.Observation(it, points(bar.cornerPoints))
            } }, ocr.textBlocks.flatMap { it.lines }.map {
                GroupLabelScanner.Observation(it.text, points(it.cornerPoints), it.angle.toDouble())
            })
        } finally { bars.close(); text.close() }
    }
    @Test fun uprightLabelDecodesBothNumbersAndPrefersUpperWithActualModels() {
        val bitmap = sample()
        try {
            val reading = read(bitmap)
            assertEquals(setOf(upper, lower), reading.candidates.toSet())
            assertEquals(setOf(upper, lower), reading.barcodeCandidates.toSet())
            assertEquals(upper, reading.groupUpperCandidate)
        } finally { bitmap.recycle() }
    }
    @Test fun rotatedLabelsNeverPreferTheLowerNumberAndPartialLabelsRequireReview() {
        val bitmap = sample()
        try {
            for (degrees in listOf(15f, 90f, 180f, 270f)) {
                val rotated = Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height,
                    Matrix().apply { postRotate(degrees) }, true)
                try {
                    val reading = read(rotated)
                    assertTrue("Wrong upper choice at $degrees: $reading", reading.groupUpperCandidate == null || reading.groupUpperCandidate == upper)
                } finally { if (rotated !== bitmap) rotated.recycle() }
            }
            val partial = Bitmap.createBitmap(bitmap, 0, 80, bitmap.width, 350)
            try { assertNull(read(partial).groupUpperCandidate) } finally { partial.recycle() }
        } finally { bitmap.recycle() }
    }
}
