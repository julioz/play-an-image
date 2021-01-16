#!/usr/bin/env kscript
import java.io.File
import java.io.FileOutputStream
import java.awt.image.BufferedImage
import javax.imageio.ImageIO
import java.nio.ByteBuffer

fun BufferedImage.getBrightness(x: Int, y: Int): Float {
    val color: Int = getRGB(x, y)

    // extract each color component
    val red = color ushr 16 and 0xFF
    val green = color ushr 8 and 0xFF
    val blue = color ushr 0 and 0xFF

    // calc luminance in range 0.0 to 1.0; using SRGB luminance constants
    return (red * 0.2126f + green * 0.7152f + blue * 0.0722f) / 255
}

fun Int.toByteArray(): ByteArray {
    return ByteBuffer.allocate(java.lang.Integer.BYTES)
        .putInt(this)
        .array()
}

val waveform = File("data/waveform-original.bmp")
val waveformImage: BufferedImage = ImageIO.read(waveform)

println("Image ${waveform.name}, width = ${waveformImage.width}, height = ${waveformImage.height}.")

val values: ArrayList<Int> = arrayListOf()

// Build an array with all min+max pairs of each of the y values for the samples in the image
for (x in 0 until waveformImage.width) {
    var max = 0
    var min = waveformImage.height

    for (y in 0 until waveformImage.height) {
        if (waveformImage.getBrightness(x, y) < 0.5f) {
            min = minOf(y, min);
            max = maxOf(max, y);
        }
    }

    values.add(min);
    values.add(max);
}

println("Identified PCM sampling min and max values.")

// Smoothing filtering: average the y values of the next $filter values
val filter: Int = 4;
val valuesArray: Array<Int> = values.toTypedArray()
for (x in 0 until (values.size - filter)) {
    val range: Array<Int> = valuesArray.copyOfRange(x, x + filter)
    val newValue: Int = range.average().toInt();
    values.set(x, newValue)
}

println("Applied smooth sampling filtering with depth of $filter.")

val textOutput = File(waveform.nameWithoutExtension + ".txt").apply { delete() }
values.forEach {
    textOutput.appendText("$it\n")
}
println("Check the generated samples in ${textOutput.name}.")

val waveOutput = File("playable-image.wav").apply { delete() }

val RIFF_HEADER = "RIFF".toByteArray()
val FORMAT_WAVE = "WAVE".toByteArray()
val FORMAT_TAG = byteArrayOf(0x66, 0x6D, 0x74, 0x20) // "fmt " - note the trailing null character
val FORMAT_LENGTH = 16
val AUDIO_FORMAT = byteArrayOf(0x1, 0x0) // PCM
val SUBCHUNK_ID = "data".toByteArray()
val WAVE_HEADER_LENGTH = 40

val BYTES_PER_SAMPLE: Int = 1
val SAMPLE_RATE: Int = 48000
val CHANNEL_COUNT: Int = 1
val TIME_STRETCH_RATE: Int = 9
val BYTE_RATE: Int = SAMPLE_RATE * CHANNEL_COUNT * BYTES_PER_SAMPLE
val BLOCK_ALIGN: Int = CHANNEL_COUNT * BYTES_PER_SAMPLE

val dataLength: Int = values.size * TIME_STRETCH_RATE * BYTES_PER_SAMPLE

// Wave file format spec: http://www.topherlee.com/software/pcm-tut-wavformat.html
val outputStream: FileOutputStream = FileOutputStream(waveOutput)
outputStream.write(RIFF_HEADER)
outputStream.write((dataLength + WAVE_HEADER_LENGTH).toByteArray(), 0, 4)
outputStream.write(FORMAT_WAVE)
outputStream.write(FORMAT_TAG)
outputStream.write(FORMAT_LENGTH.toByteArray(), 0, 4)
outputStream.write(AUDIO_FORMAT)
outputStream.write((CHANNEL_COUNT).toByteArray(), 0, 2)
outputStream.write((SAMPLE_RATE).toByteArray(), 0, 4)
outputStream.write((BYTE_RATE).toByteArray(), 0, 4)
outputStream.write((BLOCK_ALIGN).toByteArray(), 0, 2)
outputStream.write((BYTES_PER_SAMPLE * 8).toByteArray(), 0, 2)
outputStream.write(SUBCHUNK_ID)
outputStream.write((dataLength).toByteArray(), 0, 4)

println("WAV file header is ready. Writing samples with time-stretch rate of $TIME_STRETCH_RATE...")

// Apply time-stretching algorithm for given rate

var lastV2: Int = 0
val minValue: Int = values.minOrNull()!!
val maxValue: Int = values.maxOrNull()!!
val waveformMedianValue: Int = maxValue - minValue
values.forEach { v ->
    val v2: Double = (v - minValue).toDouble() / waveformMedianValue.toDouble() * 255;

    for (x in 0 until TIME_STRETCH_RATE) {
        val v3: Double = x / TIME_STRETCH_RATE.toDouble() * v2 + (1 - x / TIME_STRETCH_RATE.toDouble()) * lastV2;
        outputStream.write(byteArrayOf(v3.toUInt().toByte()), 0, 1);
    }

    lastV2 = v2.toInt();
}

println("Done! Listen to ${waveOutput.name} as an unsigned 8-bit PCM mono audio stream.")
