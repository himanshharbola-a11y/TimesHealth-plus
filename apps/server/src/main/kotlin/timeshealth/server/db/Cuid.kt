package timeshealth.server.db

import java.net.InetAddress
import java.security.SecureRandom
import java.util.concurrent.atomic.AtomicInteger

/**
 * Prisma's `@default(cuid())`, which Prisma fills in client-side — so a row this server inserts
 * needs one from here. Same format as Prisma 6 (cuid v1): 25 characters,
 *
 *   "c" + timestamp(ms, base36) + counter(4) + fingerprint(4) + random(8)
 *
 * e.g. "cmuuy9flk0000kzlwddau2qni". Ids from both servers sort roughly by creation time and can
 * never collide in practice (the fingerprint and random parts differ per process).
 */
object Cuid {
    private const val BASE = 36
    private const val BLOCK = 4
    private val DISCRETE = Math.pow(BASE.toDouble(), BLOCK.toDouble()).toInt() // 36^4
    private val random = SecureRandom()
    private val counter = AtomicInteger(random.nextInt(DISCRETE))

    private val fingerprint: String by lazy {
        val pid = ProcessHandle.current().pid()
        val host = runCatching { InetAddress.getLocalHost().hostName }.getOrDefault("timeshealth")
        val hostId = host.fold(host.length + BASE) { acc, ch -> acc + ch.code }
        pad(pid.toString(BASE), 2) + pad(hostId.toString(BASE), 2)
    }

    fun next(): String {
        val timestamp = System.currentTimeMillis().toString(BASE)
        val count = pad(Math.floorMod(counter.getAndIncrement(), DISCRETE).toString(BASE), BLOCK)
        return "c" + timestamp + count + fingerprint + randomBlock() + randomBlock()
    }

    private fun randomBlock(): String = pad(random.nextInt(DISCRETE).toString(BASE), BLOCK)

    /** Left-pads with zeros and keeps the LAST [size] characters, like cuid's `pad`. */
    private fun pad(s: String, size: Int): String = ("000000000$s").takeLast(size)
}
