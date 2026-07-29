package io.github.we.lite.features.items.moments

enum class MomentsContentType(val typeId: Int, val displayName: String) {
    UNKNOWN(0, "未知");

    companion object {
        val allTypeIds: Set<Int> get() = entries.map { it.typeId }.toSet()
        fun fromId(id: Int): MomentsContentType? = entries.firstOrNull { it.typeId == id }
    }
}
