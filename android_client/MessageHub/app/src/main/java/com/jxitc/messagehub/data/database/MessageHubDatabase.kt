package com.jxitc.messagehub.data.database

import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.TypeConverters
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import android.content.Context

/**
 * 本地库。
 *
 * **v1 → v2**：附件预览需要把"服务器上的附件元信息 + 提取出的文本"落进本地（离线可看）。
 * 用 `ALTER TABLE ... ADD COLUMN` 加四个可空列 —— 既有数据一行不动，**不做破坏性重建**
 * （旧代码里的 `fallbackToDestructiveMigration()` 已移除：它会在任何未处理的版本变化上
 * 直接清库，用户的记忆就没了）。
 *
 * 注意**字节永远不进这张库**：图片按需从服务器拉，靠图片库的磁盘缓存；DB 只存元信息与文本。
 */
@Database(
    entities = [MemoryEntity::class],
    version = 2,
    exportSchema = false
)
@TypeConverters(Converters::class)
abstract class MessageHubDatabase : RoomDatabase() {
    
    abstract fun memoryDao(): MemoryDao
    
    companion object {
        /**
         * v1 → v2：新增附件相关列。
         *
         * 全部可空：老记录是"还没同步过附件"（NULL），而不是"零个附件"（`[]`）——
         * 这个区别在"要不要去服务器查状态"上正好是需要的。
         */
        val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE `memories` ADD COLUMN `serverMessageId` TEXT")
                db.execSQL("ALTER TABLE `memories` ADD COLUMN `attachmentsJson` TEXT")
                db.execSQL("ALTER TABLE `memories` ADD COLUMN `skippedAttachmentsJson` TEXT")
                db.execSQL("ALTER TABLE `memories` ADD COLUMN `attachmentsSyncedAt` TEXT")
            }
        }

        @Volatile
        private var INSTANCE: MessageHubDatabase? = null
        
        fun getDatabase(context: Context): MessageHubDatabase {
            return INSTANCE ?: synchronized(this) {
                val instance = Room.databaseBuilder(
                    context.applicationContext,
                    MessageHubDatabase::class.java,
                    "messagehub_database"
                )
                // 保留既有数据的显式迁移；**不加** fallbackToDestructiveMigration()。
                .addMigrations(MIGRATION_1_2)
                .build()
                INSTANCE = instance
                instance
            }
        }
    }
}
