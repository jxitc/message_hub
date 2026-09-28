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
 * **v2 → v3**：新增「问知识库」的聊天记录缓存表（先看本地、再由服务器覆盖）。
 * 纯加表，既有数据与既有表一行不动。
 *
 * 注意**字节永远不进这张库**：图片按需从服务器拉，靠图片库的磁盘缓存；DB 只存元信息与文本。
 */
@Database(
    entities = [MemoryEntity::class, ChatMessageEntity::class],
    version = 3,
    exportSchema = false
)
@TypeConverters(Converters::class)
abstract class MessageHubDatabase : RoomDatabase() {
    
    abstract fun memoryDao(): MemoryDao

    abstract fun chatMessageDao(): ChatMessageDao
    
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

        /**
         * v2 → v3：新增「问知识库」的聊天记录缓存表。
         *
         * 建表 SQL 必须与 Room 按 [ChatMessageEntity] 生成的 schema 逐字对齐（列名、
         * 类型、NOT NULL、主键）—— 不一致时 Room 要到第一次打开库才抛
         * `Migration didn't properly handle`，编译期看不出来，所以这里照实体抄。
         *
         * `IF NOT EXISTS` 是容错：迁移本该只跑一次，但开发期装过中间包的话，
         * 也不该因为这张表已存在就让整个库打不开。
         */
        val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `chat_messages` (
                        `turnId` TEXT NOT NULL,
                        `question` TEXT NOT NULL,
                        `answer` TEXT NOT NULL,
                        `rewritten` TEXT,
                        `keywordsJson` TEXT,
                        `entitiesJson` TEXT,
                        `sourcesJson` TEXT,
                        `stepsJson` TEXT,
                        `citedJson` TEXT,
                        `cost` REAL,
                        `tokensPrompt` INTEGER,
                        `tokensCompletion` INTEGER,
                        `elapsedMs` INTEGER,
                        `rating` TEXT,
                        `ratingNote` TEXT,
                        `createdAt` TEXT NOT NULL,
                        PRIMARY KEY(`turnId`)
                    )
                    """.trimIndent()
                )
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
                .addMigrations(MIGRATION_1_2, MIGRATION_2_3)
                .build()
                INSTANCE = instance
                instance
            }
        }
    }
}
