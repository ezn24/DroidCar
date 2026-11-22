package io.github.ezn24.droidcar.launcher.settings

import android.os.Bundle
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import androidx.fragment.app.Fragment
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import io.github.ezn24.droidcar.R
import io.github.ezn24.droidcar.launcher.VirtualLauncher
import io.github.ezn24.droidcar.util.Bg

/** Settings panel host: in-page hints (head unit = in-page Toast view; phone = system Toast). */
interface SettingsToastHost {
    fun showToast(text: String)
}

/**
 * Base class of the settings content panels: shared app list (direct cache read + asynchronous cold-path scan) and host hints.
 * The app list is shared process-wide (VirtualLauncher cache), so panels open instantly once the desktop has scanned.
 */
abstract class SettingsPanelFragment : Fragment() {
    var apps: List<VirtualLauncher.AppEntry> = emptyList()
        private set

    /** List-ready callback (main thread; synchronous on a cache hit). */
    protected fun loadApps(onReady: () -> Unit) {
        val appContext = requireContext().applicationContext
        VirtualLauncher.peekCachedApps()?.takeIf { it.isNotEmpty() }?.let {
            apps = it
            onReady()
            return
        }
        Bg.run("apps-scan") {
            val scanned = VirtualLauncher.launchableApps(appContext)
            VirtualLauncher.adoptAppsCache(scanned)
            android.os.Handler(android.os.Looper.getMainLooper()).post {
                if (!isAdded || isDetached || view == null) return@post
                apps = scanned
                onReady()
            }
        }
    }

    /** Common setup of the app-list screens: LayoutManager + letter index bar + async loading (with a loading placeholder). */
    protected fun setupAppList(
        container: View,
        listId: Int,
        indexBarId: Int,
        adapter: AppRowsAdapter,
        onAppsReady: () -> Unit = {},
    ) {
        val list = container.findViewById<RecyclerView>(listId)
        list.layoutManager = LinearLayoutManager(requireContext())
        list.adapter = adapter
        SettingsRows.setupIndexBar(this, container.findViewById<LinearLayout>(indexBarId), list) { adapter.rows }
        val loading = container.findViewById<TextView>(R.id.listLoading)
        loadApps {
            loading.visibility = View.GONE
            onAppsReady()
            adapter.notifyDataSetChanged()
        }
    }

    protected fun hostToast(text: String) {
        val host = activity
        if (host is SettingsToastHost) {
            host.showToast(text)
        } else {
            android.widget.Toast
                .makeText(requireContext(), text, android.widget.Toast.LENGTH_SHORT)
                .show()
        }
    }

    override fun onViewCreated(
        view: View,
        savedInstanceState: Bundle?,
    ) {
        super.onViewCreated(view, savedInstanceState)
        onViewCreated(view)
    }

    /** Simplified onViewCreated (this page has no state-restore need). */
    protected abstract fun onViewCreated(view: View)
}
