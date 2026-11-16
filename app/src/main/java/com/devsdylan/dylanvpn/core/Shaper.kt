package com.devsdylan.dylanvpn.core

import java.io.InputStream
import java.io.OutputStream
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.Executors

/** Limites de débit d'une config, en octets par seconde (0 = illimité). */
data class Limits(
    val downBps: Long = 0,
    val upBps: Long = 0,
    val connBps: Long = 0,
    val burst: Long = 0
) {
    val active: Boolean get() = downBps > 0 || upBps > 0 || connBps > 0
}

/** Seau à jetons : [reserve] retourne le temps d'attente (en ms) avant de pouvoir envoyer n octets. */
class TokenBucket(private val ratePerSecond: Long, capacityBytes: Long) {
    private val capacity = maxOf(capacityBytes, 4096L).toDouble()
    private var tokens = capacity
    private var last = System.nanoTime()

    @Synchronized
    fun reserve(n: Int): Long {
        val now = System.nanoTime()
        tokens = minOf(capacity, tokens + (now - last) / 1e9 * ratePerSecond)
        last = now
        tokens -= n
        if (tokens >= 0) return 0
        return (-tokens / ratePerSecond * 1000).toLong() + 1
    }
}

/**
 * Relais SOCKS5 local qui bride le débit. Xray y passe (dialerProxy) pour joindre le serveur :
 * tout ce qui est chiffré vers le serveur traverse ce relais et peut donc être ralenti.
 */
class Shaper(private val limits: Limits) {
    private var server: ServerSocket? = null
    private val pool = Executors.newCachedThreadPool { r ->
        Thread(r).also { it.isDaemon = true }
    }
    private val globalDown = if (limits.downBps > 0) TokenBucket(limits.downBps, limits.downBps / 4) else null
    private val globalUp = if (limits.upBps > 0) TokenBucket(limits.upBps, limits.upBps / 4) else null

    @Volatile
    private var running = false

    /** Démarre le relais et retourne le port local. */
    fun start(): Int {
        val s = ServerSocket(0, 64, InetAddress.getByName("127.0.0.1"))
        server = s
        running = true
        pool.execute {
            while (running) {
                try {
                    val client = s.accept()
                    pool.execute { handle(client) }
                } catch (e: Exception) {
                    break
                }
            }
        }
        return s.localPort
    }

    fun stop() {
        running = false
        try {
            server?.close()
        } catch (e: Exception) {
            // déjà fermé
        }
        pool.shutdownNow()
    }

    private fun handle(client: Socket) {
        var remote: Socket? = null
        try {
            client.tcpNoDelay = true
            val cin = client.getInputStream()
            val cout = client.getOutputStream()

            // Négociation SOCKS5 sans authentification
            if (cin.read() != 5) return
            val methods = cin.read()
            if (methods < 0) return
            skipFully(cin, methods)
            cout.write(byteArrayOf(5, 0))
            cout.flush()

            // Requête CONNECT
            if (cin.read() != 5) return
            val cmd = cin.read()
            cin.read()
            val type = cin.read()
            val host = when (type) {
                1 -> InetAddress.getByAddress(readFully(cin, 4)).hostAddress
                4 -> InetAddress.getByAddress(readFully(cin, 16)).hostAddress
                3 -> String(readFully(cin, cin.read()), Charsets.UTF_8)
                else -> return
            }
            val portBytes = readFully(cin, 2)
            val port = ((portBytes[0].toInt() and 0xFF) shl 8) or (portBytes[1].toInt() and 0xFF)
            if (cmd != 1) {
                cout.write(byteArrayOf(5, 7, 0, 1, 0, 0, 0, 0, 0, 0))
                return
            }
            val r = Socket()
            remote = r
            try {
                r.connect(InetSocketAddress(host, port), 10_000)
            } catch (e: Exception) {
                cout.write(byteArrayOf(5, 5, 0, 1, 0, 0, 0, 0, 0, 0))
                return
            }
            r.tcpNoDelay = true
            cout.write(byteArrayOf(5, 0, 0, 1, 0, 0, 0, 0, 0, 0))
            cout.flush()

            val connBucket = if (limits.connBps > 0) TokenBucket(limits.connBps, limits.burst) else null
            val done = r
            // montant : client -> serveur
            pool.execute {
                pipe(cin, done.getOutputStream(), globalUp, null)
                closeQuietly(client)
                closeQuietly(done)
            }
            // descendant : serveur -> client
            pipe(done.getInputStream(), cout, globalDown, connBucket)
        } catch (e: Exception) {
            // connexion interrompue
        } finally {
            closeQuietly(client)
            closeQuietly(remote)
        }
    }

    private fun pipe(input: InputStream, output: OutputStream, global: TokenBucket?, perConn: TokenBucket?) {
        val buffer = ByteArray(16 * 1024)
        try {
            while (true) {
                val n = input.read(buffer)
                if (n < 0) break
                var wait = 0L
                if (global != null) wait = maxOf(wait, global.reserve(n))
                if (perConn != null) wait = maxOf(wait, perConn.reserve(n))
                if (wait > 0) Thread.sleep(wait)
                output.write(buffer, 0, n)
                output.flush()
            }
        } catch (e: Exception) {
            // fin de flux
        }
    }

    private fun readFully(input: InputStream, n: Int): ByteArray {
        val out = ByteArray(n)
        var off = 0
        while (off < n) {
            val r = input.read(out, off, n - off)
            if (r < 0) throw java.io.IOException("flux fermé")
            off += r
        }
        return out
    }

    private fun skipFully(input: InputStream, n: Int) {
        readFully(input, n)
    }

    private fun closeQuietly(s: Socket?) {
        try {
            s?.close()
        } catch (e: Exception) {
            // ignoré
        }
    }
}
