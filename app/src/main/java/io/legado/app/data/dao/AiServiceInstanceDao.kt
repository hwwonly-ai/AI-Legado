package io.legado.app.data.dao

import androidx.room.*
import io.legado.app.data.entities.AiServiceInstance

@Dao
interface AiServiceInstanceDao {

    @get:Query("select * from aiServiceInstances order by provider, sortOrder")
    val all: List<AiServiceInstance>

    @Query("select * from aiServiceInstances where provider = :provider order by sortOrder")
    fun getByProvider(provider: String): List<AiServiceInstance>

    @Query("select * from aiServiceInstances where provider = :provider and isDefault = 1 limit 1")
    fun getDefault(provider: String): AiServiceInstance?

    @Query("select * from aiServiceInstances where id = :id")
    fun get(id: Long): AiServiceInstance?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    fun insert(vararg instance: AiServiceInstance)

    @Update(onConflict = OnConflictStrategy.REPLACE)
    fun update(vararg instance: AiServiceInstance)

    @Delete
    fun delete(vararg instance: AiServiceInstance)

    @Query("delete from aiServiceInstances where id = :id")
    fun delete(id: Long)
}
