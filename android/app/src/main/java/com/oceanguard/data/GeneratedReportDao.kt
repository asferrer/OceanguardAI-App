package com.oceanguard.ai.data

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface GeneratedReportDao {

    @Query("SELECT * FROM generated_reports ORDER BY timestamp DESC")
    fun getAll(): Flow<List<GeneratedReport>>

    @Query("SELECT * FROM generated_reports WHERE id = :id")
    suspend fun getById(id: Long): GeneratedReport?

    @Insert
    suspend fun insert(report: GeneratedReport): Long

    @Delete
    suspend fun delete(report: GeneratedReport)

    @Query("DELETE FROM generated_reports")
    suspend fun deleteAll()

    @Query("DELETE FROM generated_reports WHERE text LIKE '%<!--demo:tour-->%'")
    suspend fun deleteDemoReports()

    @Query("SELECT * FROM generated_reports WHERE locationName = :locationName ORDER BY timestamp DESC")
    fun getByLocation(locationName: String): Flow<List<GeneratedReport>>

    @Query("UPDATE generated_reports SET validationScore = :score, validationDetails = :details WHERE id = :id")
    suspend fun updateValidation(id: Long, score: Int, details: String)
}
