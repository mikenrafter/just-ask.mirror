package dev.justask.app

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import dev.justask.app.databinding.ActivityMainBinding
import dev.justask.app.databinding.ItemAppBinding
import dev.justask.app.databinding.ItemIntentBinding
import dev.justask.app.databinding.ItemSectionBinding
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
    private var pendingAfterNotificationPermission: (() -> Unit)? = null
    private var pendingOnNotificationDenied: (() -> Unit)? = null
    private var suppressBootSwitchCallback = false

    private val selectedAdapter = SelectedAdapter()
    private val catalogAdapter = CatalogAdapter()

    private val notificationPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            val action = pendingAfterNotificationPermission
            val onDenied = pendingOnNotificationDenied
            pendingAfterNotificationPermission = null
            pendingOnNotificationDenied = null
            if (granted) {
                action?.invoke()
            } else {
                onDenied?.invoke()
                Toast.makeText(this, R.string.notification_permission_denied, Toast.LENGTH_LONG).show()
            }
        }

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

        binding.startOnBootSwitch.isChecked = JustAsk.isBootReceiverEnabled(this)
        binding.startOnBootSwitch.setOnCheckedChangeListener { _, checked ->
            if (suppressBootSwitchCallback) return@setOnCheckedChangeListener
            if (checked) {
                withNotificationPermission(
                    onDenied = {
                        suppressBootSwitchCallback = true
                        binding.startOnBootSwitch.isChecked = false
                        suppressBootSwitchCallback = false
                    },
                ) {
                    setStartOnBootEnabled(true)
                }
            } else {
                setStartOnBootEnabled(false)
            }
        }

        binding.launchIntentNotificationButton.setOnClickListener {
            withNotificationPermission { postIntentNotification() }
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
        maybeRequestNotificationPermissionOnLaunch()
    }

    override fun onDestroy() {
        catalogExecutor.shutdownNow()
        super.onDestroy()
    }

    private fun setStartOnBootEnabled(enabled: Boolean) {
        bootPreferences.startOnBoot = enabled
        JustAsk.setBootReceiverEnabled(this, enabled)
    }

    private fun maybeRequestNotificationPermissionOnLaunch() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return
        if (hasNotificationPermission()) return
        notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
    }

    private fun hasNotificationPermission(): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return true
        return ContextCompat.checkSelfPermission(
            this,
            Manifest.permission.POST_NOTIFICATIONS,
        ) == PackageManager.PERMISSION_GRANTED
    }

    private fun withNotificationPermission(onDenied: (() -> Unit)? = null, action: () -> Unit) {
        if (hasNotificationPermission()) {
            action()
            return
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            pendingAfterNotificationPermission = action
            pendingOnNotificationDenied = onDenied
            notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        } else {
            action()
        }
    }

    private fun postIntentNotification() {
        if (targetStore.enabledTargets().isEmpty()) {
            Toast.makeText(this, R.string.notification_no_targets, Toast.LENGTH_SHORT).show()
            return
        }
        if (JustAsk.showIntentNotification(this)) {
            Toast.makeText(this, R.string.notification_posted, Toast.LENGTH_SHORT).show()
        } else {
            Toast.makeText(this, R.string.notification_not_posted, Toast.LENGTH_LONG).show()
        }
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
                autoRegisterSdkTrampolines(apps)
                expandedPackages.addAll(apps.filter { it.isSdkHost }.map { it.packageName })
                binding.catalogStatus.text = getString(
                    R.string.catalog_ready,
                    apps.size,
                    apps.count { it.isSdkHost },
                )
                refreshSelected()
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
            onToggleComponent = ::onToggleComponent,
        )
    }

    private fun autoRegisterSdkTrampolines(apps: List<InstalledApp>) {
        val existing = targetStore.load().map { it.id }.toSet()
        var changed = false
        for (app in apps) {
            for (component in app.components) {
                if (component.isTrampolineProvider && component.canLaunch && component.id !in existing) {
                    targetStore.upsert(component.toTarget(enabled = true))
                    changed = true
                }
            }
        }
        if (changed) {
            Toast.makeText(this, R.string.sdk_hosts_registered, Toast.LENGTH_SHORT).show()
        }
    }

    private fun onToggleAppExpanded(packageName: String) {
        if (!expandedPackages.add(packageName)) {
            expandedPackages.remove(packageName)
        }
        renderCatalog()
    }

    private fun onToggleComponent(component: CatalogComponent, selected: Boolean) {
        if (!component.canLaunch) {
            Toast.makeText(this, R.string.component_not_launchable, Toast.LENGTH_SHORT).show()
            renderCatalog()
            return
        }
        if (selected) {
            val existing = targetStore.load().find { it.id == component.id }
            targetStore.upsert(component.toTarget(enabled = existing?.enabled ?: true))
        } else {
            targetStore.remove(component.id)
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
            data class Section(val titleRes: Int) : Row()
            data class Component(val component: CatalogComponent, val selected: Boolean) : Row()
        }

        private var rows: List<Row> = emptyList()
        private var onToggleApp: ((String) -> Unit)? = null
        private var onToggleComponent: ((CatalogComponent, Boolean) -> Unit)? = null

        fun submit(
            apps: List<InstalledApp>,
            expanded: Set<String>,
            selectedIds: Set<String>,
            onToggleApp: (String) -> Unit,
            onToggleComponent: (CatalogComponent, Boolean) -> Unit,
        ) {
            this.onToggleApp = onToggleApp
            this.onToggleComponent = onToggleComponent
            rows = buildList {
                for (app in apps) {
                    val selectedCount = app.components.count { it.id in selectedIds }
                    val isExpanded = app.packageName in expanded
                    add(Row.App(app, isExpanded, selectedCount))
                    if (isExpanded) {
                        addKindSection(app.components, ComponentKind.ACTIVITY, R.string.section_activities, selectedIds)
                        addKindSection(app.components, ComponentKind.SERVICE, R.string.section_services, selectedIds)
                        addKindSection(app.components, ComponentKind.RECEIVER, R.string.section_receivers, selectedIds)
                    }
                }
            }
            notifyDataSetChanged()
        }

        private fun MutableList<Row>.addKindSection(
            components: List<CatalogComponent>,
            kind: ComponentKind,
            titleRes: Int,
            selectedIds: Set<String>,
        ) {
            val group = components.filter { it.kind == kind }
            if (group.isEmpty()) return
            add(Row.Section(titleRes))
            for (component in group) {
                add(Row.Component(component, component.id in selectedIds))
            }
        }

        override fun getItemViewType(position: Int): Int = when (rows[position]) {
            is Row.App -> VIEW_APP
            is Row.Section -> VIEW_SECTION
            is Row.Component -> VIEW_COMPONENT
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
            val inflater = LayoutInflater.from(parent.context)
            return when (viewType) {
                VIEW_APP -> AppHolder(ItemAppBinding.inflate(inflater, parent, false))
                VIEW_SECTION -> SectionHolder(ItemSectionBinding.inflate(inflater, parent, false))
                else -> ComponentHolder(ItemIntentBinding.inflate(inflater, parent, false))
            }
        }

        override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
            when (val row = rows[position]) {
                is Row.App -> (holder as AppHolder).bind(row, onToggleApp)
                is Row.Section -> (holder as SectionHolder).bind(row)
                is Row.Component -> (holder as ComponentHolder).bind(row, onToggleComponent)
            }
        }

        override fun getItemCount(): Int = rows.size

        class AppHolder(private val binding: ItemAppBinding) : RecyclerView.ViewHolder(binding.root) {
            fun bind(row: Row.App, onToggleApp: ((String) -> Unit)?) {
                val app = row.app
                binding.appTitle.text = app.label
                val activities = app.components.count { it.kind == ComponentKind.ACTIVITY }
                val services = app.components.count { it.kind == ComponentKind.SERVICE }
                val receivers = app.components.count { it.kind == ComponentKind.RECEIVER }
                binding.appSubtitle.text = binding.root.context.getString(
                    R.string.app_component_count,
                    app.packageName,
                    activities,
                    services,
                    receivers,
                )
                if (app.icon != null) {
                    binding.appIcon.setImageDrawable(app.icon)
                } else {
                    binding.appIcon.setImageResource(android.R.drawable.sym_def_app_icon)
                }
                binding.sdkBadge.visibility = if (app.isSdkHost) View.VISIBLE else View.GONE
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

        class SectionHolder(private val binding: ItemSectionBinding) : RecyclerView.ViewHolder(binding.root) {
            fun bind(row: Row.Section) {
                binding.sectionTitle.setText(row.titleRes)
            }
        }

        class ComponentHolder(private val binding: ItemIntentBinding) : RecyclerView.ViewHolder(binding.root) {
            fun bind(row: Row.Component, onToggle: ((CatalogComponent, Boolean) -> Unit)?) {
                val component = row.component
                val ctx = binding.root.context
                binding.intentTitle.text = component.label
                val exportLabel = if (component.exported) {
                    ctx.getString(R.string.component_exported)
                } else {
                    ctx.getString(R.string.component_private)
                }
                val enabledLabel = if (component.enabled) {
                    exportLabel
                } else {
                    "$exportLabel · ${ctx.getString(R.string.component_disabled)}"
                }
                binding.intentKind.text = ctx.getString(
                    R.string.component_meta,
                    component.kindLabel,
                    enabledLabel,
                )
                binding.intentSubtitle.text = when {
                    component.actions.isNotEmpty() -> component.actions.joinToString(" · ")
                    else -> component.className
                }
                val badge = when {
                    component.isTrampolineProvider -> ctx.getString(R.string.trampoline_provider_badge)
                    component.isBootService -> ctx.getString(R.string.boot_service_badge)
                    else -> null
                }
                if (badge != null) {
                    binding.intentBadge.visibility = View.VISIBLE
                    binding.intentBadge.text = badge
                } else {
                    binding.intentBadge.visibility = View.GONE
                }
                binding.intentCheck.isEnabled = component.canLaunch
                binding.intentCheck.setOnCheckedChangeListener(null)
                binding.intentCheck.isChecked = row.selected
                binding.intentCheck.setOnCheckedChangeListener { _, checked ->
                    onToggle?.invoke(component, checked)
                }
                binding.root.setOnClickListener {
                    if (component.canLaunch) {
                        binding.intentCheck.isChecked = !binding.intentCheck.isChecked
                    } else {
                        onToggle?.invoke(component, false)
                    }
                }
            }
        }

        companion object {
            private const val VIEW_APP = 0
            private const val VIEW_COMPONENT = 1
            private const val VIEW_SECTION = 2
        }
    }
}
