package com.github.welite.features.api.net.abc

interface WeRequestCallback {
    fun onSuccess(bytes: ByteArray?)
    fun onFailure(errType: Int, errCode: Int, errMsg: String)
}
