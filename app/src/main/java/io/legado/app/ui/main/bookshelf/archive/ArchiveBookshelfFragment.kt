package io.legado.app.ui.main.bookshelf.archive

import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.view.GravityCompat
import androidx.core.view.isVisible
import androidx.drawerlayout.widget.DrawerLayout
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.ConcatAdapter
import androidx.recyclerview.widget.LinearLayoutManager
import io.legado.app.R
import io.legado.app.constant.AppLog
import io.legado.app.constant.EventBus
import io.legado.app.data.AppDatabase
import io.legado.app.data.appDb
import io.legado.app.data.entities.Book
import io.legado.app.data.entities.BookGroup
import io.legado.app.databinding.FragmentArchiveBookshelfBinding
import io.legado.app.help.config.AppConfig
import io.legado.app.lib.dialogs.selector
import io.legado.app.lib.theme.accentColor
import io.legado.app.lib.theme.primaryColor
import io.legado.app.lib.theme.primaryTextColor
import io.legado.app.ui.book.group.GroupManageDialog
import io.legado.app.ui.book.info.BookInfoActivity
import io.legado.app.ui.book.search.SearchActivity
import io.legado.app.ui.main.bookshelf.BaseBookshelfFragment
import io.legado.app.utils.applyMainBottomBarPadding
import io.legado.app.utils.applyStatusBarPadding
import io.legado.app.utils.cnCompare
import io.legado.app.utils.dpToPx
import io.legado.app.utils.flowWithLifecycleAndDatabaseChangeFirst
import io.legado.app.utils.observeEvent
import io.legado.app.utils.setEdgeEffectColor
import io.legado.app.utils.showDialogFragment
import io.legado.app.utils.startActivity
import io.legado.app.utils.startActivityForBook
import io.legado.app.utils.viewbindingdelegate.viewBinding
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlin.math.max

/**
 * 档案式书架页。
 *
 * 结构照 AzukiReader / Wenku8：上方立体书架，下方 SHELF / CATALOGUE 书目，
 * 分组放在左侧抽屉里。设置里关掉「档案式书架」开关就会回到原来的书架页。
 */
class ArchiveBookshelfFragment() : BaseBookshelfFragment(R.layout.fragment_archive_bookshelf) {

    constructor(position: Int) : this() {
        arguments = Bundle().apply { putInt("position", position) }
    }

    private val binding by viewBinding(FragmentArchiveBookshelfBinding::bind)
    private val catalogueAdapter by lazy {
        ArchiveCatalogueAdapter(this, viewLifecycleOwner.lifecycle, ::openBook, ::openBookInfo)
    }
    private val headerAdapter by lazy {
        ArchiveShelfHeaderAdapter(::openBook, ::openBookInfo)
    }
    private val concatAdapter by lazy {
        ConcatAdapter(headerAdapter, catalogueAdapter)
    }

    private var currentGroupId = BookGroup.IdAll
    private var currentBooks: List<Book> = emptyList()
    private var groupList: List<BookGroup> = emptyList()
    private var booksJob: Job? = null

    /** 上次提交给立体书架的数量，用于从设置页回来时补一次刷新 */
    private var appliedVisibleCount = 0

    override val groupId: Long get() = currentGroupId

    override val books: List<Book> get() = currentBooks

    override var onlyUpdateRead = false

    override fun onFragmentCreated(view: View, savedInstanceState: Bundle?) {
        binding.archiveContent.applyStatusBarPadding()
        (binding.root as? DrawerLayout)?.let {
            it.setScrimColor(0x66000000)
        }
        // 抽屉面板必须实心：主题背景色在壁纸主题下带 alpha，直接铺会让书架/壁纸透上来
        binding.drawerPanel.setBackgroundColor(ArchivePalette.paper(requireContext()))
        // DrawerLayout 的抽屉面板收不到(或晚于布局收到)状态栏 inset，这里直接按系统状态栏高度补
        val statusBarHeight = resources.getIdentifier("status_bar_height", "dimen", "android")
            .takeIf { it > 0 }
            ?.let { resources.getDimensionPixelSize(it) } ?: 0
        binding.drawerPanel.setPadding(
            binding.drawerPanel.paddingLeft,
            statusBarHeight + 24.dpToPx(),
            binding.drawerPanel.paddingRight,
            binding.drawerPanel.paddingBottom
        )
        binding.drawerPanel.applyMainBottomBarPadding(withInitialPadding = true)
        binding.rvArchive.applyMainBottomBarPadding(usePaddingForRecyclerView = true)
        binding.rvArchive.setEdgeEffectColor(primaryColor)
        binding.rvArchive.layoutManager = LinearLayoutManager(requireContext())
        binding.rvArchive.adapter = concatAdapter
        binding.btnMore.setOnClickListener { showBookshelfMenu(it) }
        binding.btnSearch.setOnClickListener { startActivity<SearchActivity>() }
        binding.btnDrawer.setOnClickListener { openDrawer() }
        binding.llGroup.setOnClickListener { openDrawer() }
        binding.tvDrawerManage.setOnClickListener { showDialogFragment<GroupManageDialog>() }
        headerAdapter.onSortClick = { showSortSelector() }
        observeEvent<String>(EventBus.BOOKSHELF_REFRESH) {
            submitShelf()
        }
        initBookGroupData()
        loadBooks()
    }

    /**
     * 设置页在主界面之上，改数量时 [EventBus.BOOKSHELF_REFRESH] 会因为书架页处于 STOPPED
     * 而收不到（LiveEventBus 只投递给活跃观察者）。回到书架时在这里补一次。
     */
    override fun onResume() {
        super.onResume()
        if (AppConfig.archiveShelfVisibleCount != appliedVisibleCount) {
            submitShelf()
        }
    }

