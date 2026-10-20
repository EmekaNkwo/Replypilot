package com.replypilot.data

import androidx.room.testing.MigrationTestHelper
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.replypilot.core.database.ReplyPilotDatabase
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Validates the exported schema and every registered migration. When VERSION is bumped, add a
 * `runMigrationsAndValidate` call per step here — the exported JSON for each version must be committed.
 */
@RunWith(AndroidJUnit4::class)
class MigrationTest {
    @get:Rule
    val helper = MigrationTestHelper(InstrumentationRegistry.getInstrumentation(), ReplyPilotDatabase::class.java)

    @Test
    fun `current schema matches the exported schema`() {
        helper.createDatabase(DB, ReplyPilotDatabase.VERSION).close()
        helper.runMigrationsAndValidate(DB, ReplyPilotDatabase.VERSION, true, *ReplyPilotDatabase.MIGRATIONS).close()
    }

    @Test
    fun `version 1 executions keep their data and default to individual recipients`() {
        helper.createDatabase(DB_1_2, 1).apply {
            execSQL(
                "INSERT INTO executions (id, idempotencyKey, source, sourceId, occurrenceIndex, channel, recipientAddress, recipientName, " +
                    "recipientContactId, body, plannedAt, expiresAt, status, attempts, maxAttempts, lastAttemptAt, nextAttemptAt, failureCode, " +
                    "statusReason, providerRef, handoffJson, replyTarget, quietHours, simulated, createdAt, updatedAt) VALUES " +
                    "('e1','k1','SCHEDULED','s1',0,'DEMO','ada','Ada',NULL,'Hi',1,NULL,'SCHEDULED',0,1,NULL,1,NULL,NULL,NULL,NULL,NULL,'DEFER',0,0,0)",
            )
            close()
        }
        val db = helper.runMigrationsAndValidate(DB_1_2, 2, true, ReplyPilotDatabase.MIGRATION_1_2)
        db.query("SELECT recipientKind, body FROM executions WHERE id = 'e1'").use { cursor ->
            cursor.moveToFirst()
            assertEquals("INDIVIDUAL", cursor.getString(0))
            assertEquals("Hi", cursor.getString(1))
        }
        db.close()
    }

    @Test
    fun `version 2 executions gain an empty attachments column`() {
        helper.createDatabase(DB_2_3, 2).apply {
            execSQL(
                "INSERT INTO executions (id, idempotencyKey, source, sourceId, occurrenceIndex, channel, recipientAddress, recipientName, " +
                    "recipientContactId, recipientKind, body, plannedAt, expiresAt, status, attempts, maxAttempts, lastAttemptAt, nextAttemptAt, " +
                    "failureCode, statusReason, providerRef, handoffJson, replyTarget, quietHours, simulated, createdAt, updatedAt) VALUES " +
                    "('e1','k1','SCHEDULED','s1',0,'DEMO','ada','Ada',NULL,'CHAT','Hi',1,NULL,'SCHEDULED',0,1,NULL,1,NULL,NULL,NULL,NULL,NULL,'DEFER',0,0,0)",
            )
            close()
        }
        val db = helper.runMigrationsAndValidate(DB_2_3, 3, true, ReplyPilotDatabase.MIGRATION_2_3)
        db.query("SELECT attachmentsJson, recipientKind FROM executions WHERE id = 'e1'").use { cursor ->
            cursor.moveToFirst()
            assertTrue(cursor.isNull(0))
            assertEquals("CHAT", cursor.getString(1))
        }
        db.close()
    }

    @Test
    fun `every version step has a migration`() {
        val covered = ReplyPilotDatabase.MIGRATIONS.map { it.startVersion to it.endVersion }.toSet()
        val required = (1 until ReplyPilotDatabase.VERSION).map { it to it + 1 }.toSet()
        assertEquals(required, covered)
    }

    private companion object {
        const val DB = "migration-test"
        const val DB_1_2 = "migration-1-2"
        const val DB_2_3 = "migration-2-3"
    }
}
