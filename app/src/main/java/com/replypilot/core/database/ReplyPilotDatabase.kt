package com.replypilot.core.database

import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(
    entities = [
        ScheduleEntity::class, ExecutionEntity::class, RuleEntity::class, TemplateEntity::class,
        ContactEntity::class, ContactIdentityEntity::class, SequenceEntity::class, EnrollmentEntity::class,
        ActivityEntity::class, ReplyRecordEntity::class, FingerprintEntity::class, ConversationStateEntity::class,
    ],
    version = ReplyPilotDatabase.VERSION,
    exportSchema = true,
)
abstract class ReplyPilotDatabase : RoomDatabase() {
    abstract fun schedules(): ScheduleDao
    abstract fun executions(): ExecutionDao
    abstract fun rules(): RuleDao
    abstract fun templates(): TemplateDao
    abstract fun contacts(): ContactDao
    abstract fun sequences(): SequenceDao
    abstract fun activity(): ActivityDao
    abstract fun conversations(): ConversationDao

    companion object {
        const val NAME = "replypilot.db"
        const val VERSION = 3

        /**
         * Every schema change bumps [VERSION] and adds an explicit migration here; exported schemas live in
         * app/schemas and are verified by MigrationTest. Destructive fallback is deliberately never enabled:
         * losing a user's schedules silently is worse than failing loudly in development.
         */
        /** 1 → 2: executions remember whether the recipient is a person or a group chat. */
        val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE executions ADD COLUMN recipientKind TEXT NOT NULL DEFAULT 'INDIVIDUAL'")
            }
        }

        val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE executions ADD COLUMN attachmentsJson TEXT DEFAULT NULL")
            }
        }

        val MIGRATIONS: Array<Migration> = arrayOf(MIGRATION_1_2, MIGRATION_2_3)
    }
}
