package io.legado.app.ui.book.read.config

import android.content.Context
import android.os.Bundle
import android.text.InputType
import android.view.MenuItem
import android.view.View
import android.view.ViewGroup
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.RadioButton
import androidx.appcompat.widget.Toolbar
import androidx.fragment.app.viewModels
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import io.legado.app.R
import io.legado.app.base.BaseDialogFragment
import io.legado.app.base.adapter.ItemViewHolder
import io.legado.app.base.adapter.RecyclerAdapter
import io.legado.app.constant.AppLog
import io.legado.app.data.appDb
import io.legado.app.data.entities.HttpTTS
import io.legado.app.databinding.DialogEditTextBinding
import io.legado.app.databinding.DialogRecyclerViewBinding
import io.legado.app.databinding.ItemHttpTtsBinding
import io.legado.app.help.DirectLinkUpload
import io.legado.app.help.config.AppConfig
import io.legado.app.lib.dialogs.SelectItem
import io.legado.app.lib.dialogs.alert
import io.legado.app.lib.theme.primaryColor
import io.legado.app.model.ReadAloud
import io.legado.app.model.ReadBook
import io.legado.app.model.tts.DoubaoTtsEngine
import io.legado.app.model.tts.DoubaoTtsSettings
import io.legado.app.ui.association.ImportHttpTtsDialog
import io.legado.app.ui.file.HandleFileContract
import io.legado.app.ui.login.SourceLoginActivity
import io.legado.app.utils.ACache
import io.legado.app.utils.GSON
import io.legado.app.utils.applyTint
import io.legado.app.utils.fromJsonObject
import io.legado.app.utils.gone
import io.legado.app.utils.isAbsUrl
import io.legado.app.utils.isJsonObject
import io.legado.app.utils.sendToClip
import io.legado.app.utils.setEdgeEffectColor
import io.legado.app.utils.setLayout
import io.legado.app.utils.showDialogFragment
import io.legado.app.utils.splitNotBlank
import io.legado.app.utils.startActivity
import io.legado.app.utils.viewbindingdelegate.viewBinding
import io.legado.app.utils.visible
import kotlinx.coroutines.Dispatchers.IO
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

/**
 * tts引擎管理
 */
