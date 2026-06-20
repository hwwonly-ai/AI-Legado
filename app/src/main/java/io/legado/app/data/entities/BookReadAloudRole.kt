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
    var locked: Boolean = false
) : Parcelable {

    companion object {
        const val VOICE_BOOK_NARRATOR = "@book:narrator"
        const val VOICE_BOOK_DIALOG = "@book:dialog"
    }
}
