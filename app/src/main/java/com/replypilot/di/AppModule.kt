package com.replypilot.di

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.room.Room
import com.replypilot.core.accessibility.AutoSendBridge
import com.replypilot.core.assistant.ConfiguredAssistant
import com.replypilot.core.attachments.AttachmentStore
import com.replypilot.core.coroutines.ApplicationScope
import com.replypilot.core.database.ReplyPilotDatabase
import com.replypilot.core.datastore.AppPreferencesRepository
import com.replypilot.core.datastore.DataStoreSettingsRepository
import com.replypilot.core.datastore.createPreferencesDataStore
import com.replypilot.core.notifications.ReplyActionRegistry
import com.replypilot.core.permissions.PlatformStatus
import com.replypilot.core.scheduling.AndroidWakeScheduler
import com.replypilot.data.repositories.RoomActivityRepository
import com.replypilot.data.repositories.RoomContactRepository
import com.replypilot.data.repositories.RoomConversationRepository
import com.replypilot.data.repositories.RoomExecutionRepository
import com.replypilot.data.repositories.RoomRuleRepository
import com.replypilot.data.repositories.RoomScheduleRepository
import com.replypilot.data.repositories.RoomSequenceRepository
import com.replypilot.data.repositories.RoomTemplateRepository
import com.replypilot.domain.assistant.MessageAssistant
import com.replypilot.domain.automation.ActivityLogger
import com.replypilot.domain.automation.IncomingMessageHandler
import com.replypilot.domain.automation.RuleContextBuilder
import com.replypilot.domain.automation.SequenceService
import com.replypilot.domain.common.Clock
import com.replypilot.domain.common.IdGenerator
import com.replypilot.domain.common.SystemClock
import com.replypilot.domain.common.UuidGenerator
import com.replypilot.domain.messaging.AdapterRegistry
import com.replypilot.domain.messaging.StaticAdapterRegistry
import com.replypilot.domain.repository.ActivityRepository
import com.replypilot.domain.repository.ContactRepository
import com.replypilot.domain.repository.ConversationRepository
import com.replypilot.domain.repository.ExecutionRepository
import com.replypilot.domain.repository.RuleRepository
import com.replypilot.domain.repository.ScheduleRepository
import com.replypilot.domain.repository.SequenceRepository
import com.replypilot.domain.repository.SettingsRepository
import com.replypilot.domain.repository.TemplateRepository
import com.replypilot.domain.repository.WakeScheduler
import com.replypilot.domain.scheduling.QueueActions
import com.replypilot.domain.scheduling.QueueProcessor
import com.replypilot.domain.scheduling.ScheduleMaterializer
import com.replypilot.domain.scheduling.ScheduleService
import com.replypilot.integrations.demo.DemoAdapter
import com.replypilot.integrations.device.DeviceChannels
import com.replypilot.integrations.device.DeviceNotificationAdapter
import com.replypilot.integrations.email.EmailAdapter
import com.replypilot.integrations.whatsapp.WhatsAppBusinessAdapter
import dagger.Binds
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

@Module
@InstallIn(SingletonComponent::class)
abstract class RepositoryBindings {
    @Binds abstract fun schedules(impl: RoomScheduleRepository): ScheduleRepository
    @Binds abstract fun executions(impl: RoomExecutionRepository): ExecutionRepository
    @Binds abstract fun rules(impl: RoomRuleRepository): RuleRepository
    @Binds abstract fun templates(impl: RoomTemplateRepository): TemplateRepository
    @Binds abstract fun contacts(impl: RoomContactRepository): ContactRepository
    @Binds abstract fun sequences(impl: RoomSequenceRepository): SequenceRepository
    @Binds abstract fun activity(impl: RoomActivityRepository): ActivityRepository
    @Binds abstract fun conversations(impl: RoomConversationRepository): ConversationRepository
    @Binds abstract fun settings(impl: DataStoreSettingsRepository): SettingsRepository
    @Binds abstract fun wake(impl: AndroidWakeScheduler): WakeScheduler
    @Binds abstract fun assistant(impl: ConfiguredAssistant): MessageAssistant
}

@Module
@InstallIn(SingletonComponent::class)
object PlatformModule {
    @Provides @Singleton @ApplicationScope
    fun applicationScope(): CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    @Provides @Singleton
    fun database(@ApplicationContext context: Context): ReplyPilotDatabase =
        Room.databaseBuilder(context, ReplyPilotDatabase::class.java, ReplyPilotDatabase.NAME)
            .addMigrations(*ReplyPilotDatabase.MIGRATIONS)
            .build()

