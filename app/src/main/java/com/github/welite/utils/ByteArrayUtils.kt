@file:Suppress("NOTHING_TO_INLINE")

package com.github.welite.utils

import java.nio.ByteBuffer

inline fun ByteArray.toByteBuffer() = ByteBuffer.wrap(this)

