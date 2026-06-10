package com.example

import androidx.room.*
import kotlinx.coroutines.flow.Flow

@Entity(tableName = "financial_items")
data class FinancialItem(
    @PrimaryKey(autoGenerate = true) val id: Int = 0,
    val isDebt: Boolean,
    val type: String,
    val name: String,
    val balance: Double,
    val interestRate: Double = 0.0,
    val minimumPayment: Double = 0.0,
    val shares: Double? = null,
    val purchasePrice: Double? = null,
    val currentPrice: Double? = null,
    val depositAmount: Double? = null,
    val tenureMonths: Int? = null,
    val timestamp: Long = System.currentTimeMillis()
)

@Dao
interface FinancialItemDao {
    @Query("SELECT * FROM financial_items ORDER BY timestamp DESC")
    fun getAllItems(): Flow<List<FinancialItem>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertItem(item: FinancialItem)

    @Update
    suspend fun updateItem(item: FinancialItem)

    @Query("DELETE FROM financial_items WHERE id = :id")
    suspend fun deleteItemById(id: Int)
}

@Database(entities = [FinancialItem::class], version = 4, exportSchema = false)
abstract class AppDatabase : RoomDatabase() {
    abstract fun financialItemDao(): FinancialItemDao
}

class FinancialRepository(private val db: AppDatabase) {
    private val financialItemDao = db.financialItemDao()

    val allItems: Flow<List<FinancialItem>> = financialItemDao.getAllItems()

    suspend fun insert(item: FinancialItem) = financialItemDao.insertItem(item)
    suspend fun update(item: FinancialItem) = financialItemDao.updateItem(item)
    suspend fun deleteById(id: Int) = financialItemDao.deleteItemById(id)
}
