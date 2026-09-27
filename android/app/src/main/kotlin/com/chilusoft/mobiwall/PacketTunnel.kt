package com.chilusoft.mobiwall

import android.net.VpnService
import java.io.FileInputStream
import java.io.FileOutputStream
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.ConcurrentHashMap
import kotlin.concurrent.thread

/**
 * Collects per-remote-endpoint traffic stats (bytes sent/received, first seen, optional domain).
 * Thread-safe; used by PacketTunnel to expose active connections to the UI.
 */
class ConnectionStatsCollector {
    private val entries = ConcurrentHashMap<String, Entry>()
    private val vpnClientIp = "10.0.0.2"

    data class Entry(
        val remoteIp: String,
        var bytesSent: Long = 0,
        var bytesReceived: Long = 0,
        var firstSeenMs: Long = System.currentTimeMillis(),
        var domain: String? = null,
    )

    fun addTraffic(remoteIp: String, sentDelta: Long, receivedDelta: Long) {
        if (remoteIp == vpnClientIp) return
        entries.getOrPut(remoteIp) { Entry(remoteIp) }.apply {
            bytesSent += sentDelta
            bytesReceived += receivedDelta
        }
    }

    fun setDomain(remoteIp: String, domain: String?) {
        entries[remoteIp]?.domain = domain
    }

    fun getSnapshot(): List<Map<String, Any>> {
        return entries.values.map { e ->
            mapOf(
                "host" to e.remoteIp,
                "display" to (e.domain ?: e.remoteIp),
                "bytesSent" to e.bytesSent,
                "bytesReceived" to e.bytesReceived,
                "firstSeenMs" to e.firstSeenMs,
            )
        }
    }

    fun clear() = entries.clear()
}

/**
 * Handles VPN tunnel when domain/IP blocklists are active: all traffic goes through the VPN.
 * Drops packets to/from blocked IPs, filters DNS by blocked domains, and forwards the rest.
 */
object PacketTunnel {

    private const val IPPROTO_TCP = 6
    private const val IPPROTO_UDP = 17
    private const val DNS_PORT = 53
    private const val UPSTREAM_DNS = "8.8.8.8"
    private const val VPN_CLIENT_IP = "10.0.0.2"

    fun run(
        vpnFd: java.io.FileDescriptor,
        vpnService: VpnService,
        blockedIps: Set<String>,
        blockedDomains: Set<String>,
        isRunning: () -> Boolean,
        connectionStatsCollector: ConnectionStatsCollector? = null,
    ) {
        val input = FileInputStream(vpnFd)
        val output = FileOutputStream(vpnFd)
        val buffer = ByteBuffer.allocate(32767).order(ByteOrder.BIG_ENDIAN)
        val upstreamDns = InetAddress.getByName(UPSTREAM_DNS)
        val dnsSockets = ConcurrentHashMap<String, DatagramSocket>()

        fun getDnsSocket(): DatagramSocket {
            val key = Thread.currentThread().name ?: "main"
            return dnsSockets.getOrPut(key) {
                DatagramSocket().also { socket ->
                    try {
                        vpnService.protect(socket)
                    } catch (_: Exception) { }
                }
            }
        }

        while (isRunning()) {
            try {
                buffer.clear()
                val read = input.channel.read(buffer)
                if (read <= 0) continue
                buffer.flip()
                if (read < 20) continue // min IPv4 header

                val version = (buffer.get(0).toInt() and 0xF0) shr 4
                if (version != 4) continue

                val protocol = buffer.get(9).toInt() and 0xFF
                val correctSrcIp = ipFromBuffer(buffer, 12)
                val correctDstIp = ipFromBuffer(buffer, 16)

                if (blockedIps.contains(correctSrcIp) || blockedIps.contains(correctDstIp)) continue // drop

                connectionStatsCollector?.let { col ->
                    if (correctSrcIp == VPN_CLIENT_IP) col.addTraffic(correctDstIp, read.toLong(), 0)
                    else if (correctDstIp == VPN_CLIENT_IP) col.addTraffic(correctSrcIp, 0, read.toLong())
                }

                when (protocol) {
                    IPPROTO_UDP -> {
                        if (read < 28) continue // IP header + UDP header
                        val srcPort = buffer.getShort(20).toInt() and 0xFFFF
                        val dstPort = buffer.getShort(22).toInt() and 0xFFFF
                        val udpPayloadStart = 28
                        val udpPayloadLen = read - 28

                        if (dstPort == DNS_PORT) {
                            // DNS query from device -> upstream
                            val query = ByteArray(udpPayloadLen)
                            buffer.position(udpPayloadStart)
                            buffer.get(query)
                            val domain = parseDnsQuestion(query)?.lowercase()
                            if (domain != null && blockedDomains.any { d -> domain == d || domain.endsWith(".$d") }) {
                                val fakeResponse = buildDnsResponseWithZero(query)
                                if (fakeResponse != null) {
                                    val responsePacket = buildIpUdpPacket(
                                        srcIp = correctDstIp, srcPort = dstPort,
                                        dstIp = correctSrcIp, dstPort = srcPort,
                                        payload = fakeResponse
                                    )
                                    synchronized(output) { output.write(responsePacket) }
                                }
                                continue
                            }
                            try {
                                val sock = getDnsSocket()
                                val request = DatagramPacket(query, query.size, upstreamDns, DNS_PORT)
                                sock.send(request)
                                val responseBuf = ByteArray(4096)
                                val response = DatagramPacket(responseBuf, responseBuf.size)
                                sock.soTimeout = 5000
                                sock.receive(response)
                                val responseData = response.data.copyOf(response.length)
                                val rewritten = if (domain != null && blockedDomains.any { d -> domain == d || domain.endsWith(".$d") }) {
                                    rewriteDnsResponseToZero(responseData)
                                } else responseData
                                if (domain != null) {
                                    parseDnsResponseARecords(responseData).forEach { ip ->
                                        connectionStatsCollector?.setDomain(ip, domain)
                                    }
                                }
                                val outPacket = buildIpUdpPacket(
                                    srcIp = UPSTREAM_DNS, srcPort = DNS_PORT,
                                    dstIp = correctSrcIp, dstPort = srcPort,
                                    payload = rewritten
                                )
                                synchronized(output) { output.write(outPacket) }
                            } catch (_: Exception) { }
                            continue
                        }

                        // Forward other UDP
                        forwardUdp(vpnService, buffer, read, output, correctSrcIp, correctDstIp)
                    }
                    IPPROTO_TCP -> {
                        // Forward TCP packets so non-blocked domains keep working
                        forwardTcp(vpnService, buffer, read, output, correctSrcIp, correctDstIp)
                    }
                    else -> { /* drop other protocols */ }
                }
            } catch (e: Exception) {
                if (!isRunning()) break
            }
        }
        dnsSockets.values.forEach { try { it.close() } catch (_: Exception) { } }
    }

