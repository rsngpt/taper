package dev.taper.queue

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/**
 * Drains the [SyncQueue] whenever the default network comes (back) up.
 *
 * Uses [ConnectivityManager.registerDefaultNetworkCallback] — available since
 * API 24, which is the reason for this library's minSdk (see README).
 * Overlapping triggers are safe: [SyncQueue.drain] serialises on a mutex.
 *
 * The drain runs in the [scope] YOU provide. Syncers almost always do blocking
 * network I/O, so that scope must use an I/O-capable dispatcher (e.g.
 * `Dispatchers.IO`) — a main-thread scope dies with `NetworkOnMainThreadException`
 * inside the syncer, which the classifier will treat as a transient failure
 * (data stays queued, but nothing ever syncs).
 */
class ConnectivityDrainTrigger(
    context: Context,
    private val queue: SyncQueue,
    private val syncer: Syncer,
    private val scope: CoroutineScope,
    private val onDrained: (DrainReport) -> Unit = {},
) {
    private val connectivityManager =
        context.applicationContext.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager

    private val callback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) {
            scope.launch {
                onDrained(queue.drain(syncer))
            }
        }
    }

    private var started = false

    fun start() {
        if (started) return
        connectivityManager.registerDefaultNetworkCallback(callback)
        started = true
    }

    fun stop() {
        if (!started) return
        connectivityManager.unregisterNetworkCallback(callback)
        started = false
    }
}
