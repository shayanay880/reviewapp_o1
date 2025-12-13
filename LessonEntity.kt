package com.example.v12

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "lessons")
data class LessonEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val title: String,
    val projectId: Long = 1,
    val box: Int = 1,                  // for UI: Step 1..5
    val dueAt: Long = System.currentTimeMillis(),
    val isManual: Boolean = false,
    val reviewCount: Int = 0,
    val fsrsCardJson: String? = null   // ✅ REQUIRED for FSRS
)