    @Provides @Singleton
    fun dataStore(@ApplicationContext context: Context, @ApplicationScope scope: CoroutineScope): DataStore<Preferences> =
        createPreferencesDataStore(context, CoroutineScope(scope.coroutineContext + Dispatchers.IO))

    @Provides @Singleton fun clock(): Clock = SystemClock
    @Provides @Singleton fun ids(): IdGenerator = UuidGenerator

    @Provides @Singleton
    fun adapters(
        @ApplicationContext context: Context,
        platform: PlatformStatus,
        preferences: AppPreferencesRepository,
        replies: ReplyActionRegistry,
        autoSend: AutoSendBridge,
        demo: DemoAdapter,
        email: EmailAdapter,
        business: WhatsAppBusinessAdapter,
        attachments: AttachmentStore,
    ): AdapterRegistry = StaticAdapterRegistry(
        DeviceChannels.all.map { DeviceNotificationAdapter(it, context, platform, preferences, replies, autoSend, attachments::exists) } +
            listOf(demo, email, business),
    )
}

/** Domain services are plain Kotlin; this module is the only place they meet Android. */
@Module
@InstallIn(SingletonComponent::class)
object DomainModule {
    @Provides @Singleton
    fun activityLogger(repo: ActivityRepository, clock: Clock, ids: IdGenerator) = ActivityLogger(repo, clock, ids)

    @Provides @Singleton
    fun materializer(
        schedules: ScheduleRepository, executions: ExecutionRepository, contacts: ContactRepository,
        settings: SettingsRepository, clock: Clock, ids: IdGenerator,
    ) = ScheduleMaterializer(schedules, executions, contacts, settings, clock, ids)

    @Provides @Singleton
    fun scheduleService(
        schedules: ScheduleRepository, executions: ExecutionRepository, contacts: ContactRepository,
        settings: SettingsRepository, adapters: AdapterRegistry, materializer: ScheduleMaterializer,
        activity: ActivityLogger, clock: Clock, ids: IdGenerator, wake: WakeScheduler,
    ) = ScheduleService(schedules, executions, contacts, settings, adapters, materializer, activity, clock, ids, wake)

    @Provides @Singleton
    fun queueProcessor(
        executions: ExecutionRepository, schedules: ScheduleRepository, sequences: SequenceRepository,
        contacts: ContactRepository, conversations: ConversationRepository, settings: SettingsRepository, adapters: AdapterRegistry,
        materializer: ScheduleMaterializer, activity: ActivityLogger, clock: Clock, wake: WakeScheduler,
    ) = QueueProcessor(executions, schedules, sequences, contacts, conversations, settings, adapters, materializer, activity, clock, wake)

    @Provides @Singleton
    fun queueActions(
        executions: ExecutionRepository, conversations: ConversationRepository, settings: SettingsRepository,
        activity: ActivityLogger, clock: Clock, wake: WakeScheduler,
    ) = QueueActions(executions, conversations, settings, activity, clock, wake)

    @Provides @Singleton
    fun sequenceService(
        sequences: SequenceRepository, executions: ExecutionRepository, contacts: ContactRepository,
        settings: SettingsRepository, activity: ActivityLogger, clock: Clock, ids: IdGenerator, wake: WakeScheduler,
    ) = SequenceService(sequences, executions, contacts, settings, activity, clock, ids, wake)

    @Provides @Singleton
    fun contextBuilder(
        contacts: ContactRepository, conversations: ConversationRepository, executions: ExecutionRepository,
        templates: TemplateRepository, settings: SettingsRepository, adapters: AdapterRegistry, clock: Clock,
    ) = RuleContextBuilder(contacts, conversations, executions, templates, settings, adapters, clock)

    @Provides @Singleton
    fun incomingHandler(
        rules: RuleRepository, contacts: ContactRepository, conversations: ConversationRepository,
        executions: ExecutionRepository, settings: SettingsRepository, contextBuilder: RuleContextBuilder,
        sequences: SequenceService, assistant: MessageAssistant, activity: ActivityLogger, clock: Clock,
        ids: IdGenerator, wake: WakeScheduler,
    ) = IncomingMessageHandler(rules, contacts, conversations, executions, settings, contextBuilder, sequences, assistant, activity, clock, ids, wake)
}