    private fun submitShelf() {
        appliedVisibleCount = AppConfig.archiveShelfVisibleCount
        headerAdapter.submit(currentBooks, appliedVisibleCount)
    }

    // ------------------------------------------------------------------ 数据

    private fun loadBooks() {
        booksJob?.cancel()
        booksJob = viewLifecycleOwner.lifecycleScope.launch {
            appDb.bookDao.flowByGroup(currentGroupId)
                .map { sortBooks(it) }
                .flowWithLifecycleAndDatabaseChangeFirst(
                    viewLifecycleOwner.lifecycle,
                    Lifecycle.State.RESUMED,
                    AppDatabase.BOOK_TABLE_NAME
                ).catch {
                    AppLog.put("档案书架更新出错", it)
                }.conflate().flowOn(kotlinx.coroutines.Dispatchers.Default).collect { list ->
                    currentBooks = list
                    catalogueAdapter.submitList(list)
                    headerAdapter.sortLabel = getString(sortLabelRes(AppConfig.bookshelfSort))
                    submitShelf()
                    binding.tvEmptyMsg.isVisible = list.isEmpty()
                }
        }
    }

    private fun sortBooks(list: List<Book>): List<Book> {
        return when (AppConfig.bookshelfSort) {
            1 -> list.sortedByDescending { it.latestChapterTime }
            2 -> list.sortedWith { o1, o2 -> o1.name.cnCompare(o2.name) }
            3 -> list.sortedBy { it.order }
            4 -> list.sortedByDescending { max(it.latestChapterTime, it.durChapterTime) }
            5 -> list.sortedWith { o1, o2 -> o1.author.cnCompare(o2.author) }
            else -> list.sortedByDescending { it.durChapterTime }
        }
    }

    override fun upGroup(data: List<BookGroup>) {
        groupList = data
        if (groupList.none { it.groupId == currentGroupId }) {
            currentGroupId = BookGroup.IdAll
        }
        rebuildGroupDrawer()
        updateTitle()
    }

    override fun upSort() {
        loadBooks()
    }

    fun switchToGroupId(groupId: Long) {
        if (currentGroupId == groupId) return
        currentGroupId = groupId
        updateTitle()
        rebuildGroupDrawer()
        loadBooks()
    }

    override fun gotoTop() {
        binding.rvArchive.scrollToPosition(0)
    }

    /** 返回键：先收抽屉，交给 MainActivity 决定要不要退出 */
    fun back(): Boolean {
        val drawer = binding.root as? DrawerLayout ?: return false
        return if (drawer.isDrawerOpen(GravityCompat.START)) {
            drawer.closeDrawer(GravityCompat.START)
            true
        } else {
            false
        }
    }

    // ------------------------------------------------------------------ 界面

    private fun drawer(): DrawerLayout? = binding.root as? DrawerLayout

    private fun openDrawer() {
        drawer()?.openDrawer(GravityCompat.START)
    }

    private fun updateTitle() {
        val name = groupList.firstOrNull { it.groupId == currentGroupId }?.groupName
            ?: getString(R.string.all)
        binding.tvBookshelfTitle.text = name
    }

    private fun rebuildGroupDrawer() {
        val container = binding.llGroups
        container.removeAllViews()
        val context = requireContext()
        val ink = context.primaryTextColor
        val accent = context.accentColor
        groupList.forEachIndexed { index, group ->
            val selected = group.groupId == currentGroupId
            val item = TextView(context).apply {
                text = "%02d  %s".format(index + 1, group.groupName)
                textSize = 14f
                gravity = Gravity.CENTER_VERTICAL
                includeFontPadding = false
                setTextColor(if (selected) accent else ink)
                setTypeface(typeface, if (selected) Typeface.BOLD else Typeface.NORMAL)
                setPadding(20.dpToPx(), 0, 12.dpToPx(), 0)
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    44.dpToPx()
                )
                if (selected) {
                    background = GradientDrawable().apply {
                        setColor(withAlpha(accent, 26))
                        cornerRadius = 12.dpToPx().toFloat()
                    }
                }
                setOnClickListener {
                    switchToGroupId(group.groupId)
                    drawer()?.closeDrawer(GravityCompat.START)
                }
            }
            container.addView(item)
        }
    }

    private fun showSortSelector() {
        val labels = (0..5).map { getString(sortLabelRes(it)) }
        requireContext().selector(R.string.sort, labels) { _, index ->
            if (AppConfig.bookshelfSort != index) {
                AppConfig.bookshelfSort = index
                upSort()
            }
        }
    }

    private fun sortLabelRes(index: Int): Int = when (index) {
        1 -> R.string.bookshelf_px_1
        2 -> R.string.bookshelf_px_2
        3 -> R.string.bookshelf_px_3
        4 -> R.string.bookshelf_px_4
        5 -> R.string.bookshelf_px_5
        else -> R.string.bookshelf_px_0
    }

    private fun openBook(book: Book) {
        startActivityForBook(book)
    }

    private fun openBookInfo(book: Book) {
        startActivity<BookInfoActivity> {
            putExtra("name", book.name)
            putExtra("author", book.author)
        }
    }

    private fun withAlpha(color: Int, alpha: Int): Int {
        return android.graphics.Color.argb(
            alpha,
            android.graphics.Color.red(color),
            android.graphics.Color.green(color),
            android.graphics.Color.blue(color)
        )
    }
}
