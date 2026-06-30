package io.legado.app.data.entities

import android.os.Parcelable
import kotlinx.parcelize.Parcelize

@Parcelize
data class BookReadAloudRole(
    val id: String,
    var name: String,
    var aliases: MutableList<String> = mutableListOf(),
    var note: String = "",
    var voices: HashMap<String, String> = hashMapOf(),
    var locked: Boolean = false,
    var roleCategory: String = RoleCategory.MINOR_SUPPORTING,
    var roleAttribute: String = RoleAttribute.MIDDLE_MALE,
    var autoManaged: Boolean = false,
    var systemRole: Boolean = false
) : Parcelable {

    companion object {
        const val VOICE_BOOK_NARRATOR = "@book:narrator"
        const val VOICE_BOOK_DIALOG = "@book:dialog"
        const val VOICE_EXTRA_PREFIX = "@extra:"
    }

    /**
     * 角色分类。男主角/女主角/主要配角只允许用户手动设置；
     * 次要配角可手动设置（MVP7.1 可动态调整）；龙套为系统角色。
     */
    object RoleCategory {
        const val MALE_LEAD = "male_lead"
        const val FEMALE_LEAD = "female_lead"
        const val MAJOR_SUPPORTING = "major_supporting"
        const val MINOR_SUPPORTING = "minor_supporting"
        const val EXTRA = "extra"

        val ORDER = listOf(
            MALE_LEAD, FEMALE_LEAD, MAJOR_SUPPORTING, MINOR_SUPPORTING, EXTRA
        )

        val MAX_COUNT: Map<String, Int> = mapOf(
            MALE_LEAD to 1,
            FEMALE_LEAD to 1,
            MAJOR_SUPPORTING to 10,
            MINOR_SUPPORTING to 10,
            EXTRA to Int.MAX_VALUE
        )

        /** 只允许用户手动设置的分类，AI 不可写。 */
        val MANUAL_ONLY = setOf(MALE_LEAD, FEMALE_LEAD, MAJOR_SUPPORTING)
    }

    /**
     * 角色属性。男主角只能选男性属性，女主角只能选女性属性，
     * 主要/次要配角可选任一属性，龙套属性固定。
     */
    object RoleAttribute {
        const val BOY = "boy"
        const val YOUNG_MALE = "young_male"
        const val MIDDLE_MALE = "middle_male"
        const val ELDER_MALE = "elder_male"
        const val GIRL = "girl"
        const val YOUNG_FEMALE = "young_female"
        const val MIDDLE_FEMALE = "middle_female"
        const val ELDER_FEMALE = "elder_female"

        val MALE = setOf(BOY, YOUNG_MALE, MIDDLE_MALE, ELDER_MALE)
        val FEMALE = setOf(GIRL, YOUNG_FEMALE, MIDDLE_FEMALE, ELDER_FEMALE)

        val ALL = listOf(BOY, YOUNG_MALE, MIDDLE_MALE, ELDER_MALE,
            GIRL, YOUNG_FEMALE, MIDDLE_FEMALE, ELDER_FEMALE)
    }
}
