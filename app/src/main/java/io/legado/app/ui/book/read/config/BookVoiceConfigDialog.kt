package io.legado.app.ui.book.read.config

import android.net.Uri
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.util.Base64
import android.view.MenuItem
import android.view.View
import android.view.ViewGroup
import android.widget.PopupMenu
import androidx.appcompat.widget.Toolbar
import androidx.lifecycle.lifecycleScope
import androidx.media3.common.MediaItem
import androidx.media3.exoplayer.ExoPlayer
import androidx.recyclerview.widget.LinearLayoutManager
import io.legado.app.R
import io.legado.app.base.BaseDialogFragment
import io.legado.app.base.adapter.ItemViewHolder
import io.legado.app.base.adapter.RecyclerAdapter
import io.legado.app.data.appDb
import io.legado.app.data.entities.Book
import io.legado.app.data.entities.BookReadAloudRole
import io.legado.app.data.entities.HttpTTS
import io.legado.app.databinding.DialogBookVoiceConfigBinding
import io.legado.app.databinding.DialogEditTextBinding
import io.legado.app.databinding.ItemBookVoicePresetBinding
import io.legado.app.help.config.AppConfig
import io.legado.app.help.http.getProxyClient
import io.legado.app.help.http.newCallResponse
import io.legado.app.lib.dialogs.alert
import io.legado.app.lib.theme.primaryColor
import io.legado.app.model.ReadAloud
import io.legado.app.model.ReadAloudAudioCache
import io.legado.app.model.ReadAloudProviderSegment
import io.legado.app.model.ReadBook
import io.legado.app.model.analyzeRule.AnalyzeUrl
import io.legado.app.utils.gone
import io.legado.app.utils.setEdgeEffectColor
import io.legado.app.utils.setLayout
import io.legado.app.utils.toastOnUi
import io.legado.app.utils.viewbindingdelegate.viewBinding
import io.legado.app.utils.visible
import io.legado.app.utils.jsonPath
import kotlinx.coroutines.Dispatchers.IO
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.File