    private fun ipFromBuffer(buffer: ByteBuffer, offset: Int): String {
        return "${buffer.get(offset).toInt() and 0xFF}.${buffer.get(offset + 1).toInt() and 0xFF}.${buffer.get(offset + 2).toInt() and 0xFF}.${buffer.get(offset + 3).toInt() and 0xFF}"
    }

    private fun ipToString(a: Byte, b: Byte, c: Byte, d: Byte): String =
        "${a.toInt() and 0xFF}.${b.toInt() and 0xFF}.${c.toInt() and 0xFF}.${d.toInt() and 0xFF}"

    /** Extracts IPv4 addresses from A records in a DNS response. */
    private fun parseDnsResponseARecords(response: ByteArray): List<String> {
        val ips = mutableListOf<String>()
        if (response.size < 12) return ips
        val ancount = ((response[6].toInt() and 0xFF) shl 8) or (response[7].toInt() and 0xFF)
        var pos = 12
        while (pos < response.size) {
            val len = response[pos].toInt() and 0xFF
            pos++
            if (len == 0) break
            if (len >= 192) { pos++; break } // compressed name
            if (pos + len > response.size) break
            pos += len
        }
        if (pos + 4 > response.size) return ips
        pos += 4 // type + class of question
        repeat(ancount) {
            if (pos >= response.size) return@repeat
            if (response[pos].toInt() and 0xFF >= 192) { pos += 2 } else {
                while (pos < response.size && (response[pos].toInt() and 0xFF) != 0) {
                    pos += (response[pos].toInt() and 0xFF) + 1
                }
                pos++
            }
            if (pos + 10 > response.size) return@repeat
            val type = ((response[pos].toInt() and 0xFF) shl 8) or (response[pos + 1].toInt() and 0xFF)
            pos += 8
            val rdlength = ((response[pos].toInt() and 0xFF) shl 8) or (response[pos + 1].toInt() and 0xFF)
            pos += 2
            if (type == 1 && rdlength == 4 && pos + 4 <= response.size) {
                ips.add("${response[pos].toInt() and 0xFF}.${response[pos + 1].toInt() and 0xFF}.${response[pos + 2].toInt() and 0xFF}.${response[pos + 3].toInt() and 0xFF}")
            }
            pos += rdlength
        }
        return ips
    }

