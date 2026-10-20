package com.replypilot.core.coroutines

import javax.inject.Qualifier

/** Long-lived scope for work that must outlive a screen (notification handling, queue triggers). */
@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class ApplicationScope