class SpeakEngineDialog() : BaseDialogFragment(R.layout.dialog_recycler_view),
    Toolbar.OnMenuItemClickListener {

    private val binding by viewBinding(DialogRecyclerViewBinding::bind)
    private val viewModel: SpeakEngineViewModel by viewModels()
    private val ttsUrlKey = "ttsUrlKey"
    private val adapter by lazy { Adapter(requireContext()) }
    private var ttsEngine: String? = ReadAloud.ttsEngine
    private val sysTtsViews = arrayListOf<RadioButton>()
    private val callBack: CallBack? get() = parentFragment as? CallBack
    private val importDocResult = registerForActivityResult(HandleFileContract()) {
        it.uri?.let { uri ->
            showDialogFragment(ImportHttpTtsDialog(uri.toString()))
        }
    }
    private val exportDirResult = registerForActivityResult(HandleFileContract()) {
        it.uri?.let { uri ->
            alert(R.string.export_success) {
                if (uri.toString().isAbsUrl()) {
                    setMessage(DirectLinkUpload.getSummary())
                }
                val alertBinding = DialogEditTextBinding.inflate(layoutInflater).apply {
                    editView.hint = getString(R.string.path)
                    editView.setText(uri.toString())
                }
                customView { alertBinding.root }
                okButton {
                    requireContext().sendToClip(uri.toString())
                }
            }
        }
    }

    override fun onStart() {
        super.onStart()
        setLayout(ViewGroup.LayoutParams.MATCH_PARENT, 0.9f)
    }

    override fun onFragmentCreated(view: View, savedInstanceState: Bundle?) {
        initView()
        initMenu()
        initData()
    }

    private fun initView() = binding.run {
        toolBar.setBackgroundColor(primaryColor)
        toolBar.setTitle(R.string.speak_engine)
        recyclerView.setEdgeEffectColor(primaryColor)
        recyclerView.layoutManager = LinearLayoutManager(requireContext())
        recyclerView.adapter = adapter
        adapter.addHeaderView {
            ItemHttpTtsBinding.inflate(layoutInflater, recyclerView, false).apply {
                sysTtsViews.add(cbName)
                ivEdit.gone()
                ivMenuDelete.gone()
                labelSys.visible()
                cbName.text = "系统默认"
                cbName.tag = ""
                cbName.isChecked = ttsEngine == null || ttsEngine!!.isJsonObject()
                        && GSON.fromJsonObject<SelectItem<String>>(ttsEngine)
                    .getOrNull()?.value.isNullOrEmpty()
                cbName.setOnClickListener {
                    upTts(GSON.toJson(SelectItem("系统默认", "")))
                }
            }
        }
        viewModel.sysEngines.forEach { engine ->
            adapter.addHeaderView {
                ItemHttpTtsBinding.inflate(layoutInflater, recyclerView, false).apply {
                    sysTtsViews.add(cbName)
                    ivEdit.gone()
                    ivMenuDelete.gone()
                    labelSys.visible()
                    cbName.text = engine.label
                    cbName.tag = engine.name
                    cbName.isChecked = GSON.fromJsonObject<SelectItem<String>>(ttsEngine)
                        .getOrNull()?.value == cbName.tag
                    cbName.setOnClickListener {
                        upTts(GSON.toJson(SelectItem(engine.label, engine.name)))
                    }
                }
            }
        }
        tvFooterLeft.setText(R.string.book)
        tvFooterLeft.visible()
        tvFooterLeft.setOnClickListener {
            ReadBook.book?.setTtsEngine(ttsEngine)
            callBack?.upSpeakEngineSummary()
            ReadAloud.upReadAloudClass()
            dismissAllowingStateLoss()
        }
        tvOk.setText(R.string.general)
        tvOk.visible()
        tvOk.setOnClickListener {
            ReadBook.book?.setTtsEngine(null)
            AppConfig.ttsEngine = ttsEngine
            callBack?.upSpeakEngineSummary()
            ReadAloud.upReadAloudClass()
            dismissAllowingStateLoss()
        }
        tvCancel.visible()
        tvCancel.setOnClickListener {
            dismissAllowingStateLoss()
        }
    }

    private fun initMenu() = binding.run {
        toolBar.inflateMenu(R.menu.speak_engine)
        toolBar.menu.applyTint(requireContext())
        toolBar.setOnMenuItemClickListener(this@SpeakEngineDialog)
    }

    private fun initData() {
        lifecycleScope.launch {
            appDb.httpTTSDao.flowAll().catch {
                AppLog.put("朗读引擎界面获取数据失败\n${it.localizedMessage}", it)
            }.flowOn(IO).conflate().collect {
                adapter.setItems(listOf(doubaoVirtualItem()) + it)
            }
        }
    }

    override fun onMenuItemClick(item: MenuItem?): Boolean {
        when (item?.itemId) {
            R.id.menu_add -> showDialogFragment<HttpTtsEditDialog>()
            R.id.menu_default -> viewModel.importDefault()
            R.id.menu_import_local -> importDocResult.launch {
                mode = HandleFileContract.FILE
                allowExtensions = arrayOf("txt", "json")
            }

            R.id.menu_import_onLine -> importAlert()
            R.id.menu_export -> exportDirResult.launch {
                mode = HandleFileContract.EXPORT
                fileData = HandleFileContract.FileData(
                    "httpTts.json",
                    GSON.toJson(adapter.getItems().filterNot { it.isDoubaoVirtualItem() })
                        .toByteArray(),
                    "application/json"
                )
            }
        }
        return true
    }

    private fun importAlert() {
        val aCache = ACache.get(cacheDir = false)
        val cacheUrls: MutableList<String> = aCache
            .getAsString(ttsUrlKey)
            ?.splitNotBlank(",")
            ?.toMutableList() ?: mutableListOf()
        alert(R.string.import_on_line) {
            val alertBinding = DialogEditTextBinding.inflate(layoutInflater).apply {
                editView.hint = "url"
                editView.setFilterValues(cacheUrls)
                editView.delCallBack = {
                    cacheUrls.remove(it)
                    aCache.put(ttsUrlKey, cacheUrls.joinToString(","))
                }
            }
            customView { alertBinding.root }
            okButton {
                alertBinding.editView.text?.toString()?.let { url ->
                    if (url.isAbsUrl() && !cacheUrls.contains(url)) {
                        cacheUrls.add(0, url)
                        aCache.put(ttsUrlKey, cacheUrls.joinToString(","))
                    }
                    showDialogFragment(ImportHttpTtsDialog(url))
                }
            }
        }
    }

    private fun upTts(tts: String) {
        ttsEngine = tts
        sysTtsViews.forEach {
            it.isChecked = GSON.fromJsonObject<SelectItem<String>>(ttsEngine)
                .getOrNull()?.value == it.tag
        }
        adapter.notifyItemRangeChanged(adapter.getHeaderCount(), adapter.itemCount)
    }

    private fun doubaoVirtualItem(): HttpTTS {
        return HttpTTS(
            id = DOUBAO_VIRTUAL_ID,
            name = DoubaoTtsEngine.TITLE
        )
    }

    private fun HttpTTS.isDoubaoVirtualItem(): Boolean {
        return id == DOUBAO_VIRTUAL_ID
    }

    private fun showDoubaoConfigDialog() {
        val settings = DoubaoTtsEngine.getSettings()
        val appIdView = configEditText("AppID", settings.appId)
        val tokenView = configEditText("AccessToken", settings.accessToken)
        val narratorVoiceView = configEditText("旁白 voiceType", settings.narratorVoiceType)
        val dialogVoiceView = configEditText("对白 voiceType", settings.dialogVoiceType)
        val emotionView = configEditText("默认 emotion", settings.emotion)
        val instructionView = configEditText("语音指令", settings.instruction)
        val contextWindowView = configEditText(
            "上下文段数",
            settings.contextWindowSize.toString(),
            InputType.TYPE_CLASS_NUMBER
        )
        val speedView = configEditText("语速倍率", settings.speedRatio.toString(), DECIMAL_INPUT_TYPE)
        val volumeView = configEditText("音量倍率", settings.volumeRatio.toString(), DECIMAL_INPUT_TYPE)
        val pitchView = configEditText("音调倍率", settings.pitchRatio.toString(), DECIMAL_INPUT_TYPE)
        val tagParserView = CheckBox(requireContext()).apply {
            text = "启用语音标签"
            isChecked = settings.enableVoiceTags
        }
        val root = LinearLayout(requireContext()).apply {
            orientation = LinearLayout.VERTICAL
            val padding = (16 * resources.displayMetrics.density).roundToInt()
            setPadding(padding, padding / 2, padding, 0)
            addView(appIdView)
            addView(tokenView)
            addView(narratorVoiceView)
            addView(dialogVoiceView)
            addView(emotionView)
            addView(instructionView)
            addView(tagParserView)
            addView(contextWindowView)
            addView(speedView)
            addView(volumeView)
            addView(pitchView)
        }
        alert(title = DoubaoTtsEngine.TITLE) {
            customView { root }
            okButton {
                DoubaoTtsEngine.saveSettings(
                    DoubaoTtsSettings(
                        appId = appIdView.text?.toString().orEmpty().trim(),
                        accessToken = tokenView.text?.toString().orEmpty().trim(),
                        narratorVoiceType = narratorVoiceView.text?.toString().orEmpty().trim()
                            .ifBlank { "BV700_V2_streaming" },
                        dialogVoiceType = dialogVoiceView.text?.toString().orEmpty().trim()
                            .ifBlank { "BV001_V2_streaming" },
                        emotion = emotionView.text?.toString().orEmpty().trim(),
                        instruction = instructionView.text?.toString().orEmpty().trim(),
                        enableVoiceTags = tagParserView.isChecked,
                        contextWindowSize = contextWindowView.text?.toString()
                            ?.toIntOrNull()
                            ?.coerceIn(0, 5)
                            ?: 2,
                        speedRatio = speedView.text?.toString()?.toDoubleOrNull() ?: 1.0,
                        volumeRatio = volumeView.text?.toString()?.toDoubleOrNull() ?: 1.0,
                        pitchRatio = pitchView.text?.toString()?.toDoubleOrNull() ?: 1.0
                    )
                )
                if (DoubaoTtsEngine.isSelected(ReadAloud.ttsEngine)) {
                    ReadAloud.upReadAloudClass()
                }
            }
            noButton()
        }
    }

    private fun configEditText(
        hint: String,
        text: String,
        inputType: Int = InputType.TYPE_CLASS_TEXT
    ): EditText {
        return EditText(requireContext()).apply {
            this.hint = hint
            setText(text)
            this.inputType = inputType
            isSingleLine = hint != "语音指令"
        }
    }

    inner class Adapter(context: Context) :
        RecyclerAdapter<HttpTTS, ItemHttpTtsBinding>(context) {

        override fun getViewBinding(parent: ViewGroup): ItemHttpTtsBinding {
            return ItemHttpTtsBinding.inflate(inflater, parent, false)
        }

        override fun convert(
            holder: ItemViewHolder,
            binding: ItemHttpTtsBinding,
            item: HttpTTS,
            payloads: MutableList<Any>
        ) {
            binding.apply {
                cbName.text = item.name
                cbName.isChecked = if (item.isDoubaoVirtualItem()) {
                    DoubaoTtsEngine.isSelected(ttsEngine)
                } else {
                    item.id.toString() == ttsEngine
                }
                ivMenuDelete.gone(item.isDoubaoVirtualItem())
                ivMenuDelete.visible(!item.isDoubaoVirtualItem())
            }
        }

        override fun registerListener(holder: ItemViewHolder, binding: ItemHttpTtsBinding) {
            binding.run {
                cbName.setOnClickListener {
                    getItemByLayoutPosition(holder.layoutPosition)?.let { httpTTS ->
                        if (httpTTS.isDoubaoVirtualItem()) {
                            upTts(DoubaoTtsEngine.selectItemJson())
                            if (!DoubaoTtsEngine.getSettings().isConfigured()) {
                                showDoubaoConfigDialog()
                            }
                            return@let
                        }
                        val id = httpTTS.id.toString()
                        upTts(id)
                        if (!httpTTS.loginUrl.isNullOrBlank()
                            && httpTTS.getLoginInfo().isNullOrBlank()
                        ) {
                            startActivity<SourceLoginActivity> {
                                putExtra("type", "httpTts")
                                putExtra("key", id)
                            }
                        }
                    }
                }
                ivEdit.setOnClickListener {
                    getItemByLayoutPosition(holder.layoutPosition)?.let { httpTTS ->
                        if (httpTTS.isDoubaoVirtualItem()) {
                            showDoubaoConfigDialog()
                        } else {
                            showDialogFragment(HttpTtsEditDialog(httpTTS.id))
                        }
                    }
                }
                ivMenuDelete.setOnClickListener {
                    getItemByLayoutPosition(holder.layoutPosition)?.let { httpTTS ->
                        appDb.httpTTSDao.delete(httpTTS)
                    }
                }
            }
        }

    }

    interface CallBack {
        fun upSpeakEngineSummary()
    }

    companion object {
        private const val DOUBAO_VIRTUAL_ID = Long.MIN_VALUE + 31
        private const val DECIMAL_INPUT_TYPE =
            InputType.TYPE_CLASS_NUMBER or
                    InputType.TYPE_NUMBER_FLAG_DECIMAL or
                    InputType.TYPE_NUMBER_FLAG_SIGNED
    }

}
