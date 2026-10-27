package io.legado.app.ui.book.map

import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.os.Build
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.children
import androidx.core.view.setPadding
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.lifecycleScope
import com.bumptech.glide.Glide
import com.bumptech.glide.load.engine.DiskCacheStrategy
import com.bumptech.glide.signature.ObjectKey
import io.legado.app.R
import io.legado.app.base.BaseDialogFragment
import io.legado.app.data.entities.Book
import io.legado.app.lib.theme.ThemeStore
import io.legado.app.ui.file.HandleFileContract
import io.legado.app.utils.dpToPx
import io.legado.app.utils.showDialogFragment
import io.legado.app.utils.toastOnUi
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import java.io.File
import kotlin.math.min

class BookMapDialog : BaseDialogFragment(0) {
    private var sourceChooserShown = false
    private var alertDialog: AlertDialog? = null
    private var candidateLoadJob: Job? = null

    private val selectImage = registerForActivityResult(HandleFileContract()) { result ->
        val uri = result.uri
        if (uri == null) {
            chooseSource()
        } else {
            book()?.let { book ->
                lifecycleScope.launch {
                    runCatching { BookMapImageManager.saveLocal(requireContext(), book, uri) }
                        .onSuccess { openViewer(book) }
                        .onFailure { requireContext().toastOnUi(it.message ?: getString(R.string.error_decode_bitmap)) }
                }
            }
        }
    }

    @Suppress("DEPRECATION")
    private fun book(): Book? = arguments?.getParcelable(KEY)

    override fun onFragmentCreated(view: View, savedInstanceState: Bundle?) {
        view.setBackgroundColor(Color.TRANSPARENT)
    }

    override fun onStart() {
        super.onStart()
        if (!sourceChooserShown) {
            sourceChooserShown = true
            if (arguments?.getBoolean(KEY_HISTORY_ONLY) == true) {
                book()?.let(::loadPeriods)
            } else {
                chooseSource()
            }
        }
    }

    override fun onCreateView(inflater: android.view.LayoutInflater, container: ViewGroup?, state: Bundle?): View {
        return View(requireContext())
    }

    override fun onDestroyView() {
        candidateLoadJob?.cancel()
        candidateLoadJob = null
        alertDialog?.dismiss()
        alertDialog = null
        super.onDestroyView()
    }

    private fun chooseSource() {
        val book = book() ?: return close()
        if (BookMapImageManager.currentFile(book) != null) return openViewer(book)
        val options = arrayOf(getString(R.string.select_local_image), getString(R.string.historical_map))
        showDialog(AlertDialog.Builder(requireContext())
            .setTitle(R.string.map_image)
            .setItems(options) { _, which ->
                if (which == 0) {
                    alertDialog?.dismiss()
                    selectImage.launch { mode = HandleFileContract.IMAGE }
                } else {
                    loadPeriods(book)
                }
            }
            .setNegativeButton(android.R.string.cancel) { _, _ -> close() })
    }

    private fun loadPeriods(book: Book) {
        val loading = ProgressBar(requireContext())
        val loadingDialog = showDialog(
            AlertDialog.Builder(requireContext()).setTitle(R.string.historical_map).setView(loading)
        )
        lifecycleScope.launch {
            runCatching { BookMapImageManager.fetchPeriods() }
                .onSuccess { periods ->
                    loadingDialog.dismiss()
                    showPeriods(book, periods)
                }
                .onFailure {
                    loadingDialog.dismiss()
                    requireContext().toastOnUi(it.message ?: getString(R.string.map_load_failed))
                }
        }
    }

