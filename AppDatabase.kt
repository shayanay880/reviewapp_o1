package com.example.v12

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

@Database(
    entities = [LessonEntity::class, ProjectEntity::class],
    version = 3, // ✅ bump from 2 to 3
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
                    .fallbackToDestructiveMigration() // ✅ easiest while developing
                    .build()
                    .also { INSTANCE = it }
            }
        }
    }
}
