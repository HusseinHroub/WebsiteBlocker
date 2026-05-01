package com.example.websiteblocker

import java.net.InetAddress
import java.nio.ByteBuffer

/**
 * Processes raw IP packets from the TUN interface.
 * - DNS packets (UDP port 53): inspect hostname, block or forward
 * - All other packets: return as-is (transparent passthrough)
 */
class DnsPacketProcessor(
    private val blocklist: BlocklistRepository,
    private val forwarder: DnsForwarder
) {

    // Returns the packet to write back into the TUN, or null to drop
    fun process(packet: ByteArray): ByteArray? {
        val buf = ByteBuffer.wrap(packet)

        // IP header: version/IHL in first byte
        val versionIhl = buf.get(0).toInt() and 0xFF
        val ipVersion = versionIhl shr 4
        if (ipVersion != 4) return packet // pass through IPv6 as-is

        val ihl = (versionIhl and 0x0F) * 4 // IP header length in bytes
        val protocol = buf.get(9).toInt() and 0xFF
        if (protocol != 17) return packet // not UDP, pass through

        val srcIp = ByteArray(4).also { buf.position(12); buf.get(it) }
        val dstIp = ByteArray(4).also { buf.get(it) }

        val udpOffset = ihl
        val srcPort = buf.getShort(udpOffset).toInt() and 0xFFFF
        val dstPort = buf.getShort(udpOffset + 2).toInt() and 0xFFFF
        if (dstPort != 53) return packet // not DNS, pass through

        val dnsOffset = udpOffset + 8
        val dnsPayload = packet.copyOfRange(dnsOffset, packet.size)

        val hostname = parseDnsQueryHostname(dnsPayload) ?: return null

        return if (isBlocked(hostname)) {
            buildNxdomainResponse(packet, srcIp, dstIp, srcPort, dstPort, dnsPayload)
        } else {
            val dnsResponse = forwarder.forward(dnsPayload) ?: return null
            buildUdpIpPacket(dstIp, srcIp, dstPort, srcPort, dnsResponse)
        }
    }

    private fun isBlocked(hostname: String): Boolean {
        val blocked = blocklist.getAll()
        // Check exact match and parent domain match (e.g. "instagram.com" blocks "www.instagram.com")
        var domain = hostname
        while (domain.isNotEmpty()) {
            if (blocked.contains(domain)) return true
            val dot = domain.indexOf('.')
            if (dot < 0) break
            domain = domain.substring(dot + 1)
        }
        return false
    }

    // Parse the QNAME from a DNS query payload
    private fun parseDnsQueryHostname(dns: ByteArray): String? {
        return try {
            val sb = StringBuilder()
            var i = 12 // skip transaction ID (2), flags (2), counts (8)
            while (i < dns.size) {
                val len = dns[i].toInt() and 0xFF
                if (len == 0) break
                if (sb.isNotEmpty()) sb.append('.')
                sb.append(String(dns, i + 1, len))
                i += len + 1
            }
            sb.toString().lowercase().ifEmpty { null }
        } catch (e: Exception) {
            null
        }
    }

    // Build a DNS NXDOMAIN response and wrap it in UDP/IP
    private fun buildNxdomainResponse(
        originalPacket: ByteArray,
        srcIp: ByteArray, dstIp: ByteArray,
        srcPort: Int, dstPort: Int,
        dnsQuery: ByteArray
    ): ByteArray {
        // Copy query, set QR=1 (response) and RCODE=3 (NXDOMAIN), zero answer counts
        val dnsResponse = dnsQuery.copyOf()
        dnsResponse[2] = (dnsResponse[2].toInt() or 0x80).toByte() // QR = 1
        dnsResponse[3] = (dnsResponse[3].toInt() and 0xF0 or 0x03).toByte() // RCODE = 3
        // ANCOUNT, NSCOUNT, ARCOUNT already 0 in a query copy
        return buildUdpIpPacket(dstIp, srcIp, dstPort, srcPort, dnsResponse)
    }

    // Wrap DNS payload in a UDP packet, then in an IPv4 packet
    private fun buildUdpIpPacket(
        srcIp: ByteArray, dstIp: ByteArray,
        srcPort: Int, dstPort: Int,
        payload: ByteArray
    ): ByteArray {
        val udpLength = 8 + payload.size
        val totalLength = 20 + udpLength
        val buf = ByteBuffer.allocate(totalLength)

        // IPv4 header
        buf.put(0x45.toByte())           // version=4, IHL=5
        buf.put(0)                        // DSCP/ECN
        buf.putShort(totalLength.toShort())
        buf.putShort(0)                   // ID
        buf.putShort(0x4000.toShort())    // flags: don't fragment
        buf.put(64)                       // TTL
        buf.put(17)                       // protocol: UDP
        buf.putShort(0)                   // checksum placeholder
        buf.put(srcIp)
        buf.put(dstIp)

        // IP checksum
        val ipChecksum = checksum(buf.array(), 0, 20)
        buf.putShort(10, ipChecksum.toShort())

        // UDP header
        buf.putShort(srcPort.toShort())
        buf.putShort(dstPort.toShort())
        buf.putShort(udpLength.toShort())
        buf.putShort(0)                   // UDP checksum (optional for IPv4)

        buf.put(payload)
        return buf.array()
    }

    private fun checksum(data: ByteArray, offset: Int, length: Int): Int {
        var sum = 0
        var i = offset
        while (i < offset + length - 1) {
            sum += ((data[i].toInt() and 0xFF) shl 8) or (data[i + 1].toInt() and 0xFF)
            i += 2
        }
        if ((length and 1) != 0) sum += (data[offset + length - 1].toInt() and 0xFF) shl 8
        while (sum shr 16 != 0) sum = (sum and 0xFFFF) + (sum shr 16)
        return sum.inv() and 0xFFFF
    }
}