    private fun parseDnsQuestion(packet: ByteArray): String? {
        if (packet.size < 12) return null
        val qdcount = ((packet[4].toInt() and 0xFF) shl 8) or (packet[5].toInt() and 0xFF)
        if (qdcount == 0) return null
        var pos = 12
        val labels = mutableListOf<String>()
        while (pos < packet.size) {
            val len = packet[pos].toInt() and 0xFF
            pos++
            if (len == 0) break
            if (pos + len > packet.size) return null
            labels.add(String(packet, pos, len))
            pos += len
        }
        return labels.joinToString(".")
    }

    private fun buildDnsResponseWithZero(query: ByteArray): ByteArray? {
        if (query.size < 12) return null
        var qEnd = 12
        while (qEnd < query.size && query[qEnd].toInt() and 0xFF != 0) {
            qEnd += (query[qEnd].toInt() and 0xFF) + 1
        }
        if (qEnd >= query.size) return null
        qEnd++
        if (qEnd + 4 > query.size) return null
        qEnd += 4 // type + class
        val id = query.copyOfRange(0, 2)
        val flags = byteArrayOf(0x81.toByte(), 0x80.toByte()) // QR=1, RD=1, RA=1
        val qdcount = query.copyOfRange(4, 6)
        val ancount = byteArrayOf(0, 1)
        val nscount = byteArrayOf(0, 0)
        val arcount = byteArrayOf(0, 0)
        val question = query.copyOfRange(12, qEnd)
        val answer = buildDnsARecord(12, 60, "0.0.0.0")
        return id + flags + qdcount + ancount + nscount + arcount + question + answer
    }

    private fun buildDnsARecord(namePtr: Int, ttl: Int, ip: String): ByteArray {
        val name = byteArrayOf(0xC0.toByte(), (namePtr and 0xFF).toByte())
        val typeA = byteArrayOf(0, 1)
        val cls = byteArrayOf(0, 1)
        val ttlBytes = byteArrayOf(
            (ttl shr 24 and 0xFF).toByte(), (ttl shr 16 and 0xFF).toByte(),
            (ttl shr 8 and 0xFF).toByte(), (ttl and 0xFF).toByte()
        )
        val parts = ip.split(".")
        val rdlength = byteArrayOf(0, 4)
        val rdata = parts.map { it.toInt().toByte() }.toByteArray()
        return name + typeA + cls + ttlBytes + rdlength + rdata
    }

    private fun rewriteDnsResponseToZero(response: ByteArray): ByteArray = response

    private fun buildIpUdpPacket(
        srcIp: String,
        srcPort: Int,
        dstIp: String,
        dstPort: Int,
        payload: ByteArray,
    ): ByteArray {
        val src = InetAddress.getByName(srcIp).address
        val dst = InetAddress.getByName(dstIp).address
        val udpLen = 8 + payload.size
        val totalLen = 20 + udpLen
        val buffer = ByteBuffer.allocate(totalLen).order(ByteOrder.BIG_ENDIAN)
        buffer.put(0x45.toByte())
        buffer.put(0)
        buffer.putShort(totalLen.toShort())
        buffer.putShort(0)
        buffer.putShort(0)
        buffer.put(64)
        buffer.put(IPPROTO_UDP.toByte())
        buffer.putShort(0)
        buffer.put(src)
        buffer.put(dst)
        buffer.putShort((srcPort and 0xFFFF).toShort())
        buffer.putShort((dstPort and 0xFFFF).toShort())
        buffer.putShort((udpLen and 0xFFFF).toShort())
        buffer.putShort(0)
        buffer.put(payload)
        val ipChecksum = ipChecksum(buffer.array(), 0, 20)
        buffer.putShort(10, (ipChecksum and 0xFFFF).toShort())
        return buffer.array()
    }

    private fun ipChecksum(data: ByteArray, offset: Int, len: Int): Int {
        var sum = 0
        var i = offset
        while (i < offset + len - 1 && i + 1 < data.size) {
            sum += ((data[i].toInt() and 0xFF) shl 8) or (data[i + 1].toInt() and 0xFF)
            if (sum and 0xFFFF0000.toInt() != 0) sum = (sum and 0xFFFF) + (sum shr 16)
            i += 2
        }
        return (sum.inv() and 0xFFFF)
    }

