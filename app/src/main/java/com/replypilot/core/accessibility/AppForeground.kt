package com.replypilot.core.accessibility

import android.app.Activity
import android.app.Application
import android.os.Bundle
import java.util.concurrent.atomic.AtomicInteger
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Knows whether ReplyPilot itself is on screen. The auto-send service is limited to WhatsApp's windows,
 * so it can't see other apps; this is how it decides whether to return the user to ReplyPilot after a send.
 */
@Singleton
class AppForeground @Inject constructor() : Application.ActivityLifecycleCallbacks {
    private val started = AtomicInteger(0)

    /** Called when ReplyPilot comes back to the front; used to end a chat pick the user abandoned. */
    @Volatile
    var onReturn: (() -> Unit)? = null

    val isForeground: Boolean get() = started.get() > 0

    fun register(application: Application) = application.registerActivityLifecycleCallbacks(this)

    override fun onActivityStarted(activity: Activity) {
        if (started.getAndIncrement() == 0) onReturn?.invoke()
    }
    override fun onActivityStopped(activity: Activity) { started.updateAndGet { (it - 1).coerceAtLeast(0) } }
    override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) = Unit
    override fun onActivityResumed(activity: Activity) = Unit
    override fun onActivityPaused(activity: Activity) = Unit
    override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) = Unit
    override fun onActivityDestroyed(activity: Activity) = Unit
}