class BookVoiceConfigDialog : BaseDialogFragment(R.layout.dialog_book_voice_config),
    Toolbar.OnMenuItemClickListener {

    private val binding by viewBinding(DialogBookVoiceConfigBinding::bind)
    private val adapter by lazy { VoiceAdapter() }
    private var selectedProvider = BookVoiceProvider.ALIYUN
    private var selectedEngine: String? = null
    private var httpTtsList: List<HttpTTS> = emptyList()
    private var previewText: String = ""
    private var exoPlayer: ExoPlayer? = null

    override fun onStart() {
        super.onStart()
        setLayout(ViewGroup.LayoutParams.MATCH_PARENT, 0.9f)
    }

    override fun onFragmentCreated(view: View, savedInstanceState: Bundle?) {
        previewText = getString(R.string.book_voice_preview_text_default)
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
        toolBar.setTitle(R.string.book_voice_manage)
        toolBar.inflateMenu(R.menu.book_voice_manage)
        toolBar.setOnMenuItemClickListener(this@BookVoiceConfigDialog)
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
        val book = ReadBook.book
        if (book?.ensureSystemExtraRoles() == true) {
            book.save()
        }
        httpTtsList = appDb.httpTTSDao.all
        selectedEngine = if (arguments?.containsKey(ARG_TTS_ENGINE) == true) {
            arguments?.getString(ARG_TTS_ENGINE)
        } else {
            ReadAloud.ttsEngine
        }
        selectedProvider = resolveInitialProvider()
        if (!selectedEngineMatchesProvider(selectedProvider)) {
            selectedEngine = engineForProvider(selectedProvider)?.id?.toString()
        }
        refreshProviderUi()
        refreshList()
    }

    override fun onMenuItemClick(item: MenuItem): Boolean {
        return when (item.itemId) {
            R.id.menu_preview_text -> {
                showPreviewTextDialog()
                true
            }
            else -> false
        }
    }

    private fun resolveInitialProvider(): BookVoiceProvider {
        val current = selectedEngine?.toLongOrNull()?.let { appDb.httpTTSDao.get(it) }
        return when {
            current?.isDoubaoTTS() == true -> BookVoiceProvider.DOUBAO
            current?.isAliyunTTS() == true -> BookVoiceProvider.ALIYUN
            httpTtsList.any { it.isAliyunTTS() } -> BookVoiceProvider.ALIYUN
            httpTtsList.any { it.isDoubaoTTS() } -> BookVoiceProvider.DOUBAO
            else -> BookVoiceProvider.ALIYUN
        }
    }

    private fun engineForProvider(provider: BookVoiceProvider): HttpTTS? {
        val candidates = httpTtsList.filter {
            it.matchesProvider(provider)
        }
        return when (provider) {
            BookVoiceProvider.ALIYUN -> candidates.firstOrNull {
                it.name.contains("统一SSML", ignoreCase = true)
            } ?: candidates.firstOrNull {
                it.name.contains("SSML", ignoreCase = true)
            } ?: candidates.firstOrNull()
            BookVoiceProvider.DOUBAO -> candidates.firstOrNull()
        }
    }

    private fun selectedEngineMatchesProvider(provider: BookVoiceProvider): Boolean {
        return currentHttpTts()?.matchesProvider(provider) == true
    }

    private fun HttpTTS.matchesProvider(provider: BookVoiceProvider): Boolean {
        return when (provider) {
            BookVoiceProvider.ALIYUN -> isAliyunTTS()
            BookVoiceProvider.DOUBAO -> isDoubaoTTS()
        }
    }

    private fun currentHttpTts(): HttpTTS? {
        return selectedEngine?.toLongOrNull()?.let { appDb.httpTTSDao.get(it) }
    }

    private fun showProviderMenu() {
        val popup = PopupMenu(requireContext(), binding.tvProvider)
        popup.menu.add(0, BookVoiceProvider.ALIYUN.ordinal, 0, R.string.book_voice_provider_aliyun)
        popup.menu.add(0, BookVoiceProvider.DOUBAO.ordinal, 1, R.string.book_voice_provider_doubao)
        popup.setOnMenuItemClickListener { item ->
            selectedProvider = if (item.itemId == BookVoiceProvider.DOUBAO.ordinal) {
                BookVoiceProvider.DOUBAO
            } else {
                BookVoiceProvider.ALIYUN
            }
            if (!selectedEngineMatchesProvider(selectedProvider)) {
                selectedEngine = engineForProvider(selectedProvider)?.id?.toString()
            }
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
            .sortedWith(compareBy<VoiceRow> { if (it.assignmentLabels.isEmpty()) 1 else 0 }
                .thenBy { it.assignmentOrder }
                .thenBy { it.preset.displayName })
        adapter.setItems(rows)
        if (rows.isEmpty()) binding.tvEmpty.visible() else binding.tvEmpty.gone()
    }

    private fun assignmentsFor(preset: BookVoicePreset): List<AssignmentLabel> {
        val book = ReadBook.book ?: return emptyList()
        val engine = selectedEngine ?: return emptyList()
        val labels = mutableListOf<AssignmentLabel>()
        if (book.getNarratorVoice(engine) == preset.requestValue) {
            labels.add(AssignmentLabel(getString(R.string.book_voice_target_narrator), 0))
        }
        if (book.getDialogVoice(engine) == preset.requestValue) {
            labels.add(AssignmentLabel(getString(R.string.book_voice_target_dialog), 1))
        }
        for (role in book.getReadAloudRoles()) {
            if (role.voices[engine] != preset.requestValue) continue
            labels.add(AssignmentLabel(roleDisplayName(role), roleOrder(role)))
        }
        return labels.sortedBy { it.order }
    }

    private fun roleOrder(role: BookReadAloudRole): Int {
        val category: String? = role.roleCategory
        return when (category) {
            BookReadAloudRole.RoleCategory.MALE_LEAD,
            BookReadAloudRole.RoleCategory.FEMALE_LEAD -> 2
            BookReadAloudRole.RoleCategory.EXTRA -> 3
            BookReadAloudRole.RoleCategory.MAJOR_SUPPORTING -> 4
            BookReadAloudRole.RoleCategory.MINOR_SUPPORTING -> 5
            else -> 6
        }
    }

    private fun showPreviewTextDialog() {
        val dialogBinding = DialogEditTextBinding.inflate(layoutInflater).apply {
            editView.setText(previewText)
        }
        alert(titleResource = R.string.book_voice_preview_text) {
            customView { dialogBinding.root }
            okButton {
                previewText = dialogBinding.editView.text?.toString()?.trim()
                    ?.takeIf { it.isNotBlank() }
                    ?: getString(R.string.book_voice_preview_text_default)
            }
            cancelButton()
        }
    }

    private fun showCardMenu(preset: BookVoicePreset, anchor: View) {
        val popup = PopupMenu(requireContext(), anchor)
        popup.menu.add(0, MENU_SET_NARRATOR, 0, R.string.book_voice_set_narrator)
        popup.menu.add(0, MENU_SET_DIALOG, 1, R.string.book_voice_set_dialog)
        popup.setOnMenuItemClickListener { item ->
            when (item.itemId) {
                MENU_SET_NARRATOR -> saveAssignment(preset, listOf(BookVoiceAssignmentTarget.Narrator))
                MENU_SET_DIALOG -> saveAssignment(preset, listOf(BookVoiceAssignmentTarget.Dialog))
            }
            true
        }
        popup.show()
    }

    private fun showAssignDialog(preset: BookVoicePreset) {
        val book = ReadBook.book ?: run {
            context?.toastOnUi(R.string.no_book_available)
            return
        }
        val targets = mutableListOf<Pair<String, BookVoiceAssignmentTarget>>()
        targets.add(getString(R.string.book_voice_target_narrator) to BookVoiceAssignmentTarget.Narrator)
        targets.add(getString(R.string.book_voice_target_dialog) to BookVoiceAssignmentTarget.Dialog)
        book.getReadAloudRoles()
            .sortedWith(compareBy<BookReadAloudRole> { roleOrder(it) }.thenBy { roleDisplayName(it) })
            .forEach { role ->
                targets.add(roleDisplayName(role) to BookVoiceAssignmentTarget.Role(role.id))
            }
        val checked = BooleanArray(targets.size) { index ->
            isTargetAssigned(book, preset, targets[index].second)
        }
        val selected = mutableSetOf<Int>()
        checked.forEachIndexed { index, isChecked ->
            if (isChecked) selected.add(index)
        }
        alert(titleResource = R.string.book_voice_assign_targets) {
            multiChoiceItems(targets.map { it.first }.toTypedArray(), checked) { _, which, isChecked ->
                if (isChecked) selected.add(which) else selected.remove(which)
            }
            okButton {
                saveAssignment(preset, selected.map { targets[it].second })
            }
            cancelButton()
        }
    }

    private fun isTargetAssigned(
        book: Book,
        preset: BookVoicePreset,
        target: BookVoiceAssignmentTarget
    ): Boolean {
        val engine = selectedEngine ?: return false
        return when (target) {
            BookVoiceAssignmentTarget.Narrator -> book.getNarratorVoice(engine) == preset.requestValue
            BookVoiceAssignmentTarget.Dialog -> book.getDialogVoice(engine) == preset.requestValue
            is BookVoiceAssignmentTarget.Role ->
                book.getReadAloudRoleById(target.roleId)?.voices?.get(engine) == preset.requestValue
        }
    }

    private fun saveAssignment(preset: BookVoicePreset, targets: List<BookVoiceAssignmentTarget>) {
        val book = ReadBook.book ?: run {
            context?.toastOnUi(R.string.no_book_available)
            return
        }
        val engine = selectedEngine?.takeIf { it.isNotBlank() } ?: run {
            context?.toastOnUi(R.string.book_voice_unsupported_engine)
            return
        }
        val oldNarrator = book.getNarratorVoice(engine)
        val oldDialog = book.getDialogVoice(engine)
        val oldRoleVoices = book.getReadAloudRoles().associate { it.id to it.voices[engine] }

        var narrator = oldNarrator
        var dialog = oldDialog
        for (target in targets) {
            when (target) {
                BookVoiceAssignmentTarget.Narrator -> narrator = preset.requestValue
                BookVoiceAssignmentTarget.Dialog -> dialog = preset.requestValue
                is BookVoiceAssignmentTarget.Role ->
                    book.setRoleVoice(target.roleId, engine, preset.requestValue)
            }
        }
        book.setTtsVoices(engine, narrator, dialog)
        book.save()
        ReadAloud.upReadAloudClass()
        if (voiceChanged(book, engine, oldNarrator, oldDialog, oldRoleVoices)) {
            clearChapterAudioCache()
        }
        context?.toastOnUi(R.string.book_voice_saved)
        refreshList()
    }

    private fun voiceChanged(
        book: Book,
        engine: String,
        oldNarrator: String?,
        oldDialog: String?,
        oldRoleVoices: Map<String, String?>
    ): Boolean {
        if (oldNarrator != book.getNarratorVoice(engine)) return true
        if (oldDialog != book.getDialogVoice(engine)) return true
        return book.getReadAloudRoles().any { oldRoleVoices[it.id] != it.voices[engine] }
    }

    private fun roleDisplayName(role: BookReadAloudRole): String {
        val name: String? = role.name
        return name?.takeIf { it.isNotBlank() } ?: role.id
    }

    private fun clearChapterAudioCache() {
        ReadAloudAudioCache.clearChapterAudioCache(
            requireContext().cacheDir,
            ReadBook.curTextChapter?.title
        )
    }

    private fun preview(preset: BookVoicePreset) {
        val httpTts = currentHttpTts() ?: run {
            context?.toastOnUi(R.string.book_voice_unsupported_engine)
            return
        }
        context?.toastOnUi(R.string.book_voice_previewing)
        lifecycleScope.launch {
            runCatching {
                val file = withContext(IO) { buildPreviewAudio(httpTts, preset) }
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

    private suspend fun buildPreviewAudio(httpTts: HttpTTS, preset: BookVoicePreset): File {
        val providerSegment = ReadAloudProviderSegment(
            provider = httpTts.providerName(),
            voice = preset.requestValue
        )
        val analyzeUrl = AnalyzeUrl(
            httpTts.url,
            speakText = previewSpeakText(preset),
            speakSpeed = AppConfig.ttsSpeechRate,
            readAloudProviderSegment = providerSegment,
            source = httpTts,
            readTimeout = 300 * 1000L,
            coroutineContext = IO
        )
        val file = File(requireContext().cacheDir, "book_voice_preview.mp3")
        analyzeUrl.getResponseAwait().use { response ->
            if (!response.isSuccessful) {
                throw IllegalStateException("HTTP ${response.code}")
            }
            val contentType = response.headers["Content-Type"].orEmpty().substringBefore(";")
            if (contentType == "application/json" || contentType.startsWith("text/")) {
                val bytes = decodePreviewAudio(httpTts, response.body.string())
                file.writeBytes(bytes)
            } else {
                response.body.byteStream().use { input ->
                    file.outputStream().use { output -> input.copyTo(output) }
                }
            }
        }
        return file
    }

    private fun previewSpeakText(preset: BookVoicePreset): String {
        val text = previewText.ifBlank { getString(R.string.book_voice_preview_text_default) }
        return if (preset.provider == BookVoiceProvider.ALIYUN) {
            """<speak><voice name="${preset.requestValue}">$text</voice></speak>"""
        } else {
            text
        }
    }

    private suspend fun decodePreviewAudio(httpTts: HttpTTS, bodyText: String): ByteArray {
        val responseType = httpTts.responseType?.trim().orEmpty()
        if (responseType.equals("jsonLinesBase64", true)
            || responseType.equals("jsonStreamBase64", true)
        ) {
            return decodeBase64JsonChunks(bodyText)
        }
        val audioText = httpTts.audioPath
            ?.takeIf { it.isNotBlank() }
            ?.let { jsonPath.parse(bodyText).read<String>(it) }
            ?: bodyText
        return when {
            responseType.equals("jsonHex", true) || responseType.equals("hex", true) ->
                hexToBytes(audioText)
            responseType.equals("jsonBase64", true) || responseType.equals("base64", true) ->
                Base64.decode(audioText, Base64.DEFAULT)
            responseType.equals("jsonUrl", true) || responseType.equals("url", true) ->
                downloadAudioUrl(audioText)
            else -> throw IllegalStateException("Unsupported TTS response type: $responseType")
        }
    }

    private fun decodeBase64JsonChunks(bodyText: String): ByteArray {
        val out = ByteArrayOutputStream()
        for (json in extractJsonObjects(bodyText)) {
            val obj = JSONObject(json)
            val code = obj.optInt("code", 0)
            if (code != 0 && code != 20000000) {
                throw IllegalStateException("TTS code=$code ${obj.optString("message")}")
            }
            val data = obj.optString("data")
            if (data.isNotBlank()) {
                out.write(Base64.decode(data, Base64.DEFAULT))
            }
        }
        if (out.size() == 0) {
            throw IllegalStateException("No preview audio data")
        }
        return out.toByteArray()
    }

    private fun extractJsonObjects(text: String): List<String> {
        val objects = arrayListOf<String>()
        var start = -1
        var depth = 0
        var inString = false
        var escaped = false
        for (i in text.indices) {
            val c = text[i]
            if (inString) {
                if (escaped) {
                    escaped = false
                } else if (c == '\\') {
                    escaped = true
                } else if (c == '"') {
                    inString = false
                }
                continue
            }
            when (c) {
                '"' -> inString = true
                '{' -> {
                    if (depth == 0) start = i
                    depth++
                }
                '}' -> {
                    if (depth > 0) depth--
                    if (depth == 0 && start >= 0) {
                        objects.add(text.substring(start, i + 1))
                        start = -1
                    }
                }
            }
        }
        return objects
    }

    private fun hexToBytes(hex: String): ByteArray {
        val clean = hex.trim().filterNot { it.isWhitespace() }
        require(clean.length % 2 == 0) { "Invalid hex audio" }
        return ByteArray(clean.length / 2) { index ->
            clean.substring(index * 2, index * 2 + 2).toInt(16).toByte()
        }
    }

    private suspend fun downloadAudioUrl(url: String): ByteArray {
        return getProxyClient().newCallResponse {
            url(url)
        }.use { response ->
            if (!response.isSuccessful) {
                throw IllegalStateException("HTTP ${response.code}")
            }
            response.body.bytes()
        }
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
        }

        override fun registerListener(holder: ItemViewHolder, binding: ItemBookVoicePresetBinding) {
            binding.tvPreview.setOnClickListener {
                getItemByLayoutPosition(holder.layoutPosition)?.let { preview(it.preset) }
            }
            binding.tvAssign.setOnClickListener {
                getItemByLayoutPosition(holder.layoutPosition)?.let { showAssignDialog(it.preset) }
            }
            binding.ivMore.setOnClickListener {
                getItemByLayoutPosition(holder.layoutPosition)?.let { row ->
                    showCardMenu(row.preset, binding.ivMore)
                }
            }
        }
    }

    private data class VoiceRow(
        val preset: BookVoicePreset,
        val assignments: List<AssignmentLabel>
    ) {
        val assignmentLabels: List<String> = assignments.map { it.label }
        val assignmentOrder: Int = assignments.minOfOrNull { it.order } ?: Int.MAX_VALUE
    }

    private data class AssignmentLabel(val label: String, val order: Int)

    companion object {
        private const val ARG_TTS_ENGINE = "ttsEngine"
        private const val MENU_SET_NARRATOR = 1
        private const val MENU_SET_DIALOG = 2

        fun newInstance(ttsEngine: String?): BookVoiceConfigDialog {
            return BookVoiceConfigDialog().apply {
                arguments = Bundle().apply {
                    putString(ARG_TTS_ENGINE, ttsEngine)
                }
            }
        }
    }
}
