package com.replypilot.integrations

import android.app.Notification
import android.app.PendingIntent
import android.app.RemoteInput
import android.content.Context
import android.content.Intent
import android.graphics.drawable.Icon
import android.provider.Settings
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.replypilot.core.datastore.AppPreferencesRepository
import com.replypilot.core.notifications.ReplyActionRegistry
import com.replypilot.core.permissions.PlatformStatus
import com.replypilot.core.security.CredentialStore
import com.replypilot.domain.messaging.OutboundMessage
import com.replypilot.domain.messaging.SendOutcome
import com.replypilot.domain.model.Attachment
import com.replypilot.domain.model.AvailabilityState
import com.replypilot.domain.model.ChannelCapability
import com.replypilot.domain.model.ChannelId
import com.replypilot.domain.model.FailureCode
import com.replypilot.domain.model.ManualHandoff
import com.replypilot.domain.model.Recipient
import com.replypilot.integrations.demo.DemoAdapter
import com.replypilot.integrations.demo.DemoControls
import com.replypilot.integrations.demo.DemoOutcome
import com.replypilot.integrations.device.DeviceChannels
import com.replypilot.integrations.device.DeviceNotificationAdapter
import com.replypilot.integrations.email.EmailAdapter
import com.replypilot.integrations.whatsapp.WhatsAppBusinessAdapter
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class AdapterTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private lateinit var preferences: AppPreferencesRepository
    private val registry by lazy { ReplyActionRegistry(context) }
    private val platform by lazy { PlatformStatus(context) }

    @Before fun setUp() {
        val file = File(context.filesDir, "test-${System.nanoTime()}.preferences_pb")
        preferences = AppPreferencesRepository(
            PreferenceDataStoreFactory.create(scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)) { file },
        )
    }

    private fun grantNotificationAccess() {
        Settings.Secure.putString(context.contentResolver, "enabled_notification_listeners", "${context.packageName}/.core.notifications.MessageNotificationListener")
    }

    private fun message(replyTarget: String? = null) =
        OutboundMessage("e1", "k1", Recipient("+2348000000001", "Ada"), "Hello Ada", replyTarget)

    private fun whatsapp() = DeviceNotificationAdapter(DeviceChannels.WHATSAPP, context, platform, preferences, registry)

    @Test
    fun `every adapter declares its capabilities honestly`() {
        val device = whatsapp().descriptor
        assertTrue("WhatsApp can send unattended through the optional Auto-send service", device.supports(ChannelCapability.SEND_MESSAGE))
        assertTrue(device.supports(ChannelCapability.AUTO_REPLY))
        assertFalse(device.supports(ChannelCapability.DELIVERY_STATUS))
        val telegram = DeviceNotificationAdapter(DeviceChannels.TELEGRAM, context, platform, preferences, registry).descriptor
        assertFalse("other device channels can't start conversations unattended", telegram.supports(ChannelCapability.SEND_MESSAGE))

        val email = EmailAdapter().descriptor
        assertEquals(setOf(ChannelCapability.MANUAL_HANDOFF, ChannelCapability.ATTACHMENTS), email.capabilities)
        assertTrue("device channels share files through the app's share screen", telegram.supports(ChannelCapability.ATTACHMENTS))
        assertFalse("the business relay doesn't upload media yet", WhatsAppBusinessAdapter(preferences, CredentialStore(context)).descriptor.supports(ChannelCapability.ATTACHMENTS))

        val business = WhatsAppBusinessAdapter(preferences, CredentialStore(context)).descriptor
        assertTrue(business.supports(ChannelCapability.SEND_MESSAGE))
        assertTrue(business.supports(ChannelCapability.DELIVERY_STATUS))
        assertFalse(business.supports(ChannelCapability.AUTO_REPLY))
    }

    @Test
    fun `a missing attached file fails the send instead of sending text alone`() = runTest {
        val gone = Attachment("a1", "flyer.jpg", "image/jpeg", 10, "/nowhere/flyer.jpg")
        val outcome = whatsapp().send(message().copy(attachments = listOf(gone)))
        assertEquals(FailureCode.ATTACHMENT_MISSING, (outcome as SendOutcome.Failed).code)
    }

    @Test
    fun `files become a share handoff addressed to the person`() {
        val file = Attachment("a1", "flyer.jpg", "image/jpeg", 10, File(context.filesDir, "flyer.jpg").path)
        val handoff = whatsapp().handoffFor(message().copy(attachments = listOf(file)))
        assertEquals(ManualHandoff.Kind.SHARE_FILES, handoff.kind)
        assertEquals(listOf(file), handoff.attachments)
        assertEquals("+2348000000001", handoff.recipientAddress)
    }

    @Test
    fun `scheduled WhatsApp send without Auto-send becomes a manual handoff`() = runTest {
        val outcome = whatsapp().send(message())
        assertTrue(outcome is SendOutcome.NeedsManualAction)
        val handoff = (outcome as SendOutcome.NeedsManualAction).handoff
        assertEquals(ManualHandoff.Kind.OPEN_APP_PREFILLED, handoff.kind)
        assertTrue(requireNotNull(handoff.uri).startsWith("https://wa.me/2348000000001?text="))
    }

    @Test
    fun `reply without notification access reports a permission failure`() = runTest {
        Settings.Secure.putString(context.contentResolver, "enabled_notification_listeners", "")
        val outcome = whatsapp().send(message(replyTarget = "com.whatsapp|Ada"))
        assertEquals(FailureCode.PERMISSION_MISSING, (outcome as SendOutcome.Failed).code)
    }

    @Test
    fun `reply goes through the registered notification action`() = runTest {
        grantNotificationAccess()
        val pending = PendingIntent.getBroadcast(context, 0, Intent("reply"), PendingIntent.FLAG_MUTABLE)
        val input = RemoteInput.Builder("reply_text").build()
        val action = Notification.Action.Builder(Icon.createWithResource(context, android.R.drawable.ic_menu_send), "Reply", pending)
            .addRemoteInput(input).build()
        registry.register("com.whatsapp|Ada", action, arrayOf(input), System.currentTimeMillis())

        val outcome = whatsapp().send(message(replyTarget = "com.whatsapp|Ada"))
        assertTrue(outcome is SendOutcome.Submitted)
        assertEquals("submitted, not delivered: no provider reference", null, (outcome as SendOutcome.Submitted).providerRef)
    }

    @Test
    fun `reply to a dismissed notification fails permanently with a handoff code`() = runTest {
        grantNotificationAccess()
        val outcome = whatsapp().send(message(replyTarget = "com.whatsapp|Gone"))
        assertEquals(FailureCode.NO_REPLY_TARGET, (outcome as SendOutcome.Failed).code)
        assertTrue(FailureCode.NO_REPLY_TARGET.permanent)
    }

    @Test
    fun `device channel status explains what is missing`() = runTest {
        val status = whatsapp().status()
        assertEquals(AvailabilityState.APP_NOT_INSTALLED, status.availability)
    }

    @Test
    fun `business relay without configuration fails as not configured`() = runTest {
        val adapter = WhatsAppBusinessAdapter(preferences, CredentialStore(context))
        assertEquals(AvailabilityState.NOT_CONFIGURED, adapter.status().availability)
        assertEquals(FailureCode.NOT_CONFIGURED, (adapter.send(message()) as SendOutcome.Failed).code)
    }

    @Test
    fun `demo adapter marks results simulated and honours the chosen outcome once`() = runTest {
        val controls = DemoControls()
        val demo = DemoAdapter(controls)
        assertTrue((demo.send(message()) as SendOutcome.Submitted).simulated)
        controls.set(DemoOutcome.FAIL_PERMANENT)
        assertEquals(FailureCode.PROVIDER_REJECTED, (demo.send(message()) as SendOutcome.Failed).code)
        assertTrue("one-shot outcomes reset", demo.send(message()) is SendOutcome.Submitted)
    }

    @Test
    fun `email is manual only and composes a mailto`() = runTest {
        val outcome = EmailAdapter().send(OutboundMessage("e", "k", Recipient("ada@example.com", "Ada"), "Hello"))
        val handoff = (outcome as SendOutcome.NeedsManualAction).handoff
        assertEquals(ManualHandoff.Kind.COMPOSE_EMAIL, handoff.kind)
        assertTrue(requireNotNull(handoff.uri).startsWith("mailto:ada%40example.com"))
    }

    @Test
    fun `channel ids are unique across adapters`() {
        val ids = DeviceChannels.all.map { it.channel } + listOf(ChannelId.DEMO, ChannelId.EMAIL, ChannelId.WHATSAPP_BUSINESS)
        assertEquals(ChannelId.entries.toSet(), ids.toSet())
    }
}
