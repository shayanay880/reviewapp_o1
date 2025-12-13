package com.example.v12

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(
    entities = [LessonEntity::class, ProjectEntity::class],
    version = 1,
    exportSchema = false
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun lessonDao(): LessonDao
    abstract fun projectDao(): ProjectDao

    companion object {
        @Volatile private var INSTANCE: AppDatabase? = null

        fun get(context: Context): AppDatabase {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    "review.db"
                )
                    // ✅ real migrations instead of wiping data
                    .addMigrations(MIGRATION_1_2, MIGRATION_2_3)
                    // (optional) only destructive on DOWNGRADE (safer than full destructive)
                    // .fallbackToDestructiveMigrationOnDowngrade()
                    .build()
                    .also { INSTANCE = it }
            }
        }

        /**
         * If you ever had a v1 installed, this upgrades it to your v2 schema
         * (projects + projectId + reviewCount).
         *
         * If your app NEVER had version 1, you can delete this migration.
         */
        val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                // Create projects table if it didn't exist
                db.execSQL("""
                    CREATE TABLE IF NOT EXISTS projects (
                        id INTEGER NOT NULL PRIMARY KEY,
                        name TEXT NOT NULL
                    )
                """.trimIndent())

                // Ensure a default project exists
                db.execSQL("""
                    INSERT OR IGNORE INTO projects (id, name) VALUES (1, 'General')
                """.trimIndent())

                // Add columns to lessons (if v1 didn't have them)
                db.execSQL("""
                    ALTER TABLE lessons ADD COLUMN projectId INTEGER NOT NULL DEFAULT 1
                """.trimIndent())

                db.execSQL("""
                    ALTER TABLE lessons ADD COLUMN reviewCount INTEGER NOT NULL DEFAULT 0
                """.trimIndent())
            }
        }

        /**
         * v2 -> v3: add fsrsCardJson
         */
        val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("""
                    ALTER TABLE lessons ADD COLUMN fsrsCardJson TEXT
                """.trimIndent())
            }
        }
    }
}
