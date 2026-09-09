package com.myfactory.forge.data.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

@Database(
    entities = [
        ProjectEntity::class,
        SessionEntity::class,
        MessageEntity::class,
        ToolInvocationEntity::class,
        ProviderConfigEntity::class,
        CheckpointEntity::class,
        AuditEntity::class,
    ],
    version = 1,
    exportSchema = true,
)
abstract class ForgeDatabase : RoomDatabase() {

    abstract fun projects(): ProjectDao
    abstract fun sessions(): SessionDao
    abstract fun messages(): MessageDao
    abstract fun toolInvocations(): ToolInvocationDao
    abstract fun providerConfigs(): ProviderConfigDao
    abstract fun checkpoints(): CheckpointDao
    abstract fun audit(): AuditDao

    companion object {
        private const val NAME = "forge.db"

        @Volatile
        private var instance: ForgeDatabase? = null

        fun get(context: Context): ForgeDatabase = instance ?: synchronized(this) {
            instance ?: build(context.applicationContext).also { instance = it }
        }

        private fun build(context: Context): ForgeDatabase =
            Room.databaseBuilder(context, ForgeDatabase::class.java, NAME)
                // Write-ahead logging costs a second file and some memory. On a
                // 1 GB device that is a bad trade for a database this small.
                .setJournalMode(JournalMode.TRUNCATE)
                .build()
    }
}
