package io.github.ezn24.cardeck.neo.launcher

import android.content.ComponentName
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.GridLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.activity.addCallback
import androidx.appcompat.app.AppCompatActivity
import io.github.ezn24.cardeck.neo.R
import io.github.ezn24.cardeck.neo.ui.WallpaperStore
import io.github.ezn24.cardeck.neo.util.Bg

/**
 * 背景应用管理（车机侧）。2026-11-17 改成 CarPlay 主画面同一套视觉：与桌面同一张壁纸
 * 切片、4 列大图标 + 名称；图标左上角「−」关闭（iOS 编辑态同款），点图标切换过去；
 * 顶栏：返回 · 标题 · 「全部关闭」胶囊。
 *
 * 只展示本次连接开过的应用（[VirtualLauncher.sessionApps]），图标/标签和 shell 查询全在后台。
 */
class RecentAppsActivity : AppCompatActivity() {
    private data class Entry(
        val component: ComponentName,
        val title: String,
        val icon: Bitmap,
    )

    private lateinit var grid: GridLayout
    private lateinit var status: TextView
    private lateinit var empty: TextView
    private lateinit var closeAll: TextView
    private var entries = emptyList<Entry>()
    private var busy = false

    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()

    private fun pill(color: Int) =
        GradientDrawable().apply {
            cornerRadius = dp(100).toFloat()
            setColor(color)
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val root = FrameLayout(this).apply { setBackgroundColor(0xFF090B12.toInt()) }
        addWallpaper(root)
        // 壁纸上压一层暗幕，保证图标与文字可读（与桌面观感一致、略深一点区分「管理态」）
        root.addView(View(this).apply { setBackgroundColor(0x59000000) }, FrameLayout.LayoutParams(-1, -1))

        val column =
            LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(16), dp(10), dp(16), dp(8))
            }
        column.addView(buildHeader(), LinearLayout.LayoutParams(-1, dp(52)))
        status =
            TextView(this).apply {
                textSize = 13f
                setTextColor(0xCCFFFFFF.toInt())
                gravity = Gravity.CENTER
                visibility = View.GONE
            }
        column.addView(status, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(2) })

        val body = FrameLayout(this)
        grid =
            GridLayout(this).apply {
                columnCount = COLUMNS
                setPadding(0, dp(6), 0, dp(6))
            }
        body.addView(ScrollView(this).apply { addView(grid, FrameLayout.LayoutParams(-1, -2)) }, FrameLayout.LayoutParams(-1, -1))
        empty =
            TextView(this).apply {
                textSize = 17f
                setTextColor(0xB3FFFFFF.toInt())
                gravity = Gravity.CENTER
                visibility = View.GONE
            }
        body.addView(empty, FrameLayout.LayoutParams(-1, -1))
        column.addView(body, LinearLayout.LayoutParams(-1, 0, 1f))
        root.addView(column, FrameLayout.LayoutParams(-1, -1))
        setContentView(root)
        onBackPressedDispatcher.addCallback(this) { leave() }
    }

    /** 顶栏：圆形返回 · 标题（居中）· 全部关闭胶囊。 */
    private fun buildHeader(): View {
        val header = FrameLayout(this)
        header.addView(
            ImageView(this).apply {
                setImageResource(R.drawable.ic_chev_left)
                setColorFilter(Color.WHITE)
                background = pill(0x33FFFFFF)
                setPadding(dp(10), dp(10), dp(10), dp(10))
                contentDescription = getString(R.string.recent_back)
                isFocusable = true
                setOnClickListener { leave() }
            },
            FrameLayout.LayoutParams(dp(44), dp(44), Gravity.START or Gravity.CENTER_VERTICAL),
        )
        header.addView(
            TextView(this).apply {
                text = getString(R.string.recent_apps)
                textSize = 20f
                setTypeface(typeface, Typeface.BOLD)
                setTextColor(Color.WHITE)
                gravity = Gravity.CENTER
            },
            FrameLayout.LayoutParams(-2, -2, Gravity.CENTER),
        )
        closeAll =
            TextView(this).apply {
                text = getString(R.string.recent_close_all)
                textSize = 15f
                setTypeface(typeface, Typeface.BOLD)
                setTextColor(Color.WHITE)
                gravity = Gravity.CENTER
                background = pill(0xCCE5484D.toInt())
                setPadding(dp(18), 0, dp(18), 0)
                isFocusable = true
                setOnClickListener { if (!busy) close(entries.map { it.component.packageName }.toSet()) }
            }
        header.addView(closeAll, FrameLayout.LayoutParams(-2, dp(40), Gravity.END or Gravity.CENTER_VERTICAL))
        return header
    }

    /** 与桌面同一张壁纸切片（整幅画布 aspectFill，按 dock 位置偏移露出应用区那一块）。 */
    private fun addWallpaper(root: FrameLayout) {
        val scale = VirtualLauncher.gridScale()
        val canvasWidth = (VirtualLauncher.streamWidthPx * scale).toInt()
        val canvasHeight = (VirtualLauncher.streamHeightPx * scale).toInt()
        if (canvasWidth <= 0 || canvasHeight <= 0) return
        val view =
            ImageView(this).apply {
                scaleType = ImageView.ScaleType.FIT_XY
                importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
            }
        root.addView(
            view,
            FrameLayout.LayoutParams(canvasWidth, canvasHeight).apply {
                leftMargin = if (VirtualLauncher.dockAtBottom) 0 else -(VirtualLauncher.dockWidthPx * scale).toInt()
            },
        )
        Bg.run("wallpaper-recents") {
            val bitmap = runCatching { WallpaperStore.aspectFillCanvas(this, canvasWidth, canvasHeight) }.getOrNull()
            if (bitmap != null) runOnUiThread { if (!isDestroyed) view.setImageBitmap(bitmap) }
        }
    }

    override fun onResume() {
        super.onResume()
        load()
    }

    override fun onStop() {
        super.onStop()
        if (!isFinishing) finishAndRemoveTask()
    }

    private fun leave() {
        VirtualLauncher.goGrid()
        finishAndRemoveTask()
    }

    private fun busy(value: Boolean) {
        busy = value
        closeAll.isEnabled = !value && entries.isNotEmpty()
        closeAll.alpha = if (closeAll.isEnabled) 1f else 0.4f
        grid.alpha = if (value) 0.5f else 1f
    }

    private fun showStatus(message: String?) {
        status.text = message.orEmpty()
        status.visibility = if (message.isNullOrEmpty()) View.GONE else View.VISIBLE
    }

    private fun load(message: String? = null) {
        if (busy) return
        busy(true)
        showStatus(message)
        Bg.run("recent-apps") {
            val result =
                runCatching {
                    val cached = VirtualLauncher.peekCachedApps().orEmpty().associateBy { it.component.packageName }
                    VirtualLauncher.sessionApps().map { component ->
                        val pkg = component.packageName
                        val app = cached[pkg]
                        val info = runCatching { packageManager.getApplicationInfo(pkg, 0) }.getOrNull()
                        val title = app?.label ?: info?.loadLabel(packageManager)?.toString() ?: pkg
                        val icon = app?.icon ?: info?.loadIcon(packageManager) ?: packageManager.defaultActivityIcon
                        Entry(component, title, AppIcons.rounded(icon, 144))
                    }
                }
            runOnUiThread {
                if (isFinishing || isDestroyed) return@runOnUiThread
                result
                    .onSuccess {
                        entries = it
                        render()
                    }.onFailure { showStatus(getString(R.string.recent_read_failed)) }
                busy(false)
            }
        }
    }

    /** 4 列图标格：格宽 = 内容宽 / 4，图标占格宽 ~46%（与桌面网格同观感）。 */
    private fun render() {
        grid.removeAllViews()
        empty.visibility = if (entries.isEmpty()) View.VISIBLE else View.GONE
        empty.text = getString(R.string.recent_empty)
        if (entries.isEmpty()) return
        val contentWidth = resources.displayMetrics.widthPixels - dp(32)
        val cellWidth = contentWidth / COLUMNS
        val iconSize = (cellWidth * 0.46f).toInt().coerceIn(dp(48), dp(96))
        val badge = (iconSize * 0.30f).toInt().coerceAtLeast(dp(22))
        entries.forEach { entry -> grid.addView(cell(entry, iconSize, badge), GridLayout.LayoutParams().apply { width = cellWidth }) }
    }

    private fun cell(
        entry: Entry,
        iconSize: Int,
        badge: Int,
    ): View {
        val cell =
            LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER_HORIZONTAL
                setPadding(0, dp(10), 0, dp(10))
                isFocusable = true
                contentDescription = getString(R.string.recent_open_app, entry.title)
                setOnClickListener { if (!busy) open(entry) }
            }
        // 图标 + 左上角关闭徽标（徽标向外溢出一半，压在图标角上）
        val iconFrame = FrameLayout(this).apply { clipChildren = false }
        val inset = badge / 3
        iconFrame.addView(
            ImageView(this).apply {
                setImageBitmap(entry.icon)
                importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
            },
            FrameLayout.LayoutParams(iconSize, iconSize).apply {
                leftMargin = inset
                topMargin = inset
            },
        )
        iconFrame.addView(
            TextView(this).apply {
                text = "−"
                textSize = 16f
                setTypeface(typeface, Typeface.BOLD)
                setTextColor(Color.WHITE)
                gravity = Gravity.CENTER
                includeFontPadding = false
                background =
                    GradientDrawable().apply {
                        shape = GradientDrawable.OVAL
                        setColor(0xF02C2C2E.toInt())
                        setStroke(dp(1), 0x66FFFFFF)
                    }
                isFocusable = true
                contentDescription = getString(R.string.recent_close_app, entry.title)
                setOnClickListener { if (!busy) close(setOf(entry.component.packageName)) }
            },
            FrameLayout.LayoutParams(badge, badge, Gravity.TOP or Gravity.START),
        )
        cell.addView(iconFrame, LinearLayout.LayoutParams(iconSize + inset, iconSize + inset))
        cell.addView(
            TextView(this).apply {
                text = entry.title
                textSize = 13f
                setTextColor(Color.WHITE)
                gravity = Gravity.CENTER
                maxLines = 1
                ellipsize = android.text.TextUtils.TruncateAt.END
                setShadowLayer(dp(2).toFloat(), 0f, dp(1).toFloat(), 0x99000000.toInt())
            },
            LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(6) },
        )
        return cell
    }

    private fun open(entry: Entry) {
        busy(true)
        Bg.run("recent-open") {
            val ok = runCatching { VirtualLauncher.launchApp(entry.component) }.getOrDefault(false)
            runOnUiThread {
                if (isFinishing || isDestroyed) return@runOnUiThread
                if (ok) {
                    finishAndRemoveTask()
                } else {
                    busy(false)
                    showStatus(getString(R.string.recent_open_failed))
                }
            }
        }
    }

    private fun close(packages: Set<String>) {
        if (packages.isEmpty()) return
        busy(true)
        Bg.run("recent-close") {
            val ok = runCatching { VirtualLauncher.closeVehicleApps(packages) }.getOrDefault(false)
            runOnUiThread {
                if (isFinishing || isDestroyed) return@runOnUiThread
                busy(false)
                load(if (ok) null else getString(R.string.recent_close_failed))
            }
        }
    }

    private companion object {
        const val COLUMNS = 4
    }
}
