@file:Suppress("NOTHING_TO_INLINE")

package io.github.we.lite.utils

import java.nio.ByteBuffer

inline fun ByteArray.toByteBuffer() = ByteBuffer.wrap(this)

