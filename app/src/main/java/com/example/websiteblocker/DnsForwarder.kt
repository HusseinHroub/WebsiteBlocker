package com.example.websiteblocker

import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress

class DnsForwarder(private val upstreamDns: InetAddress) {

    // Forward raw DNS query bytes to upstream, return raw response bytes
    fun forward(queryPayload: ByteArray): ByteArray? {
        return try {
            DatagramSocket().use { socket ->
                socket.soTimeout = 3000
                val request = DatagramPacket(queryPayload, queryPayload.size, upstreamDns, 53)
                socket.send(request)
                val responseBuffer = ByteArray(512)
                val response = DatagramPacket(responseBuffer, responseBuffer.size)
                socket.receive(response)
                responseBuffer.copyOf(response.length)
            }
        } catch (e: Exception) {
            null // timeout or network error — caller will drop the query
        }
    }
}
