package com.replypilot.core

import android.content.Context
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.work.Configuration
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.testing.SynchronousExecutor
import androidx.work.testing.WorkManagerTestInitHelper
import com.replypilot.core.datastore.AppPreferencesRepository
import com.replypilot.core.permissions.PlatformStatus
import com.replypilot.core.scheduling.AndroidWakeScheduler
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class WakeSchedulerTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private lateinit var scheduler: AndroidWakeScheduler

    @Before fun setUp() {
        WorkManagerTestInitHelper.initializeTestWorkManager(context, Configuration.Builder().setExecutor(SynchronousExecutor()).build())
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val prefs = AppPreferencesRepository(PreferenceDataStoreFactory.create(scope = scope) { File(context.filesDir, "wake-${System.nanoTime()}.preferences_pb") })
        scheduler = AndroidWakeScheduler(context, PlatformStatus(context), prefs, scope)
    }

    private fun states(name: String) = WorkManager.getInstance(context).getWorkInfosForUniqueWork(name).get().map { it.state }

    @Test
    fun `a future wake arms exactly one delayed unique work request`() {
        scheduler.scheduleWake(System.currentTimeMillis() + 60_000)
        scheduler.scheduleWake(System.currentTimeMillis() + 30_000)
        val active = states(AndroidWakeScheduler.WORK_WAKE).filter { it == WorkInfo.State.ENQUEUED }
        assertEquals(1, active.size)
    }

    @Test
    fun `no pending work cancels the wake`() {
        scheduler.scheduleWake(System.currentTimeMillis() + 60_000)
        scheduler.scheduleWake(null)
        assertTrue(states(AndroidWakeScheduler.WORK_WAKE).all { it == WorkInfo.State.CANCELLED })
    }

    @Test
    fun `periodic safety net is registered once`() {
        scheduler.ensurePeriodicSafetyNet()
        scheduler.ensurePeriodicSafetyNet()
        assertEquals(1, states(AndroidWakeScheduler.WORK_PERIODIC).size)
    }
}
