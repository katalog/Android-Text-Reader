package com.moonkata.textreader.data.sync

import android.content.Context
import android.net.ConnectivityManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.withContext

private const val CONCURRENCY = 64

/**
 * Finds the PC tray server on the local subnet (.docs/PC_SYNC_SERVER_PLAN.md §3 "host discovery").
 * Unlike the SMB version's plain port scan, since we control the protocol here, each candidate is
 * confirmed with a direct `/ping` call, checking "is the port open" and "is this genuinely our server" in
 * one shot — more accurate than an SMB scan.
 */
class PcHostScanner(private val context: Context) {

    /** Pings [CONCURRENCY] addresses at a time out of the 254 addresses in the local /24 subnet, and returns only the IPs found. */
    suspend fun scanLocalSubnet(): List<String> = withContext(Dispatchers.IO) {
        val prefix = currentLocalIpv4()?.substringBeforeLast('.') ?: return@withContext emptyList()
        val found = mutableListOf<String>()
        (1..254).chunked(CONCURRENCY).forEach { chunk ->
            val results = chunk.map { last ->
                val candidate = "$prefix.$last"
                async { candidate to PcSyncClient.isPcSyncServer(candidate) }
            }.awaitAll()
            found += results.filter { it.second }.map { it.first }
        }
        found
    }

    private fun currentLocalIpv4(): String? {
        val connectivityManager = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
            ?: return null
        val network = connectivityManager.activeNetwork ?: return null
        val linkProperties = connectivityManager.getLinkProperties(network) ?: return null
        return linkProperties.linkAddresses
            .map { it.address }
            .firstOrNull { it.hostAddress?.contains(':') == false } // IPv4 only (IPv6 addresses contain ':')
            ?.hostAddress
    }
}
