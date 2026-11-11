package com.elixir.tracker

import android.content.Context
import android.graphics.Bitmap
import org.tensorflow.lite.Interpreter
import org.tensorflow.lite.support.common.FileUtil
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.exp
import kotlin.math.max

class CardDetector(context: Context) {
    private val interpreter: Interpreter
    val labels: List<String>
    private val inputSize = 640
    private val confThreshold = 0.45f
    private val numClasses: Int

    // Cooldown to avoid counting the same card multiple times
    private var lastCard: String? = null
    private var lastDetectTime = 0L
    private val cooldownMs = 1800L

    init {
        val model = FileUtil.loadMappedFile(context, "modelo_cartas.tflite")
        val options = Interpreter.Options().apply {
            setNumThreads(2)
        }
        interpreter = Interpreter(model, options)
        labels = context.assets.open("clases.txt").bufferedReader().readLines()
            .map { it.trim() }.filter { it.isNotEmpty() }
        numClasses = labels.size
    }

    data class Detection(val name: String, val confidence: Float, val cost: Int)

    fun detect(bitmap: Bitmap, elixirMap: Map<String, Int>): Detection? {
        val now = System.currentTimeMillis()
        if (now - lastDetectTime < 600) return null // rate limit inference

        val input = preprocess(bitmap)
        val outputShape = interpreter.getOutputTensor(0).shape()
        // Expected: [1, 4+nc, 8400] or [1, 8400, 4+nc]
        val out0 = outputShape[0]
        val out1 = outputShape[1]
        val out2 = if (outputShape.size > 2) outputShape[2] else 1

        val output: Array<Array<FloatArray>>
        val transposed: Boolean

        if (out1 == 4 + numClasses || out1 > out2) {
            // [1, 4+nc, anchors]
            output = Array(1) { Array(out1) { FloatArray(out2) } }
            interpreter.run(input, output)
            transposed = false
        } else {
            // [1, anchors, 4+nc]
            output = Array(1) { Array(out1) { FloatArray(out2) } }
            interpreter.run(input, output)
            transposed = true
        }

        var bestScore = 0f
        var bestClass = -1
        val anchors = if (transposed) out1 else out2
        val channels = if (transposed) out2 else out1

        for (a in 0 until anchors) {
            var maxCls = 0f
            var maxIdx = 0
            val start = 4
            for (c in 0 until numClasses) {
                val score = if (transposed) {
                    output[0][a][start + c]
                } else {
                    output[0][start + c][a]
                }
                // some exports are already sigmoid, some not
                val s = if (score in 0f..1f) score else (1f / (1f + exp(-score)))
                if (s > maxCls) {
                    maxCls = s
                    maxIdx = c
                }
            }
            if (maxCls > bestScore) {
                bestScore = maxCls
                bestClass = maxIdx
            }
        }

        if (bestClass < 0 || bestScore < confThreshold) return null

        val name = labels.getOrElse(bestClass) { return null }
        if (name == lastCard && now - lastDetectTime < cooldownMs) return null

        val cost = elixirMap[name] ?: elixirMap.entries
            .firstOrNull { it.key.equals(name, ignoreCase = true) }?.value
            ?: 0
        if (cost <= 0) return null

        lastCard = name
        lastDetectTime = now
        return Detection(name, bestScore, cost)
    }

    private fun preprocess(src: Bitmap): ByteBuffer {
        val resized = Bitmap.createScaledBitmap(src, inputSize, inputSize, true)
        val buf = ByteBuffer.allocateDirect(1 * inputSize * inputSize * 3 * 4)
        buf.order(ByteOrder.nativeOrder())
        val pixels = IntArray(inputSize * inputSize)
        resized.getPixels(pixels, 0, inputSize, 0, 0, inputSize, inputSize)
        for (p in pixels) {
            buf.putFloat(((p shr 16) and 0xFF) / 255f)
            buf.putFloat(((p shr 8) and 0xFF) / 255f)
            buf.putFloat((p and 0xFF) / 255f)
        }
        buf.rewind()
        if (resized != src) resized.recycle()
        return buf
    }

    fun close() {
        interpreter.close()
    }
}
