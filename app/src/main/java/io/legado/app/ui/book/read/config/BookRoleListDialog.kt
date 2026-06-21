package io.legado.app.ui.book.read.config

import android.annotation.SuppressLint
import android.content.Context
import android.os.Bundle
import android.view.LayoutInflater
import android.view.MenuItem
import android.view.View
import android.view.ViewGroup
import androidx.appcompat.widget.Toolbar
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import io.legado.app.R
import io.legado.app.base.BaseDialogFragment
import io.legado.app.data.entities.BookReadAloudRole
import io.legado.app.databinding.DialogBookRoleListBinding
import io.legado.app.databinding.ItemBookRoleBinding
import io.legado.app.databinding.ItemBookRoleSectionBinding
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
    private val adapter by lazy { GroupedRoleAdapter(requireContext()) }

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
        initCountChips()
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
        // MVP7：展示前确保 8 个固定龙套存在（幂等）。
        val book = ReadBook.book
        if (book != null && book.ensureSystemExtraRoles()) {
            book.save()
        }
        val roles = ReadBook.book?.getReadAloudRoles() ?: mutableListOf()
        adapter.submit(roles)
        updateCountChips(roles)
        binding.tvEmpty.visibility = if (roles.isEmpty()) View.VISIBLE else View.GONE
        binding.recyclerView.visibility = if (roles.isEmpty()) View.GONE else View.VISIBLE
    }

    private fun initCountChips() = binding.run {
        tvCountAll.setOnClickListener { adapter.filterBy(null) }
        tvCountLead.setOnClickListener {
            adapter.filterBy(BookReadAloudRole.RoleCategory.MALE_LEAD)
        }
        tvCountMajor.setOnClickListener {
            adapter.filterBy(BookReadAloudRole.RoleCategory.MAJOR_SUPPORTING)
        }
        tvCountMinor.setOnClickListener {
            adapter.filterBy(BookReadAloudRole.RoleCategory.MINOR_SUPPORTING)
        }
        tvCountExtra.setOnClickListener {
            adapter.filterBy(BookReadAloudRole.RoleCategory.EXTRA)
        }
    }

    private fun updateCountChips(roles: List<BookReadAloudRole>) = binding.run {
        val leadCount = roles.count {
            it.roleCategory == BookReadAloudRole.RoleCategory.MALE_LEAD ||
                it.roleCategory == BookReadAloudRole.RoleCategory.FEMALE_LEAD
        }
        tvCountAll.text = getString(R.string.book_role_count_all, roles.size)
        tvCountLead.text = getString(R.string.book_role_count_lead, leadCount)
        tvCountMajor.text = getString(
            R.string.book_role_count_major,
            roles.count { it.roleCategory == BookReadAloudRole.RoleCategory.MAJOR_SUPPORTING }
        )
        tvCountMinor.text = getString(
            R.string.book_role_count_minor,
            roles.count { it.roleCategory == BookReadAloudRole.RoleCategory.MINOR_SUPPORTING }
        )
        tvCountExtra.text = getString(
            R.string.book_role_count_extra,
            roles.count { it.roleCategory == BookReadAloudRole.RoleCategory.EXTRA }
        )
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

    private sealed class RoleListItem {
        data class Section(val title: String, val countText: String?) : RoleListItem()
        data class RoleRow(val role: BookReadAloudRole) : RoleListItem()
    }

    private object ViewTypes {
        const val SECTION = 0
        const val ROLE = 1
    }

    private inner class GroupedRoleAdapter(private val context: Context) :
        RecyclerView.Adapter<RecyclerView.ViewHolder>() {

        private val items = mutableListOf<RoleListItem>()
        private var allRoles: List<BookReadAloudRole> = emptyList()
        private var filterCategory: String? = null

        @SuppressLint("NotifyDataSetChanged")
        fun submit(roles: List<BookReadAloudRole>) {
            allRoles = roles
            rebuildItems()
            notifyDataSetChanged()
        }

        @SuppressLint("NotifyDataSetChanged")
        fun filterBy(category: String?) {
            filterCategory = category
            rebuildItems()
            notifyDataSetChanged()
        }

        private fun rebuildItems() {
            items.clear()
            val leadRoles = allRoles.filter {
                it.roleCategory == BookReadAloudRole.RoleCategory.MALE_LEAD ||
                    it.roleCategory == BookReadAloudRole.RoleCategory.FEMALE_LEAD
            }
            val majorRoles = allRoles.filter {
                it.roleCategory == BookReadAloudRole.RoleCategory.MAJOR_SUPPORTING
            }
            val minorRoles = allRoles.filter {
                it.roleCategory == BookReadAloudRole.RoleCategory.MINOR_SUPPORTING
            }
            val extraRoles = allRoles.filter {
                it.roleCategory == BookReadAloudRole.RoleCategory.EXTRA
            }
            val filter = filterCategory
            if (filter == null || filter == BookReadAloudRole.RoleCategory.MALE_LEAD) {
                if (leadRoles.isNotEmpty()) {
                    items.add(RoleListItem.Section(context.getString(R.string.book_role_section_lead), null))
                    items.addAll(leadRoles.map { RoleListItem.RoleRow(it) })
                }
            }
            if (filter == null || filter == BookReadAloudRole.RoleCategory.MAJOR_SUPPORTING) {
                items.add(
                    RoleListItem.Section(
                        context.getString(R.string.book_role_section_major),
                        context.getString(
                            R.string.book_role_section_major,
                            majorRoles.size,
                            BookReadAloudRole.RoleCategory.MAX_COUNT[BookReadAloudRole.RoleCategory.MAJOR_SUPPORTING] ?: 0
                        )
                    )
                )
                items.addAll(majorRoles.map { RoleListItem.RoleRow(it) })
            }
            if (filter == null || filter == BookReadAloudRole.RoleCategory.MINOR_SUPPORTING) {
                items.add(
                    RoleListItem.Section(
                        context.getString(R.string.book_role_section_minor),
                        context.getString(
                            R.string.book_role_section_minor,
                            minorRoles.size,
                            BookReadAloudRole.RoleCategory.MAX_COUNT[BookReadAloudRole.RoleCategory.MINOR_SUPPORTING] ?: 0
                        )
                    )
                )
                items.addAll(minorRoles.map { RoleListItem.RoleRow(it) })
            }
            if (filter == null || filter == BookReadAloudRole.RoleCategory.EXTRA) {
                if (extraRoles.isNotEmpty()) {
                    items.add(RoleListItem.Section(context.getString(R.string.book_role_section_extra), null))
                    items.addAll(extraRoles.map { RoleListItem.RoleRow(it) })
                }
            }
        }

        override fun getItemViewType(position: Int): Int {
            return if (items[position] is RoleListItem.Section) ViewTypes.SECTION else ViewTypes.ROLE
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
            val inflater = LayoutInflater.from(parent.context)
            return when (viewType) {
                ViewTypes.SECTION -> SectionViewHolder(
                    ItemBookRoleSectionBinding.inflate(inflater, parent, false)
                )
                else -> RoleViewHolder(
                    ItemBookRoleBinding.inflate(inflater, parent, false)
                )
            }
        }

        override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
            when (val item = items[position]) {
                is RoleListItem.Section -> (holder as SectionViewHolder).bind(item)
                is RoleListItem.RoleRow -> (holder as RoleViewHolder).bind(item.role)
            }
        }

        override fun getItemCount(): Int = items.size

        inner class SectionViewHolder(val binding: ItemBookRoleSectionBinding) :
            RecyclerView.ViewHolder(binding.root) {
            fun bind(section: RoleListItem.Section) = binding.run {
                tvSectionTitle.text = section.title
                if (section.countText != null) {
                    tvSectionCount.text = section.countText
                    tvSectionCount.visibility = View.VISIBLE
                } else {
                    tvSectionCount.visibility = View.GONE
                }
            }
        }

        inner class RoleViewHolder(val binding: ItemBookRoleBinding) :
            RecyclerView.ViewHolder(binding.root) {
            fun bind(role: BookReadAloudRole) = binding.run {
                tvRoleName.text = role.name
                tvRoleAttribute.text = attributeLabel(role.roleAttribute)
                tvLocked.visibility = if (role.locked && !role.systemRole) View.VISIBLE else View.GONE
                ivDelete.visibility = if (role.systemRole) View.GONE else View.VISIBLE

                val configuredVoice = role.voices[ttsEngine]
                val summaryParts = mutableListOf<String>()
                if (role.aliases.isNotEmpty()) {
                    summaryParts.add(role.aliases.joinToString(", "))
                }
                when (configuredVoice) {
                    BookReadAloudRole.VOICE_BOOK_NARRATOR -> summaryParts.add(
                        context.getString(R.string.book_role_voice_follow_narrator)
                    )
                    BookReadAloudRole.VOICE_BOOK_DIALOG -> summaryParts.add(
                        context.getString(R.string.book_role_voice_follow_dialog)
                    )
                    null, "" -> summaryParts.add(context.getString(R.string.book_role_voice_fallback))
                    else -> summaryParts.add(configuredVoice)
                }
                tvRoleSummary.text = summaryParts.joinToString(" · ")

                root.setOnClickListener {
                    val pos = bindingAdapterPosition
                    if (pos != RecyclerView.NO_POSITION) {
                        (items[pos] as? RoleListItem.RoleRow)?.let { showRoleEditor(it.role) }
                    }
                }
                ivDelete.setOnClickListener {
                    val pos = bindingAdapterPosition
                    if (pos != RecyclerView.NO_POSITION) {
                        (items[pos] as? RoleListItem.RoleRow)?.let { deleteRole(it.role) }
                    }
                }
            }
        }

        private fun attributeLabel(attr: String): String {
            val resId = when (attr) {
                BookReadAloudRole.RoleAttribute.BOY -> R.string.book_role_attr_boy
                BookReadAloudRole.RoleAttribute.YOUNG_MALE -> R.string.book_role_attr_young_male
                BookReadAloudRole.RoleAttribute.MIDDLE_MALE -> R.string.book_role_attr_middle_male
                BookReadAloudRole.RoleAttribute.ELDER_MALE -> R.string.book_role_attr_elder_male
                BookReadAloudRole.RoleAttribute.GIRL -> R.string.book_role_attr_girl
                BookReadAloudRole.RoleAttribute.YOUNG_FEMALE -> R.string.book_role_attr_young_female
                BookReadAloudRole.RoleAttribute.MIDDLE_FEMALE -> R.string.book_role_attr_middle_female
                BookReadAloudRole.RoleAttribute.ELDER_FEMALE -> R.string.book_role_attr_elder_female
                else -> 0
            }
            return if (resId != 0) context.getString(resId) else ""
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