    private fun showPeriods(book: Book, periods: List<MapPeriod>) {
        if (periods.isEmpty()) {
            requireContext().toastOnUi(R.string.map_no_candidates)
            return
        }
        val inferred = BookMapImageManager.inferDynasty(book)
        val initial = periods.indexOfFirst { it.title.contains(inferred.orEmpty()) }.coerceAtLeast(0)
        val leftList = LinearLayout(requireContext()).apply { orientation = LinearLayout.VERTICAL }
        val leftScroll = ScrollView(requireContext()).apply {
            addView(leftList, ViewGroup.LayoutParams(-1, -2))
        }
        val rightList = LinearLayout(requireContext()).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(8.dpToPx(), 0, 8.dpToPx(), 8.dpToPx())
        }
        val rightScroll = ScrollView(requireContext()).apply {
            addView(rightList, ViewGroup.LayoutParams(-1, -2))
        }
        val content = LinearLayout(requireContext()).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(4.dpToPx(), 0, 4.dpToPx(), 0)
            addView(leftScroll, LinearLayout.LayoutParams(136.dpToPx(), 420.dpToPx()))
            addView(rightScroll, LinearLayout.LayoutParams(0, 420.dpToPx(), 1f))
        }
        showDialog(AlertDialog.Builder(requireContext()).setTitle(periods[initial].title).setView(content)
            .setNegativeButton(android.R.string.cancel) { _, _ -> close() })

        fun selectPeriod(index: Int) {
            leftList.children.forEachIndexed { childIndex, child ->
                (child as? TextView)?.setBackgroundColor(
                    if (childIndex == index) 0xFFE8E8E8.toInt() else Color.TRANSPARENT
                )
            }
            alertDialog?.setTitle(periods[index].title)
            loadCandidates(book, periods[index], rightList)
        }
        periods.forEachIndexed { index, period ->
            val item = TextView(requireContext()).apply {
                text = period.title
                textSize = 14f
                setTextColor(ThemeStore.textColorPrimary(requireContext()))
                setPadding(10.dpToPx(), 12.dpToPx(), 8.dpToPx(), 12.dpToPx())
                setOnClickListener { selectPeriod(index) }
            }
            leftList.addView(item, LinearLayout.LayoutParams(-1, ViewGroup.LayoutParams.WRAP_CONTENT))
        }
        selectPeriod(initial)
    }

    private fun loadCandidates(book: Book, period: MapPeriod, container: LinearLayout) {
        candidateLoadJob?.cancel()
        container.removeAllViews()
        container.addView(ProgressBar(requireContext()), LinearLayout.LayoutParams(-1, 64.dpToPx()))
        candidateLoadJob = lifecycleScope.launch {
            runCatching { BookMapImageManager.fetchCandidates(period) }
                .onSuccess { candidates -> showCandidates(book, candidates, container) }
                .onFailure {
                    container.removeAllViews()
                    addMessage(container, it.message ?: getString(R.string.map_load_failed))
                    requireContext().toastOnUi(it.message ?: getString(R.string.map_load_failed))
                }
        }
    }

    private fun showCandidates(book: Book, candidates: List<MapImageCandidate>, container: LinearLayout) {
        container.removeAllViews()
        if (candidates.isEmpty()) {
            addMessage(container, getString(R.string.map_no_candidates))
            return
        }
        candidates.take(30).forEach { candidate ->
            val row = LinearLayout(requireContext()).apply { gravity = Gravity.CENTER_VERTICAL; setPadding(8.dpToPx()) }
            val image = ImageView(requireContext()).apply { layoutParams = LinearLayout.LayoutParams(72.dpToPx(), 56.dpToPx()) }
            val thumbnail = BookMapImageManager.cachedFile(requireContext(), candidate)
                ?: candidate.thumbnailUrl
            Glide.with(this).load(thumbnail).fitCenter().into(image)
            val text = TextView(requireContext()).apply {
                text = candidate.title
                setTextColor(ThemeStore.textColorPrimary(requireContext()))
                setPadding(12.dpToPx(), 0, 0, 0)
            }
            row.addView(image); row.addView(text, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            row.setOnClickListener {
                lifecycleScope.launch {
                    if (BookMapImageManager.cachedFile(requireContext(), candidate) == null) {
                        requireContext().toastOnUi(R.string.map_downloading)
                    }
                    runCatching { BookMapImageManager.download(requireContext(), book, candidate) }
                        .onSuccess { refreshViewerOrOpen(book, it) }
                        .onFailure { requireContext().toastOnUi(it.message ?: getString(R.string.map_download_failed)) }
                }
            }
            container.addView(row)
        }
    }

    private fun addMessage(container: LinearLayout, message: String) {
        container.addView(TextView(requireContext()).apply {
            text = message
            setTextColor(ThemeStore.textColorSecondary(requireContext()))
            setPadding(12.dpToPx(), 20.dpToPx(), 12.dpToPx(), 20.dpToPx())
        })
    }

    private fun refreshViewerOrOpen(book: Book, imagePath: String) {
        if (arguments?.getBoolean(KEY_HISTORY_ONLY) == true) {
            val viewer = findViewer()
            close()
            if (viewer != null) viewer.refreshImage(imagePath) else openViewer(book)
        } else {
            openViewer(book)
        }
    }

    private fun findViewer(): BookMapViewerDialog? {
        return parentFragmentManager.findFragmentByTag(VIEWER_TAG) as? BookMapViewerDialog
            ?: parentFragmentManager.fragments.filterIsInstance<BookMapViewerDialog>().lastOrNull()
    }

    private fun openViewer(book: Book) {
        if (!isAdded) return
        alertDialog?.dismiss()
        alertDialog = null
        dismissAllowingStateLoss()
        parentFragmentManager.beginTransaction()
            .add(BookMapViewerDialog(book), VIEWER_TAG)
            .commitAllowingStateLoss()
    }

    private fun showDialog(builder: AlertDialog.Builder): AlertDialog {
        dialog?.window?.apply {
            setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
            setDimAmount(0f)
        }
        alertDialog?.dismiss()
        return builder.setOnCancelListener { close() }
            .create()
            .also {
                alertDialog = it
                it.show()
                it.window?.setLayout(
                    min(resources.displayMetrics.widthPixels - 24.dpToPx(), 640.dpToPx()),
                    ViewGroup.LayoutParams.WRAP_CONTENT
                )
            }
    }

    private fun close() {
        alertDialog?.dismiss()
        alertDialog = null
        if (isAdded) dismissAllowingStateLoss()
    }

    companion object {
        private const val KEY = "bookUrl"
        private const val KEY_HISTORY_ONLY = "historyOnly"
        const val VIEWER_TAG = "book_map_viewer"

        fun newInstance(book: Book, historyOnly: Boolean = false) =
            BookMapDialog().apply {
                arguments = Bundle().apply {
                    putParcelable(KEY, book)
                    putBoolean(KEY_HISTORY_ONLY, historyOnly)
                }
            }

        fun open(activity: AppCompatActivity, book: Book) {
            if (BookMapImageManager.currentFile(book) != null) {
                BookMapViewerDialog(book).show(activity.supportFragmentManager, VIEWER_TAG)
            } else {
                activity.showDialogFragment(newInstance(book))
            }
        }
    }
}

