package com.jarvis.android.wake

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

class TfliteShapeTest {
    /**
     * A hand-built FlatBuffer with the structure of a .tflite file, reduced to what the reader
     * follows: Model.subgraphs[0].{tensors[0].shape, inputs[0]}.
     */
    private fun model(shape: IntArray): ByteArray {
        val out = ByteBuffer.allocate(512).order(ByteOrder.LITTLE_ENDIAN)
        // Layout (byte offsets), written back to front by hand:
        val root = 16          // Model table
        val rootVt = 4         // Model vtable
        val subVec = 32        // subgraphs vector: [len=1][uoffset]
        val sub = 56           // SubGraph table
        val subVt = 44         // SubGraph vtable
        val tensorsVec = 72    // tensors vector: [len=1][uoffset]
        val inputsVec = 84     // inputs vector: [len=1][0]
        val tensor = 104       // Tensor table
        val tensorVt = 96     // Tensor vtable
        val shapeVec = 116     // shape vector: [len][values...]

        out.putInt(0, root)
        // Model vtable: size 8, table size 8, field 0..1 absent, field 2 (subgraphs) at +4
        out.putShort(rootVt, 10); out.putShort(rootVt + 2, 8); out.putShort(rootVt + 4, 0); out.putShort(rootVt + 6, 0); out.putShort(rootVt + 8, 4)
        out.putInt(root, root - rootVt)
        out.putInt(root + 4, subVec - (root + 4))
        out.putInt(subVec, 1); out.putInt(subVec + 4, sub - (subVec + 4))
        // SubGraph vtable: field 0 (tensors) at +4, field 1 (inputs) at +8
        out.putShort(subVt, 8); out.putShort(subVt + 2, 12); out.putShort(subVt + 4, 4); out.putShort(subVt + 6, 8)
        out.putInt(sub, sub - subVt)
        out.putInt(sub + 4, tensorsVec - (sub + 4))
        out.putInt(sub + 8, inputsVec - (sub + 8))
        out.putInt(tensorsVec, 1); out.putInt(tensorsVec + 4, tensor - (tensorsVec + 4))
        out.putInt(inputsVec, 1); out.putInt(inputsVec + 4, 0)
        // Tensor vtable: field 0 (shape) at +4
        out.putShort(tensorVt, 6); out.putShort(tensorVt + 2, 8); out.putShort(tensorVt + 4, 4)
        out.putInt(tensor, tensor - tensorVt)
        out.putInt(tensor + 4, shapeVec - (tensor + 4))
        out.putInt(shapeVec, shape.size)
        shape.forEachIndexed { i, v -> out.putInt(shapeVec + 4 + 4 * i, v) }
        return out.array()
    }

    private fun shapeOf(bytes: ByteArray): IntArray {
        val b = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        val n = b.getInt(116)
        return IntArray(n) { b.getInt(120 + 4 * it) }
    }

    @Test
    fun `the placeholder length of the first input is replaced`() {
        val patched = withInputLength(model(intArrayOf(1, 1)), 1760)
        assertArrayEquals(intArrayOf(1, 1760), shapeOf(patched))
    }

    @Test
    fun `only the last dimension changes and the input is not modified in place`() {
        val original = model(intArrayOf(1, 1, 1))
        val patched = withInputLength(original, 99)
        assertArrayEquals(intArrayOf(1, 1, 99), shapeOf(patched))
        assertArrayEquals(intArrayOf(1, 1, 1), shapeOf(original))
    }

    @Test
    fun `a single dimension or garbage is left alone`() {
        val one = model(intArrayOf(7))
        assertArrayEquals(one, withInputLength(one, 5))
        val garbage = ByteArray(64) { 0x7F }
        assertEquals(64, withInputLength(garbage, 5).size)
        assertEquals(0, withInputLength(ByteArray(0), 5).size)
    }
}