    private fun forwardUdp(
        vpnService: VpnService,
        buffer: ByteBuffer,
        read: Int,
        output: FileOutputStream,
        srcIp: String,
        dstIp: String,
    ) {
        val srcPort = buffer.getShort(20).toInt() and 0xFFFF
        val dstPort = buffer.getShort(22).toInt() and 0xFFFF
        val payload = ByteArray(read - 28)
        buffer.position(28)
        buffer.get(payload)
        thread {
            try {
                val sock = DatagramSocket()
                vpnService.protect(sock)
                sock.send(DatagramPacket(payload, payload.size, InetAddress.getByName(dstIp), dstPort))
                val reply = ByteArray(4096)
                val replyPkt = DatagramPacket(reply, reply.size)
                sock.soTimeout = 3000
                sock.receive(replyPkt)
                val replyData = reply.copyOf(replyPkt.length)
                val outPacket = buildIpUdpPacket(
                    srcIp = dstIp, srcPort = dstPort,
                    dstIp = srcIp, dstPort = srcPort,
                    payload = replyData
                )
                synchronized(output) { output.write(outPacket) }
            } catch (_: Exception) { }
        }
    }

    /**
     * Forwards a TCP packet to its destination and relays the response back through the VPN.
     * This is a best-effort implementation that handles the common case of a single
     * request-response exchange per packet.
     */
    private fun forwardTcp(
        vpnService: VpnService,
        buffer: ByteBuffer,
        read: Int,
        output: FileOutputStream,
        srcIp: String,
        dstIp: String,
    ) {
        // IP header is 20 bytes (no options in the common case)
        // TCP header starts at offset 20
        if (read < 40) return // need at least IP + TCP header
        val srcPort = buffer.getShort(20).toInt() and 0xFFFF
        val dstPort = buffer.getShort(22).toInt() and 0xFFFF
        val tcpDataOffset = ((buffer.get(32).toInt() and 0xF0) shr 4) * 4
        val payloadStart = 20 + tcpDataOffset
        val payloadLen = read - payloadStart
        thread {
            var sock: Socket? = null
            try {
                sock = Socket()
                vpnService.protect(sock)
                sock.connect(InetSocketAddress(InetAddress.getByName(dstIp), dstPort), 10000)
                sock.soTimeout = 10000
                // Forward payload if present
                if (payloadLen > 0) {
                    val payload = ByteArray(payloadLen)
                    buffer.position(payloadStart)
                    buffer.get(payload)
                    sock.getOutputStream().write(payload)
                    sock.getOutputStream().flush()
                }
                // Read response
                val reply = ByteArray(8192)
                val replyLen = sock.getInputStream().read(reply)
                if (replyLen > 0) {
                    val replyData = reply.copyOf(replyLen)
                    val outPacket = buildIpTcpPacket(
                        srcIp = dstIp, srcPort = dstPort,
                        dstIp = srcIp, dstPort = srcPort,
                        seqNum = 1, ackNum = 1,
                        payload = replyData,
                    )
                    synchronized(output) { output.write(outPacket) }
                }
            } catch (_: Exception) { }
            finally {
                try { sock?.close() } catch (_: Exception) { }
            }
        }
    }

    private fun buildIpTcpPacket(
        srcIp: String,
        srcPort: Int,
        dstIp: String,
        dstPort: Int,
        seqNum: Int,
        ackNum: Int,
        payload: ByteArray,
    ): ByteArray {
        val src = InetAddress.getByName(srcIp).address
        val dst = InetAddress.getByName(dstIp).address
        val tcpLen = 20 + payload.size
        val totalLen = 20 + tcpLen
        val buffer = ByteBuffer.allocate(totalLen).order(ByteOrder.BIG_ENDIAN)
        // IP header
        buffer.put(0x45.toByte())
        buffer.put(0)
        buffer.putShort(totalLen.toShort())
        buffer.putShort(0) // identification
        buffer.putShort(0) // flags + fragment offset
        buffer.put(64) // TTL
        buffer.put(IPPROTO_TCP.toByte())
        buffer.putShort(0) // checksum placeholder
        buffer.put(src)
        buffer.put(dst)
        // TCP header
        buffer.putShort((srcPort and 0xFFFF).toShort())
        buffer.putShort((dstPort and 0xFFFF).toShort())
        buffer.putInt(seqNum)
        buffer.putInt(ackNum)
        buffer.put(0x50.toByte()) // data offset = 5 (20 bytes), no options
        buffer.put(0x18.toByte()) // flags: PSH + ACK
        buffer.putShort(8192.toShort()) // window size
        buffer.putShort(0) // checksum placeholder
        buffer.putShort(0) // urgent pointer
        // Payload
        buffer.put(payload)
        // Fix IP checksum
        val ipChecksum = ipChecksum(buffer.array(), 0, 20)
        buffer.putShort(10, (ipChecksum and 0xFFFF).toShort())
        return buffer.array()
    }
}
