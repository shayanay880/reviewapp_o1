package com.example.v12

import androidx.room.*
import kotlinx.coroutines.flow.Flow

@Dao
interface LessonDao {

    @Query("SELECT * FROM lessons ORDER BY dueAt ASC")
    fun observeAll(): Flow<List<LessonEntity>>

    @Query("SELECT * FROM lessons WHERE id = :id LIMIT 1")
    fun observeById(id: Long): Flow<LessonEntity?>

    @Query("SELECT * FROM lessons WHERE id = :id LIMIT 1")
    suspend fun getById(id: Long): LessonEntity?

    @Query("SELECT * FROM lessons WHERE dueAt <= :now ORDER BY dueAt ASC LIMIT 1")
    suspend fun getFirstDue(now: Long): LessonEntity?

    @Query("SELECT COUNT(*) FROM lessons")
    suspend fun count(): Int

    @Insert
    suspend fun insert(entity: LessonEntity): Long

    @Update
    suspend fun update(entity: LessonEntity)

    @Query("DELETE FROM lessons WHERE id = :id")
    suspend fun deleteById(id: Long)
}
