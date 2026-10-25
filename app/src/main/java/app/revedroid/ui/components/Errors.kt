package app.revedroid.ui.components

import app.revedroid.data.api.ApiException
import app.revedroid.data.net.CloudflareBlockedException
import app.revedroid.data.net.CloudflareChallengeException
import kotlinx.serialization.SerializationException
import java.io.IOException
import java.net.SocketTimeoutException
import java.net.UnknownHostException

fun Throwable.userMessage(): String =
    when (this) {
        is ApiException -> message ?: "Server error"
        is CloudflareChallengeException -> "Cloudflare verification is required"
        is CloudflareBlockedException -> "Cloudflare blocked the request"
        is UnknownHostException -> "No connection to the server"
        is SocketTimeoutException -> "The server took too long to respond"
        is SerializationException -> "Unexpected response from the server"
        is IOException -> message ?: "Network error"
        else -> message ?: "Something went wrong"
    }