class BookMapViewerDialog() : BaseDialogFragment(0) {
    constructor(book: Book) : this() { arguments = Bundle().apply { putParcelable("book", book) } }
    @Suppress("DEPRECATION")
    private val book: Book? get() = arguments?.getParcelable("book")
    private var photoView: io.legado.app.ui.widget.image.PhotoView? = null
    override fun onFragmentCreated(view: View, savedInstanceState: Bundle?) {
        view.setBackgroundColor(Color.WHITE)
    }
    override fun onCreateView(inflater: android.view.LayoutInflater, container: ViewGroup?, state: Bundle?): View {
        val root = android.widget.FrameLayout(requireContext()).apply { setBackgroundColor(Color.WHITE) }
        val photo = io.legado.app.ui.widget.image.PhotoView(requireContext()).apply {
            setBackgroundColor(Color.WHITE)
            setMaxScale(5f)
        }
        photoView = photo
        root.addView(photo, ViewGroup.LayoutParams(-1, -1))
        val close = ImageView(requireContext()).apply {
            setImageResource(R.drawable.ic_baseline_close)
            contentDescription = getString(R.string.close)
            setColorFilter(Color.DKGRAY)
            setPadding(12.dpToPx())
            setOnClickListener { dismissAllowingStateLoss() }
        }
        root.addView(close, android.widget.FrameLayout.LayoutParams(52.dpToPx(), 52.dpToPx(), Gravity.TOP or Gravity.START))
        val clear = ImageView(requireContext()).apply {
            setImageResource(R.drawable.ic_outline_delete)
            contentDescription = getString(R.string.clear)
            setColorFilter(Color.DKGRAY)
            setPadding(12.dpToPx())
            setOnClickListener { book?.let { BookMapImageManager.clear(it) }; dismiss() }
        }
        root.addView(clear, android.widget.FrameLayout.LayoutParams(52.dpToPx(), 52.dpToPx(), Gravity.TOP or Gravity.END))
        val rotate = ImageView(requireContext()).apply {
            setImageResource(R.drawable.ic_rotate_right)
            contentDescription = getString(R.string.rotate_image)
            setColorFilter(Color.DKGRAY)
            setPadding(11.dpToPx())
            background = ColorDrawable(0xCCFFFFFF.toInt())
            elevation = 4.dpToPx().toFloat()
            setOnClickListener { photo.rotateAndFit(90f) }
        }
        val rotateParams = android.widget.FrameLayout.LayoutParams(
            56.dpToPx(), 56.dpToPx(), Gravity.BOTTOM or Gravity.END
        ).apply { setMargins(0, 0, 16.dpToPx(), 16.dpToPx()) }
        root.addView(rotate, rotateParams)

        val history = TextView(requireContext()).apply {
            text = getString(R.string.historical_map)
            textSize = 14f
            setTextColor(Color.DKGRAY)
            setCompoundDrawablesRelativeWithIntrinsicBounds(R.drawable.ic_image, 0, 0, 0)
            compoundDrawablePadding = 6.dpToPx()
            setPadding(14.dpToPx(), 0, 14.dpToPx(), 0)
            gravity = Gravity.CENTER
            background = ColorDrawable(0xEFFFFFFF.toInt())
            elevation = 4.dpToPx().toFloat()
            setOnClickListener {
                book?.let {
                    BookMapDialog.newInstance(it, historyOnly = true).show(
                        parentFragmentManager,
                        "book_map_history_picker"
                    )
                }
            }
        }
        val historyParams = android.widget.FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT, 48.dpToPx(), Gravity.BOTTOM or Gravity.START
        ).apply { setMargins(16.dpToPx(), 0, 0, 16.dpToPx()) }
        root.addView(history, historyParams)
        loadCurrentImage()
        return root
    }

    fun refreshImage(imagePath: String) {
        val photo = photoView ?: return
        Glide.with(this).clear(photo)
        photo.setImageDrawable(null)
        loadImage(File(imagePath))
    }

    private fun loadCurrentImage() {
        BookMapImageManager.currentFile(book ?: return)?.let(::loadImage)
    }

    private fun loadImage(file: File) {
        if (!file.isFile) return
        val photo = photoView ?: return
        Glide.with(this).load(file)
            .signature(ObjectKey("${file.absolutePath}:${file.length()}:${file.lastModified()}"))
            .skipMemoryCache(true)
            .diskCacheStrategy(DiskCacheStrategy.NONE)
            .dontTransform()
            .into(photo)
    }
    override fun onStart() {
        super.onStart()
        dialog?.setCanceledOnTouchOutside(false)
        dialog?.window?.apply {
            setBackgroundDrawable(ColorDrawable(Color.WHITE))
            clearFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND)
            addFlags(
                WindowManager.LayoutParams.FLAG_DRAWS_SYSTEM_BAR_BACKGROUNDS or
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                    WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS
            )
            statusBarColor = Color.WHITE
            navigationBarColor = Color.WHITE
            attributes = attributes.apply {
                gravity = Gravity.TOP or Gravity.START
                width = ViewGroup.LayoutParams.MATCH_PARENT
                height = ViewGroup.LayoutParams.MATCH_PARENT
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                    layoutInDisplayCutoutMode =
                        WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
                }
            }
            setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
            decorView.setPadding(0, 0, 0, 0)
            decorView.setBackgroundColor(Color.WHITE)
            WindowCompat.setDecorFitsSystemWindows(this, false)
            WindowCompat.getInsetsController(this, decorView).run {
                hide(WindowInsetsCompat.Type.systemBars())
                systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
                isAppearanceLightStatusBars = true
                isAppearanceLightNavigationBars = true
            }
            decorView.post {
                setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
                WindowCompat.getInsetsController(this, decorView)
                    .hide(WindowInsetsCompat.Type.systemBars())
            }
        }
    }
}
