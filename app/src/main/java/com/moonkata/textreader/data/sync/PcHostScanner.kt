package com.moonkata.textreader.data.sync

import android.content.Context
import android.net.ConnectivityManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.withContext

private const val CONCURRENCY = 64

/**
 * 로컬 서브넷에서 PC 트레이 서버를 찾는다 (.docs/PC_SYNC_SERVER_PLAN.md §3 "호스트 찾기").
 * SMB 버전의 순수 포트 스캔과 달리, 우리가 프로토콜을 통제하므로 각 후보에 `/ping`을 직접 호출해서
 * "포트가 열려있는지"와 "진짜 우리 서버가 맞는지"를 한 번에 확인한다 — SMB 스캔보다 정확도가 높다.
 */
class PcHostScanner(private val context: Context) {

    /** `/24`로 가정한 로컬 서브넷의 254개 주소를 [CONCURRENCY]개씩 병렬로 `/ping`을 쳐서 찾은 IP만 돌려준다. */
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
            .firstOrNull { it.hostAddress?.contains(':') == false } // IPv4만(IPv6 주소엔 ':' 포함)
            ?.hostAddress
    }
}
