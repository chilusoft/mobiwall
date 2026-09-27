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

data class TcpConnection(
    val key: String,
    val srcIp: String,
    val srcPort: Int,
    val dstIp: String,
    val dstPort: Int,
    val sock: Socket,
    var nextSeq: Long = 1,
    var nextAck: Long = 1,
    var closed: Boolean = false,
)

object PacketTunnel {

    private const val IPPROTO_TCP = 6
    private const val IPPROTO_UDP = 17
    private const val DNS_PORT = 53
    private const val UPSTREAM_DNS = "8.8.8.8"
    private const val VPN_CLIENT_IP = "10.0.0.2"

    private val tcpConnections = ConcurrentHashMap<String, TcpConnection>()

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
                if (read < 20) continue

                val version = (buffer.get(0).toInt() and 0xF0) shr 4
                if (version != 4) continue

                val protocol = buffer.get(9).toInt() and 0xFF
                val correctSrcIp = ipFromBuffer(buffer, 12)
                val correctDstIp = ipFromBuffer(buffer, 16)

                if (blockedIps.contains(correctSrcIp) || blockedIps.contains(correctDstIp)) continue

                connectionStatsCollector?.let { col ->
                    if (correctSrcIp == VPN_CLIENT_IP) col.addTraffic(correctDstIp, read.toLong(), 0)
                    else if (correctDstIp == VPN_CLIENT_IP) col.addTraffic(correctSrcIp, 0, read.toLong())
                }

