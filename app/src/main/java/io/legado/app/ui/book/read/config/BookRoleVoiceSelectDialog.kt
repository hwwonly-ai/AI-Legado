package io.legado.app.ui.book.read.config

import android.net.Uri
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.View
import android.view.ViewGroup
import android.widget.PopupMenu
import androidx.lifecycle.lifecycleScope
import androidx.media3.common.MediaItem
import androidx.media3.exoplayer.ExoPlayer
import androidx.recyclerview.widget.LinearLayoutManager
import io.legado.app.R
import io.legado.app.base.BaseDialogFragment
import io.legado.app.base.adapter.ItemViewHolder
import io.legado.app.base.adapter.RecyclerAdapter
import io.legado.app.data.appDb
import io.legado.app.data.entities.BookReadAloudRole
import io.legado.app.data.entities.HttpTTS
import io.legado.app.databinding.DialogBookVoiceConfigBinding
import io.legado.app.databinding.ItemBookVoicePresetBinding
import io.legado.app.lib.theme.primaryColor
import io.legado.app.model.ReadBook
import io.legado.app.utils.gone
import io.legado.app.utils.setEdgeEffectColor
import io.legado.app.utils.setLayout
import io.legado.app.utils.toastOnUi
import io.legado.app.utils.viewbindingdelegate.viewBinding
import io.legado.app.utils.visible
import kotlinx.coroutines.Dispatchers.IO
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class BookRoleVoiceSelectDialog : BaseDialogFragment(R.layout.dialog_book_voice_config) {

    var onVoiceSelected: ((engine: String, preset: BookVoicePreset) -> Unit)? = null

    private val binding by viewBinding(DialogBookVoiceConfigBinding::bind)
    private val adapter by lazy { VoiceAdapter() }
    private var selectedProvider = BookVoiceProvider.ALIYUN
    private var selectedEngine: String? = null
    private var roleId: String = ""
    private var httpTtsList: List<HttpTTS> = emptyList()
    private var exoPlayer: ExoPlayer? = null

    override fun onStart() {
        super.onStart()
        setLayout(ViewGroup.LayoutParams.MATCH_PARENT, 0.9f)
    }

    override fun onFragmentCreated(view: View, savedInstanceState: Bundle?) {
        initView()
        initData()
    }

    override fun onDestroyView() {
        exoPlayer?.release()
        exoPlayer = null
        super.onDestroyView()
    }

    private fun initView() = binding.run {
        toolBar.setBackgroundColor(primaryColor)
        toolBar.setTitle(R.string.book_role_voice_select)
        tvClose.setOnClickListener { dismissAllowingStateLoss() }
        tvProvider.setOnClickListener { showProviderMenu() }
        recyclerView.setEdgeEffectColor(primaryColor)
        recyclerView.layoutManager = LinearLayoutManager(requireContext())
        recyclerView.adapter = adapter
        etSearch.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                refreshList()
            }
            override fun afterTextChanged(s: Editable?) = Unit
        })
    }

    private fun initData() {
        roleId = arguments?.getString(ARG_ROLE_ID).orEmpty()
        httpTtsList = appDb.httpTTSDao.all
        selectedEngine = arguments?.getString(ARG_TTS_ENGINE)
        selectedProvider = currentHttpTts()?.bookVoiceProvider()
            ?: httpTtsList.firstOrNull { it.isSupportedRoleVoice() }?.bookVoiceProvider()
            ?: BookVoiceProvider.ALIYUN
        if (!selectedEngineMatchesProvider(selectedProvider)) {
            selectedEngine = engineForProvider(selectedProvider)?.id?.toString()
        }
        refreshProviderUi()
        refreshList()
    }

    private fun showProviderMenu() {
        val popup = PopupMenu(requireContext(), binding.tvProvider)
        if (httpTtsList.any { it.isAliyunTTS() }) {
            popup.menu.add(0, BookVoiceProvider.ALIYUN.ordinal, 0, R.string.book_voice_provider_aliyun)
        }
        if (httpTtsList.any { it.isDoubaoTTS() }) {
            popup.menu.add(0, BookVoiceProvider.DOUBAO.ordinal, 1, R.string.book_voice_provider_doubao)
        }
        popup.setOnMenuItemClickListener { item ->
            selectedProvider = if (item.itemId == BookVoiceProvider.DOUBAO.ordinal) {
                BookVoiceProvider.DOUBAO
            } else {
                BookVoiceProvider.ALIYUN
            }
            selectedEngine = engineForProvider(selectedProvider)?.id?.toString()
            refreshProviderUi()
            refreshList()
            true
        }
        popup.show()
    }

    private fun refreshProviderUi() = binding.run {
        tvProvider.setText(
            when (selectedProvider) {
                BookVoiceProvider.ALIYUN -> R.string.book_voice_provider_aliyun
                BookVoiceProvider.DOUBAO -> R.string.book_voice_provider_doubao
            }
        )
        val engine = currentHttpTts()
        tvEngineSummary.text = if (engine?.isSupportedRoleVoice() == true) {
            getString(R.string.book_voice_current_engine, engine.name, engine.providerName())
        } else {
            getString(R.string.book_voice_unsupported_engine)
        }
    }

    private fun refreshList() {
        val query = binding.etSearch.text?.toString()?.trim().orEmpty()
        val rows = BookVoicePresetLibrary.presets(selectedProvider)
            .map { VoiceRow(it, assignmentsFor(it)) }
            .filter { row ->
                query.isBlank() ||
                    row.preset.displayName.contains(query, ignoreCase = true) ||
                    row.preset.voiceProperty.contains(query, ignoreCase = true) ||
                    row.assignmentLabels.any { it.contains(query, ignoreCase = true) }
            }
            .sortedWith(compareBy<VoiceRow> { if (it.isSelectedForCurrentRole) 0 else 1 }
                .thenBy { if (it.assignmentLabels.isEmpty()) 1 else 0 }
                .thenBy { it.preset.displayName })
        adapter.setItems(rows)
        if (rows.isEmpty()) binding.tvEmpty.visible() else binding.tvEmpty.gone()
    }

    private fun assignmentsFor(preset: BookVoicePreset): List<String> {
        val book = ReadBook.book ?: return emptyList()
        val engine = selectedEngine ?: return emptyList()
        return book.getReadAloudRoles()
            .filter { it.voices[engine] == preset.requestValue }
            .map { roleDisplayName(it) }
            .sorted()
    }

    private fun selectVoice(preset: BookVoicePreset) {
        val engine = selectedEngine?.takeIf { it.isNotBlank() } ?: run {
            context?.toastOnUi(R.string.book_voice_unsupported_engine)
            return
        }
        onVoiceSelected?.invoke(engine, preset)
        dismissAllowingStateLoss()
    }

    private fun preview(preset: BookVoicePreset) {
        val httpTts = currentHttpTts() ?: run {
            context?.toastOnUi(R.string.book_voice_unsupported_engine)
            return
        }
        context?.toastOnUi(R.string.book_voice_previewing)
        lifecycleScope.launch {
            runCatching {
                val file = withContext(IO) {
                    BookVoicePreviewHelper.buildPreviewAudio(
                        requireContext(),
                        httpTts,
                        preset,
                        getString(R.string.book_voice_preview_text_default)
                    )
                }
                exoPlayer?.release()
                exoPlayer = ExoPlayer.Builder(requireContext()).build().apply {
                    setMediaItem(MediaItem.fromUri(Uri.fromFile(file)))
                    prepare()
                    play()
                }
            }.onFailure {
                context?.toastOnUi(
                    getString(R.string.book_voice_preview_failed, it.localizedMessage ?: it.javaClass.simpleName)
                )
            }
        }
    }

    private fun currentHttpTts(): HttpTTS? {
        return selectedEngine?.toLongOrNull()?.let { appDb.httpTTSDao.get(it) }
    }

    private fun selectedEngineMatchesProvider(provider: BookVoiceProvider): Boolean {
        return currentHttpTts()?.matchesProvider(provider) == true
    }

    private fun engineForProvider(provider: BookVoiceProvider): HttpTTS? {
        val candidates = httpTtsList.filter { it.matchesProvider(provider) }
        return when (provider) {
            BookVoiceProvider.ALIYUN -> candidates.firstOrNull {
                it.name.contains("统一SSML", ignoreCase = true)
            } ?: candidates.firstOrNull {
                it.name.contains("SSML", ignoreCase = true)
            } ?: candidates.firstOrNull()
            BookVoiceProvider.DOUBAO -> candidates.firstOrNull()
        }
    }

    private fun HttpTTS.matchesProvider(provider: BookVoiceProvider): Boolean {
        return when (provider) {
            BookVoiceProvider.ALIYUN -> isAliyunTTS()
            BookVoiceProvider.DOUBAO -> isDoubaoTTS()
        }
    }

    private fun roleDisplayName(role: BookReadAloudRole): String {
        val name: String? = role.name
        return name?.takeIf { it.isNotBlank() } ?: role.id
    }

    private inner class VoiceAdapter :
        RecyclerAdapter<VoiceRow, ItemBookVoicePresetBinding>(requireContext()) {

        override fun getViewBinding(parent: ViewGroup): ItemBookVoicePresetBinding {
            return ItemBookVoicePresetBinding.inflate(inflater, parent, false)
        }

        override fun convert(
            holder: ItemViewHolder,
            binding: ItemBookVoicePresetBinding,
            item: VoiceRow,
            payloads: MutableList<Any>
        ) = binding.run {
            tvName.text = item.preset.displayName
            tvProperty.text = getString(R.string.book_voice_property, item.preset.voiceProperty)
            tvAssignment.text = if (item.assignmentLabels.isEmpty()) {
                getString(R.string.book_voice_unassigned)
            } else {
                getString(R.string.book_voice_assigned, item.assignmentLabels.joinToString(" / "))
            }
            tvAssign.setText(R.string.book_role_voice_select_action)
            ivMore.gone()
        }

        override fun registerListener(holder: ItemViewHolder, binding: ItemBookVoicePresetBinding) {
            binding.tvPreview.setOnClickListener {
                getItemByLayoutPosition(holder.layoutPosition)?.let { preview(it.preset) }
            }
            binding.tvAssign.setOnClickListener {
                getItemByLayoutPosition(holder.layoutPosition)?.let { selectVoice(it.preset) }
            }
        }
    }

    private inner class VoiceRow(
        val preset: BookVoicePreset,
        val assignmentLabels: List<String>
    ) {
        val isSelectedForCurrentRole: Boolean =
            ReadBook.book?.getReadAloudRoleById(roleId)?.voices?.get(selectedEngine) == preset.requestValue
    }

    companion object {
        private const val ARG_ROLE_ID = "roleId"
        private const val ARG_TTS_ENGINE = "ttsEngine"

        fun newInstance(roleId: String, ttsEngine: String?): BookRoleVoiceSelectDialog {
            return BookRoleVoiceSelectDialog().apply {
                arguments = Bundle().apply {
                    putString(ARG_ROLE_ID, roleId)
                    putString(ARG_TTS_ENGINE, ttsEngine)
                }
            }
        }
    }
}
