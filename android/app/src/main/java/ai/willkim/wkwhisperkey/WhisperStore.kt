@Entity(tableName = "whisper_data")
data class WhisperData(
    @PrimaryKey(autoGenerate = true) val id: Int = 0,
    val wave: ByteArray,
    val text: String
)

@Dao
interface WhisperDao {
    @Insert fun insert(data: WhisperData)
    @Query("SELECT * FROM whisper_data ORDER BY id DESC LIMIT :n")
    fun sample(n: Int): List<WhisperData>
}

@Database(entities = [WhisperData::class], version = 1)
abstract class WhisperDB : RoomDatabase() {
    abstract fun dao(): WhisperDao
    companion object {
        fun get(ctx: Context) =
            Room.databaseBuilder(ctx, WhisperDB::class.java, "whisper.db").build()
    }
}

object WhisperStore {
    fun insert(wave: ByteArray, text: String) {
        GlobalScope.launch(Dispatchers.IO) {
            WhisperDB.get(appContext).dao().insert(WhisperData(wave = wave, text = text))
        }
    }
    fun sampleBatch(n: Int) = WhisperDB.get(appContext).dao().sample(n)
}
