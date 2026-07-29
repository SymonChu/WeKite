package io.github.we.lite.features.items.moments

object AutoRefresh {
    interface IRefreshListener {
        fun onRefresh()
    }

    private val listeners = mutableListOf<IRefreshListener>()

    fun addListener(listener: IRefreshListener) {
        listeners.add(listener)
    }

    fun removeListener(listener: IRefreshListener) {
        listeners.remove(listener)
    }
}
