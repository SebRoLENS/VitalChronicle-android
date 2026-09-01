package io.github.sebrolens.vitalchronicle.android

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import okhttp3.Dns
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import java.net.UnknownHostException

/**
 * HTTP transport which explicitly uses Android's current active network.
 *
 * Some devices can successfully open the OAuth page in the browser while the
 * app process itself fails DNS lookup. Binding DNS and sockets to the active
 * Android Network avoids stale or incorrect process-level routing. A normal
 * OkHttp attempt is retained as a fallback.
 */
class AndroidHttpClient(
    context: Context,
    private val defaultClient: OkHttpClient = OkHttpClient(),
) {
    private val connectivity =
        context.applicationContext.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager

    fun execute(request: Request): Response {
        var activeNetworkError: UnknownHostException? = null

        activeNetworkClient()?.let { client ->
            try {
                return client.newCall(request).execute()
            } catch (e: UnknownHostException) {
                activeNetworkError = e
            }
        }

        try {
            return defaultClient.newCall(request).execute()
        } catch (e: UnknownHostException) {
            val cause = activeNetworkError ?: e
            val wrapped = UnknownHostException(dnsFailureMessage(request.url.host, cause))
            wrapped.initCause(e)
            throw wrapped
        }
    }

    private fun activeNetworkClient(): OkHttpClient? {
        val network = connectivity.activeNetwork ?: return null
        val networkDns = Dns { hostname ->
            val addresses = network.getAllByName(hostname).toList()
            if (addresses.isEmpty()) throw UnknownHostException("No addresses returned for $hostname")
            addresses
        }
        return defaultClient.newBuilder()
            .socketFactory(network.socketFactory)
            .dns(networkDns)
            .build()
    }

    private fun dnsFailureMessage(host: String, cause: UnknownHostException): String {
        val network = connectivity.activeNetwork
        val caps = network?.let(connectivity::getNetworkCapabilities)
        val transports = buildList {
            if (caps?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true) add("Wi-Fi")
            if (caps?.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) == true) add("cellular")
            if (caps?.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) == true) add("Ethernet")
            if (caps?.hasTransport(NetworkCapabilities.TRANSPORT_VPN) == true) add("VPN")
        }.ifEmpty { listOf("unknown") }.joinToString("+")
        val dnsServers = network
            ?.let(connectivity::getLinkProperties)
            ?.dnsServers
            ?.joinToString { it.hostAddress ?: it.toString() }
            .orEmpty()
            .ifBlank { "unknown" }
        return "Unable to resolve host \"$host\" using Android active network " +
            "($transports; DNS: $dnsServers). ${cause.message.orEmpty()}"
    }
}
