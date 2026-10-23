package io.github.lopezec007.tracker.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface PendingFixDao {
    /** The oldest [limit] queued fixes, in recording order. */
    @Query("SELECT * FROM pending_fixes ORDER BY id LIMIT :limit")
    suspend fun oldest(limit: Int): List<PendingFixEntity>

    @Query("SELECT * FROM pending_fixes ORDER BY id")
    suspend fun all(): List<PendingFixEntity>

    @Query("SELECT COUNT(*) FROM pending_fixes")
    suspend fun count(): Int

    @Query("SELECT COUNT(*) FROM pending_fixes")
    fun observeCount(): Flow<Int>

    @Insert
    suspend fun insert(fix: PendingFixEntity): Long

    @Query("DELETE FROM pending_fixes WHERE id IN (:ids)")
    suspend fun deleteIds(ids: List<Long>)

    @Query("DELETE FROM pending_fixes WHERE deviceTime < :before")
    suspend fun deleteOlderThan(before: Long): Int

    @Query("DELETE FROM pending_fixes")
    suspend fun deleteAll()
}