                when (protocol) {
                    IPPROTO_UDP -> {
                        if (read < 28) continue
                        val srcPort = buffer.getShort(20).toInt() and 0xFFFF
                        val dstPort = buffer.getShort(22).toInt() and 0xFFFF
                        val udpPayloadStart = 28
                        val udpPayloadLen = read - 28

                        if (dstPort == DNS_PORT) {
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

                        forwardUdp(vpnService, buffer, read, output, correctSrcIp, correctDstIp)
                    }
                    IPPROTO_TCP -> {
                        handleTcpPacket(vpnService, buffer, read, output, correctSrcIp, correctDstIp)
                    }
                    else -> { }
                }
            } catch (e: Exception) {
                if (!isRunning()) break
            }
        }
        dnsSockets.values.forEach { try { it.close() } catch (_: Exception) { } }
        tcpConnections.values.forEach { try { it.sock.close() } catch (_: Exception) { } }
        tcpConnections.clear()
    }

    private fun getTcpConnectionKey(srcIp: String, srcPort: Int, dstIp: String, dstPort: Int) =
        "$srcIp:$srcPort->$dstIp:$dstPort"

    private fun handleTcpPacket(
        vpnService: VpnService,
        buffer: ByteBuffer,
        read: Int,
        output: FileOutputStream,
        srcIp: String,
        dstIp: String,
    ) {
        if (read < 40) return
        val srcPort = buffer.getShort(20).toInt() and 0xFFFF
        val dstPort = buffer.getShort(22).toInt() and 0xFFFF
        val tcpDataOffset = ((buffer.get(32).toInt() and 0xF0) shr 4) * 4
        val payloadStart = 20 + tcpDataOffset
        val payloadLen = read - payloadStart
        val seqNum = buffer.getInt(24).toLong() and 0xFFFFFFFFL
        val ackNum = buffer.getInt(28).toLong() and 0xFFFFFFFFL
        val flags = buffer.get(33).toInt() and 0xFF
        val synFlag = (flags and 0x02) != 0
        val finFlag = (flags and 0x01) != 0
        val rstFlag = (flags and 0x04) != 0

        val key = getTcpConnectionKey(srcIp, srcPort, dstIp, dstPort)

        if (rstFlag) {
            tcpConnections.remove(key)?.let { conn ->
                try { conn.sock.close() } catch (_: Exception) { }
            }
            return
        }

        if (synFlag) {
            tcpConnections.remove(key)?.let { conn ->
                try { conn.sock.close() } catch (_: Exception) { }
            }
            thread {
                var sock: Socket? = null
                try {
                    sock = Socket()
                    vpnService.protect(sock)
                    sock.connect(InetSocketAddress(InetAddress.getByName(dstIp), dstPort), 15000)
                    sock.soTimeout = 30000
                    val conn = TcpConnection(
                        key, srcIp, srcPort, dstIp, dstPort, sock,
                        nextSeq = 1, nextAck = (seqNum + 1)
                    )
                    tcpConnections[key] = conn
                    val synAck = buildIpTcpPacket(
                        srcIp = dstIp, srcPort = dstPort,
                        dstIp = srcIp, dstPort = srcPort,
                        seqNum = 0, ackNum = conn.nextAck.toInt(),
                        flags = 0x12.toByte(),
                        payload = ByteArray(0),
                    )
                    synchronized(output) { output.write(synAck) }
                    forwardTcpData(conn, output)
                } catch (_: Exception) {
                    tcpConnections.remove(key)
                    try { sock?.close() } catch (_: Exception) { }
                    try {
                        val rst = buildIpTcpPacket(
                            srcIp = dstIp, srcPort = dstPort,
                            dstIp = srcIp, dstPort = srcPort,
                            seqNum = 0, ackNum = (seqNum + 1).toInt(),
                            flags = 0x04.toByte(),
                            payload = ByteArray(0),
                        )
                        synchronized(output) { output.write(rst) }
                    } catch (_: Exception) { }
                }
            }
            return
        }

        val conn = tcpConnections[key] ?: return

        if (payloadLen > 0) {
            conn.nextAck = (seqNum + payloadLen) and 0xFFFFFFFFL
        }

        if (finFlag) {
            conn.closed = true
            try { conn.sock.shutdownOutput() } catch (_: Exception) { }
            try {
                val finAck = buildIpTcpPacket(
                    srcIp = dstIp, srcPort = dstPort,
                    dstIp = srcIp, dstPort = srcPort,
                    seqNum = conn.nextSeq.toInt(), ackNum = conn.nextAck.toInt(),
                    flags = 0x11.toByte(),
                    payload = ByteArray(0),
                )
                synchronized(output) { output.write(finAck) }
            } catch (_: Exception) { }
            thread {
                Thread.sleep(2000)
                try { conn.sock.close() } catch (_: Exception) { }
                tcpConnections.remove(key)
            }
            return
        }

        if (payloadLen > 0 && !conn.closed) {
            val payload = ByteArray(payloadLen)
            buffer.position(payloadStart)
            buffer.get(payload)
            thread {
                try {
                    conn.sock.getOutputStream().write(payload)
                    conn.sock.getOutputStream().flush()
                } catch (_: Exception) { }
            }
            thread {
                try {
                    val ack = buildIpTcpPacket(
                        srcIp = dstIp, srcPort = dstPort,
                        dstIp = srcIp, dstPort = srcPort,
                        seqNum = conn.nextSeq.toInt(), ackNum = conn.nextAck.toInt(),
                        flags = 0x10.toByte(),
                        payload = ByteArray(0),
                    )
                    synchronized(output) { output.write(ack) }
                } catch (_: Exception) { }
            }
        }
    }

    private fun forwardTcpData(conn: TcpConnection, output: FileOutputStream) {
        val replyBuf = ByteArray(8192)
        while (!conn.closed) {
            try {
                val n = conn.sock.getInputStream().read(replyBuf)
                if (n <= 0) break
                val data = replyBuf.copyOf(n)
                val outPacket = buildIpTcpPacket(
                    srcIp = conn.dstIp, srcPort = conn.dstPort,
                    dstIp = conn.srcIp, dstPort = conn.srcPort,
                    seqNum = conn.nextSeq.toInt(), ackNum = conn.nextAck.toInt(),
                    flags = 0x18.toByte(),
                    payload = data,
                )
                conn.nextSeq = (conn.nextSeq + n) and 0xFFFFFFFFL
                synchronized(output) { output.write(outPacket) }
            } catch (_: Exception) {
                break
            }
        }
        try {
            val fin = buildIpTcpPacket(
                srcIp = conn.dstIp, srcPort = conn.dstPort,
                dstIp = conn.srcIp, dstPort = conn.srcPort,
                seqNum = conn.nextSeq.toInt(), ackNum = conn.nextAck.toInt(),
                flags = 0x11.toByte(),
                payload = ByteArray(0),
            )
            synchronized(output) { output.write(fin) }
        } catch (_: Exception) { }
        conn.closed = true
    }

    private fun ipFromBuffer(buffer: ByteBuffer, offset: Int): String {
        return "${buffer.get(offset).toInt() and 0xFF}.${buffer.get(offset + 1).toInt() and 0xFF}.${buffer.get(offset + 2).toInt() and 0xFF}.${buffer.get(offset + 3).toInt() and 0xFF}"
    }

    private fun parseDnsResponseARecords(response: ByteArray): List<String> {
        val ips = mutableListOf<String>()
        if (response.size < 12) return ips
        val ancount = ((response[6].toInt() and 0xFF) shl 8) or (response[7].toInt() and 0xFF)
        var pos = 12
        while (pos < response.size) {
            val len = response[pos].toInt() and 0xFF
            pos++
            if (len == 0) break
            if (len >= 192) { pos++; break }
            if (pos + len > response.size) break
            pos += len
        }
        if (pos + 4 > response.size) return ips
        pos += 4
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
        qEnd += 4
        val id = query.copyOfRange(0, 2)
        val flags = byteArrayOf(0x81.toByte(), 0x80.toByte())
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

    private fun buildIpTcpPacket(
        srcIp: String,
        srcPort: Int,
        dstIp: String,
        dstPort: Int,
        seqNum: Int,
        ackNum: Int,
        flags: Byte,
        payload: ByteArray,
    ): ByteArray {
        val src = InetAddress.getByName(srcIp).address
        val dst = InetAddress.getByName(dstIp).address
        val tcpLen = 20 + payload.size
        val totalLen = 20 + tcpLen
        val buffer = ByteBuffer.allocate(totalLen).order(ByteOrder.BIG_ENDIAN)
        buffer.put(0x45.toByte())
        buffer.put(0)
        buffer.putShort(totalLen.toShort())
        buffer.putShort(0)
        buffer.putShort(0)
        buffer.put(64)
        buffer.put(IPPROTO_TCP.toByte())
        buffer.putShort(0)
        buffer.put(src)
        buffer.put(dst)
        buffer.putShort((srcPort and 0xFFFF).toShort())
        buffer.putShort((dstPort and 0xFFFF).toShort())
        buffer.putInt(seqNum)
        buffer.putInt(ackNum)
        buffer.put(0x50.toByte())
        buffer.put(flags)
        buffer.putShort(8192.toShort())
        buffer.putShort(0)
        buffer.putShort(0)
        buffer.put(payload)
        val ipChecksum = ipChecksum(buffer.array(), 0, 20)
        buffer.putShort(10, (ipChecksum and 0xFFFF).toShort())
        return buffer.array()
    }
}
