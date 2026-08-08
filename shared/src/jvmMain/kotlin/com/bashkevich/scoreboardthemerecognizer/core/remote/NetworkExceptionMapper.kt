package com.bashkevich.scoreboardthemerecognizer.core.remote

import java.net.ConnectException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import javax.net.ssl.SSLException

actual fun Throwable.toNetworkException(): NetworkException? = when (this) {
    is UnknownHostException -> NetworkException(this)
    is SocketTimeoutException -> NetworkException(this)
    is ConnectException -> NetworkException(this)
    is SSLException -> NetworkException(this)
    else -> null
}
