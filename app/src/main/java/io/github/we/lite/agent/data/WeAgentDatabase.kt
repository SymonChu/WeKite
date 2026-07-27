package io.github.we.lite.agent.data

import androidx.room.AutoMigration
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.TypeConverters
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import io.github.we.lite.agent.data.dao.ConditionalPromptDao
import io.github.we.lite.agent.data.dao.ExternalServiceDao
import io.github.we.lite.agent.data.dao.MessageDao
import io.github.we.lite.agent.data.dao.ModelDao
import io.github.we.lite.agent.data.dao.ModelProviderDao
import io.github.we.lite.agent.data.dao.PerTurnPromptDao
import io.github.we.lite.agent.data.dao.PresetPromptDao
import io.github.we.lite.agent.data.dao.ProviderDao
import io.github.we.lite.agent.data.dao.SessionDao
import io.github.we.lite.agent.data.dao.SettingDao
import io.github.we.lite.agent.data.dao.SystemPromptDao
import io.github.we.lite.agent.data.dao.ToolCallDao
import io.github.we.lite.agent.data.dao.ToolPermissionDao
import io.github.we.lite.agent.data.dao.TriggerDao
import io.github.we.lite.agent.data.dao.WorkspaceDao
import io.github.we.lite.agent.data.entity.ConditionalPromptEntity
import io.github.we.lite.agent.data.entity.ExternalServiceEntity
import io.github.we.lite.agent.data.entity.MessageEntity
import io.github.we.lite.agent.data.entity.ModelEntity
import io.github.we.lite.agent.data.entity.ModelProviderEntity
import io.github.we.lite.agent.data.entity.PerTurnPromptEntity
import io.github.we.lite.agent.data.entity.PresetPromptEntity
import io.github.we.lite.agent.data.entity.ProviderEntity
import io.github.we.lite.agent.data.entity.SessionEntity
import io.github.we.lite.agent.data.entity.SettingEntity
import io.github.we.lite.agent.data.entity.SystemPromptEntity
import io.github.we.lite.agent.data.entity.ToolCallEntity
import io.github.we.lite.agent.data.entity.ToolPermissionEntity
import io.github.we.lite.agent.data.entity.TriggerEntity
import io.github.we.lite.agent.data.entity.WorkspaceEntity
import io.github.we.lite.utils.HostInfo
import io.github.we.lite.utils.fs.KnownPaths
import io.github.we.lite.utils.fs.createDirsSafe

@Database(
    entities = [
        SessionEntity::class,
        MessageEntity::class,
        ToolCallEntity::class,
        ProviderEntity::class,
        ToolPermissionEntity::class,
        ModelProviderEntity::class,
        ModelEntity::class,
        SystemPromptEntity::class,
        PerTurnPromptEntity::class,
        ConditionalPromptEntity::class,
        PresetPromptEntity::class,
        WorkspaceEntity::class,
        SettingEntity::class,
        TriggerEntity::class,
        ExternalServiceEntity::class,
    ],
    version = 12,
    exportSchema = true,
    autoMigrations = [
        AutoMigration(from = 9, to = 10), // adds external_services table
        AutoMigration(from = 10, to = 11), // adds messages.reasoningSignature, tool_calls.providerSignature
    ],
)
@TypeConverters(WeAgentConverters::class)
abstract class WeAgentDatabase : RoomDatabase() {
    abstract fun sessionDao(): SessionDao
    abstract fun messageDao(): MessageDao
    abstract fun toolCallDao(): ToolCallDao
    abstract fun providerDao(): ProviderDao
    abstract fun toolPermissionDao(): ToolPermissionDao
    abstract fun modelProviderDao(): ModelProviderDao
    abstract fun modelDao(): ModelDao
    abstract fun systemPromptDao(): SystemPromptDao
    abstract fun perTurnPromptDao(): PerTurnPromptDao
    abstract fun conditionalPromptDao(): ConditionalPromptDao
    abstract fun presetPromptDao(): PresetPromptDao
    abstract fun workspaceDao(): WorkspaceDao
    abstract fun settingDao(): SettingDao
    abstract fun triggerDao(): TriggerDao
    abstract fun externalServiceDao(): ExternalServiceDao

    companion object {
        @Volatile
        private var INSTANCE: WeAgentDatabase? = null

        val instance: WeAgentDatabase
            get() = INSTANCE ?: synchronized(this) {
                INSTANCE ?: build().also { INSTANCE = it }
            }

        // 11 → 12: WEKIT_ROUTER enum value removed from ModelProviderType.
        // Any stored provider row with that type is now unreadable; delete them so the
        // converter no longer encounters an unknown enum name on startup.
        private val MIGRATION_11_12 = object : Migration(11, 12) {
            override fun migrate(db: SupportSQLiteDatabase) {
                // Remove models that referenced the now-deleted provider first to avoid
                // dangling providerId foreign keys, then drop the providers themselves.
                db.execSQL(
                    "DELETE FROM models WHERE providerId IN " +
                            "(SELECT id FROM model_providers WHERE type = 'WEKIT_ROUTER')"
                )
                db.execSQL("DELETE FROM model_providers WHERE type = 'WEKIT_ROUTER'")
            }
        }

        private fun build(): WeAgentDatabase {
            val dbFile = KnownPaths.moduleData
                .resolve("agent")
                .createDirsSafe()
                .resolve("weagent.db")
            return Room.databaseBuilder(
                HostInfo.application,
                WeAgentDatabase::class.java,
                dbFile.toString()
            )
                // WAL uses mmap'd -shm/-wal sidecars that misbehave on FUSE-emulated
                // external storage (moduleData lives on /sdcard); TRUNCATE is safe there.
                .setJournalMode(JournalMode.TRUNCATE)
                .addMigrations(MIGRATION_11_12)
                // Destructive fallback is scoped to the pre-release schemas (1–8) only, which no
                // migration path was ever written for. From 9 onwards every step must have a
                // migration: a missing one then fails loudly at open time instead of silently
                // wiping every session, prompt, workspace, trigger and model provider (API keys
                // included). If you bump `version`, add the matching migration — do NOT widen this
                // list.
                .fallbackToDestructiveMigrationFrom(true, 1, 2, 3, 4, 5, 6, 7, 8)
                .build()
        }
    }
}
