package com.jarvis.android.wake

import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * The spectrogram model declares its audio input as a 1x1 tensor whose real length is set when it
 * runs. TFLite's Java interpreter allocates tensors as soon as it is created and rejects that
 * placeholder, so the declared length is rewritten in the file first (the length is still changed
 * with `resizeInput` at run time). This reads the FlatBuffers structure of a `.tflite` file just
 * far enough to find the first input tensor's shape.
 */
internal fun withInputLength(model: ByteArray, length: Int): ByteArray {
    val out = model.copyOf()
    val b = ByteBuffer.wrap(out).order(ByteOrder.LITTLE_ENDIAN)
    fun u32(p: Int) = b.getInt(p).toLong() and 0xFFFFFFFFL
    fun field(table: Int, id: Int): Int? {
        val vtable = table - b.getInt(table)
        val size = b.getShort(vtable).toInt() and 0xFFFF
        val at = 4 + 2 * id
        if (at >= size) return null
        val offset = b.getShort(vtable + at).toInt() and 0xFFFF
        return if (offset == 0) null else table + offset
    }
    /** Start of the elements and count of a vector stored in field [id] of [table]. */
    fun vector(table: Int, id: Int): Pair<Int, Int>? {
        val p = field(table, id) ?: return null
        val v = p + u32(p).toInt()
        return (v + 4) to u32(v).toInt()
    }
    try {
        val root = u32(0).toInt()
        val (subgraphs, count) = vector(root, 2) ?: return out
        if (count < 1) return out
        val subgraph = subgraphs + u32(subgraphs).toInt()
        val (tensors, tensorCount) = vector(subgraph, 0) ?: return out
        val (inputs, inputCount) = vector(subgraph, 1) ?: return out
        if (inputCount < 1) return out
        val index = b.getInt(inputs)
        if (index < 0 || index >= tensorCount) return out
        val tensor = tensors + 4 * index + u32(tensors + 4 * index).toInt()
        val (shape, dims) = vector(tensor, 0) ?: return out
        if (dims < 2) return out
        b.putInt(shape + 4 * (dims - 1), length)
    } catch (_: IndexOutOfBoundsException) {
        return model.copyOf()
    }
    return out
}
