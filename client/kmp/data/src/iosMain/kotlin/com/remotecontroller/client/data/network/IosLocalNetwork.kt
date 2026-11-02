@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package com.remotecontroller.client.data.network

import com.remotecontroller.client.data.log.logInfo
import com.remotecontroller.client.data.log.logWarn
import kotlin.concurrent.Volatile
import kotlinx.cinterop.ExperimentalForeignApi
import platform.Foundation.NSNotificationCenter
import platform.Foundation.NSOperationQueue
import platform.Foundation.NSURL
import platform.Network.nw_browse_descriptor_create_bonjour_service
import platform.Network.nw_browser_cancel
import platform.Network.nw_browser_create
import platform.Network.nw_browser_set_queue
import platform.Network.nw_browser_set_state_changed_handler
import platform.Network.nw_browser_start
import platform.Network.nw_browser_state_cancelled
import platform.Network.nw_browser_state_failed
import platform.Network.nw_browser_state_ready
import platform.Network.nw_browser_state_t
import platform.Network.nw_browser_state_waiting
import platform.Network.nw_browser_t
import platform.Network.nw_error_domain_dns
import platform.Network.nw_error_get_error_code
import platform.Network.nw_error_get_error_domain
import platform.Network.nw_error_t
import platform.UIKit.UIApplication
import platform.UIKit.UIApplicationDidBecomeActiveNotification
import platform.UIKit.UIApplicationOpenSettingsURLString
import platform.darwin.dispatch_get_main_queue

/**
 * iOS has no synchronous local-network permission API. Browsing a declared Bonjour
 * service makes the system show the prompt; a DNS policy-denied error means the user
 * refused. Until that answer arrives, [isGranted] stays false so connect waits.
 */
object IosLocalNetwork {
    const val PERMISSION = "NSLocalNetworkUsageDescription"
    private const val BONJOUR_SERVICE = "_remotectrl._tcp"
    private const val DNS_POLICY_DENIED = -65570
    private const val DNS_NOT_PERMITTED = -65571
    private const val TAG = "LocalNetwork"

    @Volatile
    private var granted = false

    @Volatile
    private var resolved = false

    private var permanentlyDenied = false
    private var generation = 0
    private var browser: nw_browser_t = null
    private var stateHandler: ((nw_browser_state_t, nw_error_t) -> Unit)? = null
    private val listeners = mutableListOf<(Boolean) -> Unit>()

    init {
        NSNotificationCenter.defaultCenter.addObserverForName(
            name = UIApplicationDidBecomeActiveNotification,
            `object` = null,
            queue = NSOperationQueue.mainQueue,
            usingBlock = { onBecomeActive() },
        )
    }

    fun isGranted(): Boolean = resolved && granted

    fun addListener(listener: (Boolean) -> Unit) {
        listeners += listener
        if (resolved) listener(granted)
    }

    fun removeListener(listener: (Boolean) -> Unit) {
        listeners -= listener
    }

    fun request() {
        if (permanentlyDenied) {
            openSettings()
            restartBrowser()
            return
        }
        if (browser == null) startBrowser()
    }

    private fun onBecomeActive() {
        if (permanentlyDenied) restartBrowser()
    }

    private fun restartBrowser() {
        browser?.let { nw_browser_cancel(it) }
        browser = null
        startBrowser()
    }

    private fun startBrowser() {
        val ticket = ++generation
        val descriptor = nw_browse_descriptor_create_bonjour_service(BONJOUR_SERVICE, null)
        val next = descriptor?.let { nw_browser_create(it, null) }
        if (next == null) {
            logWarn(TAG, "could not start the local-network prompt", null)
            publish(granted = true)
            return
        }
        browser = next
        val handler: (nw_browser_state_t, nw_error_t) -> Unit = { state, error ->
            if (ticket == generation) onBrowserState(state, error)
        }
        stateHandler = handler
        nw_browser_set_queue(next, dispatch_get_main_queue())
        nw_browser_set_state_changed_handler(next, handler)
        nw_browser_start(next)
    }

    private fun onBrowserState(state: nw_browser_state_t, error: nw_error_t) {
        when (state) {
            nw_browser_state_ready -> publish(granted = true)
            nw_browser_state_waiting -> {
                if (isPolicyDenied(error)) {
                    publish(granted = false)
                } else {
                    logInfo(TAG, "waiting for local network permission ${describe(error)}")
                }
            }
            nw_browser_state_failed -> {
                if (isPolicyDenied(error)) {
                    publish(granted = false)
                } else {
                    logWarn(TAG, "local network browse failed ${describe(error)}; not blocking connect", null)
                    publish(granted = true)
                }
            }
            nw_browser_state_cancelled -> Unit
            else -> Unit
        }
    }

    private fun publish(granted: Boolean) {
        val changed = !resolved || this.granted != granted
        this.granted = granted
        resolved = true
        permanentlyDenied = !granted
        if (!changed) return
        logInfo(TAG, if (granted) "local network allowed" else "local network denied")
        listeners.toList().forEach { it(granted) }
    }

    private fun isPolicyDenied(error: nw_error_t): Boolean {
        if (error == null) return false
        if (nw_error_get_error_domain(error) != nw_error_domain_dns) return false
        val code = nw_error_get_error_code(error)
        return code == DNS_POLICY_DENIED || code == DNS_NOT_PERMITTED
    }

    private fun describe(error: nw_error_t): String {
        if (error == null) return "no error"
        return "domain=${nw_error_get_error_domain(error)} code=${nw_error_get_error_code(error)}"
    }

    private fun openSettings() {
        val url = NSURL.URLWithString(UIApplicationOpenSettingsURLString) ?: return
        UIApplication.sharedApplication.openURL(
            url,
            options = emptyMap<Any?, Any>(),
            completionHandler = null,
        )
    }
}
