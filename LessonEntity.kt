package com.example.v12

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "lessons")
data class LessonEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val title: String,
    val box: Int = 1,
    val dueAt: Long = System.currentTimeMillis(),
    val isManual: Boolean = false
)
