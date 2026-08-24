package dev.justask.app

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import dev.justask.app.databinding.ActivityMainBinding
import dev.justask.app.databinding.DialogAddTargetBinding
import dev.justask.app.databinding.ItemTargetBinding
import dev.justask.sdk.JustAsk
import dev.justask.sdk.JustAskBootPreferences
import dev.justask.sdk.JustAskLauncher
import dev.justask.sdk.JustAskTarget
import java.util.UUID

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var targetStore: TargetStore
    private lateinit var bootPreferences: JustAskBootPreferences
    private val adapter = TargetAdapter()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        targetStore = TargetStore(this)
        bootPreferences = JustAskBootPreferences(this)

        setSupportActionBar(binding.toolbar)

        binding.targetList.layoutManager = LinearLayoutManager(this)
        binding.targetList.adapter = adapter

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

        binding.addTargetButton.setOnClickListener { showAddTargetDialog() }
        binding.discoverButton.setOnClickListener { showDiscoverDialog() }

        refreshTargets()
    }

    private fun refreshTargets() {
        adapter.submit(targetStore.load(), ::onToggleTarget, ::onDeleteTarget)
        binding.emptyState.visibility =
            if (adapter.itemCount == 0) View.VISIBLE else View.GONE
    }

    private fun onToggleTarget(target: JustAskTarget, enabled: Boolean) {
        targetStore.upsert(target.copy(enabled = enabled))
        refreshTargets()
    }

    private fun onDeleteTarget(target: JustAskTarget) {
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.delete_target_title)
            .setMessage(getString(R.string.delete_target_message, target.displayLabel))
            .setPositiveButton(R.string.delete) { _, _ ->
                targetStore.remove(target.id)
                refreshTargets()
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun showAddTargetDialog(existing: JustAskTarget? = null) {
        val dialogBinding = DialogAddTargetBinding.inflate(layoutInflater)
        existing?.let {
            dialogBinding.labelInput.setText(it.label)
            dialogBinding.packageInput.setText(it.componentPackage.orEmpty())
            dialogBinding.classInput.setText(it.componentClass.orEmpty())
            dialogBinding.actionInput.setText(it.intentAction.orEmpty())
            dialogBinding.dataInput.setText(it.intentData.orEmpty())
        }

        val dialog = MaterialAlertDialogBuilder(this)
            .setTitle(if (existing == null) R.string.add_target_title else R.string.edit_target_title)
            .setView(dialogBinding.root)
            .setPositiveButton(R.string.save, null)
            .setNegativeButton(android.R.string.cancel, null)
            .create()

        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val label = dialogBinding.labelInput.text?.toString()?.trim().orEmpty()
                val pkg = dialogBinding.packageInput.text?.toString()?.trim().orEmpty()
                val cls = dialogBinding.classInput.text?.toString()?.trim().orEmpty()
                val action = dialogBinding.actionInput.text?.toString()?.trim().orEmpty()

                if (pkg.isBlank() && action.isBlank()) {
                    Toast.makeText(this, R.string.add_target_validation, Toast.LENGTH_SHORT).show()
                    return@setOnClickListener
                }
                if (pkg.isNotBlank() && cls.isBlank()) {
                    Toast.makeText(this, R.string.add_target_validation_class, Toast.LENGTH_SHORT).show()
                    return@setOnClickListener
                }

                val id = existing?.id ?: UUID.randomUUID().toString()
                val target = JustAskTarget(
                    id = id,
                    label = label,
                    enabled = existing?.enabled ?: true,
                    componentPackage = pkg.ifBlank { null },
                    componentClass = cls.ifBlank { null },
                    intentAction = action.ifBlank { null },
                    intentData = dialogBinding.dataInput.text?.toString()?.trim()?.ifBlank { null },
                    intentType = null,
                    intentFlags = 0,
                )
                targetStore.upsert(target)
                refreshTargets()
                dialog.dismiss()
            }
        }
        dialog.show()
    }

    private fun showDiscoverDialog() {
        val discovered = JustAskLauncher.discoverProviders(this)
        if (discovered.isEmpty()) {
            Toast.makeText(this, R.string.discover_empty, Toast.LENGTH_SHORT).show()
            return
        }

        val labels = discovered.map { "${it.displayLabel} (${it.componentPackage})" }.toTypedArray()
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.discover_title)
            .setItems(labels) { _, which ->
                val target = discovered[which]
                val existing = targetStore.load().any { it.id == target.id }
                if (existing) {
                    Toast.makeText(this, R.string.discover_already_added, Toast.LENGTH_SHORT).show()
                } else {
                    targetStore.upsert(target)
                    refreshTargets()
                }
            }
            .show()
    }

    private class TargetAdapter : RecyclerView.Adapter<TargetAdapter.Holder>() {

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
            val binding = ItemTargetBinding.inflate(
                LayoutInflater.from(parent.context),
                parent,
                false,
            )
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
}
