package io.legado.app.data.entities

import android.os.Parcelable
import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey
import kotlinx.parcelize.Parcelize

/**
 * AI 服务实例。每个实例对应一套 endpoint / model / 加密后的 apiKey。
 * 同一个 Provider（如 Custom）可以有多个实例。
 */
@Parcelize
@Entity(tableName = "aiServiceInstances")
data class AiServiceInstance(
    @PrimaryKey
    var id: Long = System.currentTimeMillis(),
    @ColumnInfo(defaultValue = "")
    var name: String = "",
    @ColumnInfo(defaultValue = "DeepSeek")
    var provider: String = "DeepSeek",
    @ColumnInfo(defaultValue = "")
    var endpoint: String = "",
    @ColumnInfo(defaultValue = "")
    var apiKeyEncrypted: String = "",
    @ColumnInfo(defaultValue = "")
    var model: String = "",
    @ColumnInfo(defaultValue = "0")
    var isDefault: Boolean = false,
    @ColumnInfo(defaultValue = "0")
    var sortOrder: Int = 0
) : Parcelable {

    override fun hashCode(): Int = id.hashCode()

    override fun equals(other: Any?): Boolean {
        if (other is AiServiceInstance) {
            return id == other.id
        }
        return false
    }
}
