package io.legado.app.ui.book.read.config

import android.content.Context
import android.os.Bundle
import android.view.MenuItem
import android.view.View
import android.view.ViewGroup
import androidx.appcompat.widget.Toolbar
import androidx.recyclerview.widget.LinearLayoutManager
import io.legado.app.R
import io.legado.app.base.BaseDialogFragment
import io.legado.app.base.adapter.ItemViewHolder
import io.legado.app.base.adapter.RecyclerAdapter
import io.legado.app.data.entities.BookReadAloudRole
import io.legado.app.databinding.DialogBookRoleListBinding
import io.legado.app.databinding.ItemBookRoleBinding
import io.legado.app.lib.dialogs.alert
import io.legado.app.lib.theme.primaryColor
import io.legado.app.model.ReadAloud
import io.legado.app.model.ReadBook
import io.legado.app.utils.setLayout
import io.legado.app.utils.showDialogFragment
import io.legado.app.utils.viewbindingdelegate.viewBinding

class BookRoleListDialog : BaseDialogFragment(R.layout.dialog_book_role_list),
    Toolbar.OnMenuItemClickListener {

    private val binding by viewBinding(DialogBookRoleListBinding::bind)
    private var ttsEngine: String? = null
    private val adapter by lazy { RoleAdapter(requireContext()) }

    override fun onStart() {
        super.onStart()
        setLayout(0.9f, ViewGroup.LayoutParams.WRAP_CONTENT)
    }

    override fun onFragmentCreated(view: View, savedInstanceState: Bundle?) {
        initView()
        initMenu()
        refreshData()
    }

    private fun initView() = binding.run {
        toolBar.setBackgroundColor(primaryColor)
        toolBar.setTitle(R.string.book_role_config)
        toolBar.setNavigationIcon(R.drawable.ic_arrow_back)
        toolBar.setNavigationOnClickListener { dismissAllowingStateLoss() }
        recyclerView.layoutManager = LinearLayoutManager(requireContext())
        recyclerView.adapter = adapter
        ttsEngine = if (arguments?.containsKey(ARG_TTS_ENGINE) == true) {
            arguments?.getString(ARG_TTS_ENGINE)
        } else {
            ReadAloud.ttsEngine
        }
    }

    private fun initMenu() = binding.run {
        toolBar.inflateMenu(R.menu.book_role_list)
        toolBar.setOnMenuItemClickListener(this@BookRoleListDialog)
    }

    override fun onMenuItemClick(item: MenuItem?): Boolean {
        when (item?.itemId) {
            R.id.menu_add_role -> {
                ReadBook.book?.let {
                    val newRole = BookReadAloudRole(
                        id = java.util.UUID.randomUUID().toString(),
                        name = ""
                    )
                    showRoleEditor(newRole)
                }
            }
        }
        return true
    }

    private fun refreshData() {
        val roles = ReadBook.book?.getReadAloudRoles() ?: mutableListOf()
        adapter.setItems(roles)
        binding.tvEmpty.visibility = if (roles.isEmpty()) View.VISIBLE else View.GONE
        binding.recyclerView.visibility = if (roles.isEmpty()) View.GONE else View.VISIBLE
    }

    override fun onResume() {
        super.onResume()
        refreshData()
    }

    private fun deleteRole(role: BookReadAloudRole) {
        alert(message = getString(R.string.book_role_delete_confirm, role.name)) {
            okButton {
                ReadBook.book?.let { book ->
                    book.deleteReadAloudRole(role.id)
                    book.save()
                }
                refreshData()
            }
            cancelButton()
        }
    }

    private fun showRoleEditor(role: BookReadAloudRole) {
        val dialog = BookRoleEditDialog.newInstance(role, ttsEngine).apply {
            setOnDismissListener { refreshData() }
        }
        showDialogFragment(dialog)
    }

    inner class RoleAdapter(context: Context) :
        RecyclerAdapter<BookReadAloudRole, ItemBookRoleBinding>(context) {

        override fun getViewBinding(parent: ViewGroup): ItemBookRoleBinding {
            return ItemBookRoleBinding.inflate(inflater, parent, false)
        }

        override fun convert(
            holder: ItemViewHolder,
            binding: ItemBookRoleBinding,
            item: BookReadAloudRole,
            payloads: MutableList<Any>
        ) {
            binding.tvRoleName.text = item.name
            binding.tvLocked.visibility = if (item.locked) View.VISIBLE else View.GONE

            val configuredVoice = item.voices[ttsEngine]
            val summaryParts = mutableListOf<String>()
            if (item.aliases.isNotEmpty()) {
                summaryParts.add(item.aliases.joinToString(", "))
            }
            when (configuredVoice) {
                BookReadAloudRole.VOICE_BOOK_NARRATOR -> summaryParts.add(
                    getString(R.string.book_role_voice_follow_narrator)
                )
                BookReadAloudRole.VOICE_BOOK_DIALOG -> summaryParts.add(
                    getString(R.string.book_role_voice_follow_dialog)
                )
                null, "" -> summaryParts.add(getString(R.string.book_role_voice_fallback))
                else -> summaryParts.add(configuredVoice)
            }
            binding.tvRoleSummary.text = summaryParts.joinToString(" · ")
        }

        override fun registerListener(holder: ItemViewHolder, binding: ItemBookRoleBinding) {
            binding.root.setOnClickListener {
                getItemByLayoutPosition(holder.layoutPosition)?.let { role ->
                    showRoleEditor(role)
                }
            }
            binding.ivDelete.setOnClickListener {
                getItemByLayoutPosition(holder.layoutPosition)?.let { role ->
                    deleteRole(role)
                }
            }
        }
    }

    companion object {
        private const val ARG_TTS_ENGINE = "ttsEngine"

        fun newInstance(ttsEngine: String?): BookRoleListDialog {
            return BookRoleListDialog().apply {
                arguments = Bundle().apply {
                    putString(ARG_TTS_ENGINE, ttsEngine)
                }
            }
        }
    }
}
