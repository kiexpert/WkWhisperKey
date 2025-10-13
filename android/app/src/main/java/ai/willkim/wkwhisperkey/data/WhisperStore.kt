package ai.willkim.wkwhisperkey.data

import android.content.Context
import androidx.room.*
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * WhisperDB.kt
 * 단일파일 버전 — 데이터, DAO, DB, Store 통합
 * Kotlin 1.9.25 / Gradle 8.7 / Room 2.6.1 호환
 */

@Entity(tableName = "whisper_data")
data class WhisperData(
    @PrimaryKey(autoGenerate = true) val id: Int = 0,
    val wave: ByteArray,
    val text: String
)

@Dao
interface WhisperDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(data: WhisperData)

    @Query("SELECT * FROM whisper_data ORDER BY id DESC LIMIT :n")
    suspend fun sample(n: Int): List<WhisperData>
}

@Database(entities = [WhisperData::class], version = 1, exportSchema = false)
abstract class WhisperDB : RoomDatabase() {
    abstract fun dao(): WhisperDao

    companion object {
        @Volatile private var instance: WhisperDB? = null

        fun get(ctx: Context): WhisperDB =
            instance ?: synchronized(this) {
                instance ?: Room.databaseBuilder(
                    ctx.applicationContext,
                    WhisperDB::class.java,
                    "whisper.db"
                ).build().also { instance = it }
            }
    }
}

object WhisperStore {
    private val ioScope = CoroutineScope(Dispatchers.IO)

    fun insert(ctx: Context, wave: ByteArray, text: String) {
        ioScope.launch {
            WhisperDB.get(ctx).dao().insert(WhisperData(wave = wave, text = text))
        }
    }

    suspend fun sampleBatch(ctx: Context, n: Int): List<WhisperData> {
        return WhisperDB.get(ctx).dao().sample(n)
    }
}
