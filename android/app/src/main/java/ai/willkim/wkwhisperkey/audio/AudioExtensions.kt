fun ShortArray.toByteBuffer(): java.nio.ByteBuffer {
    val bb = java.nio.ByteBuffer.allocate(this.size * 2)
    bb.order(java.nio.ByteOrder.LITTLE_ENDIAN)
    forEach { bb.putShort(it) }
    bb.flip()
    return bb
}
