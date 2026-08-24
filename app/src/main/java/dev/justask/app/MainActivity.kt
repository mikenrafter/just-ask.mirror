package dev.justask.app

import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import dev.justask.app.databinding.ActivityMainBinding
import dev.justask.app.databinding.ItemAppBinding
import dev.justask.app.databinding.ItemIntentBinding
import dev.justask.app.databinding.ItemTargetBinding
import dev.justask.sdk.JustAsk
import dev.justask.sdk.JustAskBootPreferences
import dev.justask.sdk.JustAskTarget
import java.util.concurrent.Executors

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var targetStore: TargetStore
    private lateinit var bootPreferences: JustAskBootPreferences

    private val catalogExecutor = Executors.newSingleThreadExecutor()
    private var allApps: List<InstalledApp> = emptyList()
    private var searchQuery: String = ""
    private val expandedPackages = mutableSetOf<String>()

    private val selectedAdapter = SelectedAdapter()
    private val catalogAdapter = CatalogAdapter()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        targetStore = TargetStore(this)
        bootPreferences = JustAskBootPreferences(this)

        setSupportActionBar(binding.toolbar)

        binding.selectedList.layoutManager = LinearLayoutManager(this)
        binding.selectedList.adapter = selectedAdapter
        binding.appList.layoutManager = LinearLayoutManager(this)
        binding.appList.adapter = catalogAdapter

        binding.startOnBootSwitch.isChecked = bootPreferences.startOnBoot
        binding.startOnBootSwitch.setOnCheckedChangeListener { _, checked ->
            bootPreferences.startOnBoot = checked
            JustAsk.setBootReceiverEnabled(this, checked)
        }

        binding.launchNowButton.setOnClickListener {
            val results = JustAsk.launchTargets(this, targetStore.enabledTargets())
            val launched = results.count { it.launched }
            Toast.makeText(
                this,
                getString(R.string.launch_result_toast, launched, results.size),
                Toast.LENGTH_SHORT,
            ).show()
        }

        binding.searchInput.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = Unit
            override fun afterTextChanged(s: Editable?) {
                searchQuery = s?.toString().orEmpty()
                renderCatalog()
            }
        })

        refreshSelected()
        loadCatalog()
    }

    override fun onDestroy() {
        catalogExecutor.shutdownNow()
        super.onDestroy()
    }

    private fun loadCatalog() {
        binding.catalogStatus.setText(R.string.catalog_loading)
        catalogExecutor.execute {
            val apps = try {
                AppCatalog.load(packageManager)
            } catch (e: Exception) {
                runOnUiThread {
                    binding.catalogStatus.text = getString(R.string.catalog_error, e.message ?: "unknown")
                }
                return@execute
            }
            runOnUiThread {
                allApps = apps
                binding.catalogStatus.text = getString(R.string.catalog_ready, apps.size)
                renderCatalog()
            }
        }
    }

    private fun refreshSelected() {
        val targets = targetStore.load()
        selectedAdapter.submit(targets, ::onToggleTarget, ::onDeleteTarget)
        binding.selectedEmpty.visibility = if (targets.isEmpty()) View.VISIBLE else View.GONE
        binding.selectedHeading.text = getString(R.string.selected_heading_count, targets.size)
        renderCatalog()
    }

    private fun renderCatalog() {
        val selectedIds = targetStore.load().map { it.id }.toSet()
        val filtered = allApps.filter { it.matches(searchQuery) }
        catalogAdapter.submit(
            apps = filtered,
            expanded = expandedPackages,
            selectedIds = selectedIds,
            onToggleApp = ::onToggleAppExpanded,
            onToggleIntent = ::onToggleIntent,
        )
    }

    private fun onToggleAppExpanded(packageName: String) {
        if (!expandedPackages.add(packageName)) {
            expandedPackages.remove(packageName)
        }
        renderCatalog()
    }

    private fun onToggleIntent(intent: LaunchableIntent, selected: Boolean) {
        if (selected) {
            val existing = targetStore.load().find { it.id == intent.id }
            targetStore.upsert(intent.toTarget(enabled = existing?.enabled ?: true))
        } else {
            targetStore.remove(intent.id)
        }
        refreshSelected()
    }

    private fun onToggleTarget(target: JustAskTarget, enabled: Boolean) {
        targetStore.upsert(target.copy(enabled = enabled))
        refreshSelected()
    }

    private fun onDeleteTarget(target: JustAskTarget) {
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.delete_target_title)
            .setMessage(getString(R.string.delete_target_message, target.displayLabel))
            .setPositiveButton(R.string.delete) { _, _ ->
                targetStore.remove(target.id)
                refreshSelected()
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private class SelectedAdapter : RecyclerView.Adapter<SelectedAdapter.Holder>() {
        private var items: List<JustAskTarget> = emptyList()
        private var onToggle: ((JustAskTarget, Boolean) -> Unit)? = null
        private var onDelete: ((JustAskTarget) -> Unit)? = null

        fun submit(
            targets: List<JustAskTarget>,
            onToggle: (JustAskTarget, Boolean) -> Unit,
            onDelete: (JustAskTarget) -> Unit,
        ) {
            items = targets
            this.onToggle = onToggle
            this.onDelete = onDelete
            notifyDataSetChanged()
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder {
            val binding = ItemTargetBinding.inflate(LayoutInflater.from(parent.context), parent, false)
            return Holder(binding)
        }

        override fun onBindViewHolder(holder: Holder, position: Int) {
            holder.bind(items[position], onToggle, onDelete)
        }

        override fun getItemCount(): Int = items.size

        class Holder(private val binding: ItemTargetBinding) : RecyclerView.ViewHolder(binding.root) {
            fun bind(
                target: JustAskTarget,
                onToggle: ((JustAskTarget, Boolean) -> Unit)?,
                onDelete: ((JustAskTarget) -> Unit)?,
            ) {
                binding.targetTitle.text = target.displayLabel
                binding.targetSubtitle.text = target.componentName()?.flattenToString()
                    ?: target.intentAction
                    ?: target.id
                binding.enabledSwitch.setOnCheckedChangeListener(null)
                binding.enabledSwitch.isChecked = target.enabled
                binding.enabledSwitch.setOnCheckedChangeListener { _, checked ->
                    onToggle?.invoke(target, checked)
                }
                binding.deleteButton.setOnClickListener { onDelete?.invoke(target) }
            }
        }
    }

    private class CatalogAdapter : RecyclerView.Adapter<RecyclerView.ViewHolder>() {

        private sealed class Row {
            data class App(val app: InstalledApp, val expanded: Boolean, val selectedCount: Int) : Row()
            data class Intent(val intent: LaunchableIntent, val selected: Boolean) : Row()
        }

        private var rows: List<Row> = emptyList()
        private var onToggleApp: ((String) -> Unit)? = null
        private var onToggleIntent: ((LaunchableIntent, Boolean) -> Unit)? = null

        fun submit(
            apps: List<InstalledApp>,
            expanded: Set<String>,
            selectedIds: Set<String>,
            onToggleApp: (String) -> Unit,
            onToggleIntent: (LaunchableIntent, Boolean) -> Unit,
        ) {
            this.onToggleApp = onToggleApp
            this.onToggleIntent = onToggleIntent
            rows = buildList {
                for (app in apps) {
                    val selectedCount = app.intents.count { it.id in selectedIds }
                    val isExpanded = app.packageName in expanded
                    add(Row.App(app, isExpanded, selectedCount))
                    if (isExpanded) {
                        for (intent in app.intents) {
                            add(Row.Intent(intent, intent.id in selectedIds))
                        }
                    }
                }
            }
            notifyDataSetChanged()
        }

        override fun getItemViewType(position: Int): Int = when (rows[position]) {
            is Row.App -> VIEW_APP
            is Row.Intent -> VIEW_INTENT
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
            val inflater = LayoutInflater.from(parent.context)
            return when (viewType) {
                VIEW_APP -> AppHolder(ItemAppBinding.inflate(inflater, parent, false))
                else -> IntentHolder(ItemIntentBinding.inflate(inflater, parent, false))
            }
        }

        override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
            when (val row = rows[position]) {
                is Row.App -> (holder as AppHolder).bind(row, onToggleApp)
                is Row.Intent -> (holder as IntentHolder).bind(row, onToggleIntent)
            }
        }

        override fun getItemCount(): Int = rows.size

        class AppHolder(private val binding: ItemAppBinding) : RecyclerView.ViewHolder(binding.root) {
            fun bind(row: Row.App, onToggleApp: ((String) -> Unit)?) {
                val app = row.app
                binding.appTitle.text = app.label
                binding.appSubtitle.text = binding.root.context.getString(
                    R.string.app_intent_count,
                    app.packageName,
                    app.intents.size,
                )
                if (app.icon != null) {
                    binding.appIcon.setImageDrawable(app.icon)
                } else {
                    binding.appIcon.setImageResource(android.R.drawable.sym_def_app_icon)
                }
                if (row.selectedCount > 0) {
                    binding.selectedBadge.visibility = View.VISIBLE
                    binding.selectedBadge.text = binding.root.context.getString(
                        R.string.app_selected_count,
                        row.selectedCount,
                    )
                } else {
                    binding.selectedBadge.visibility = View.GONE
                }
                binding.expandChevron.rotation = if (row.expanded) 180f else 0f
                binding.root.setOnClickListener { onToggleApp?.invoke(app.packageName) }
            }
        }

        class IntentHolder(private val binding: ItemIntentBinding) : RecyclerView.ViewHolder(binding.root) {
            fun bind(row: Row.Intent, onToggleIntent: ((LaunchableIntent, Boolean) -> Unit)?) {
                val intent = row.intent
                binding.intentTitle.text = intent.activityLabel
                binding.intentSubtitle.text = intent.subtitle
                binding.intentBadge.visibility =
                    if (intent.isTrampolineProvider) View.VISIBLE else View.GONE
                binding.intentCheck.setOnCheckedChangeListener(null)
                binding.intentCheck.isChecked = row.selected
                binding.intentCheck.setOnCheckedChangeListener { _, checked ->
                    onToggleIntent?.invoke(intent, checked)
                }
                binding.root.setOnClickListener {
                    binding.intentCheck.isChecked = !binding.intentCheck.isChecked
                }
            }
        }

        companion object {
            private const val VIEW_APP = 0
            private const val VIEW_INTENT = 1
        }
    }
}
