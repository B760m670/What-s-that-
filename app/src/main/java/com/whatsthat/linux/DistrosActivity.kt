package com.whatsthat.linux

import android.content.res.ColorStateList
import android.os.Bundle
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.google.android.material.button.MaterialButton
import com.google.android.material.chip.Chip
import com.whatsthat.linux.databinding.ActivityDistrosBinding
import com.whatsthat.linux.databinding.ItemDistroBinding
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Lists the available distributions as cards: each carries its own mark and
 * accent, the chips say what state it is in, and the footprint is shown because
 * these are multi-gigabyte installs and the old screen never mentioned it.
 *
 * Selecting a distro makes it active and returns; the main screen then installs
 * or launches it. Installing or removing one never touches another — each has
 * its own rootfs directory.
 */
class DistrosActivity : AppCompatActivity() {

    private lateinit var binding: ActivityDistrosBinding
    private lateinit var env: LinuxEnvironment

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityDistrosBinding.inflate(layoutInflater)
        setContentView(binding.root)
        env = LinuxEnvironment(this)

        binding.toolbar.setNavigationIcon(android.R.drawable.ic_menu_close_clear_cancel)
        binding.toolbar.setNavigationOnClickListener { finish() }
        rebuild()
    }

    private fun rebuild() {
        binding.distroList.removeAllViews()
        for (d in env.allDistros) addCard(d)
        // Systems installed when more distros were offered are still on disk and
        // still cost gigabytes. They get a card of their own so they can be
        // removed; there is nothing else useful left to do with them.
        for (dir in env.orphanedRootfs()) addOrphanCard(dir)
        updateStorageSummary()
    }

    private fun addOrphanCard(dir: java.io.File) {
        val row = ItemDistroBinding.inflate(layoutInflater, binding.distroList, true)
        val tint = color(R.color.state_absent)

        row.distroIcon.setImageResource(R.drawable.ic_distro_debian)
        row.distroIcon.imageTintList = ColorStateList.valueOf(tint)
        row.distroName.text = dir.name.replaceFirstChar { it.uppercase() }
        row.distroTagline.text = getString(R.string.orphan_tagline)
        row.card.strokeColor = color(R.color.card_outline)
        row.card.strokeWidth = 1

        row.chips.removeAllViews()
        row.chips.addView(chip(getString(R.string.orphan_chip), tint))
        val sizeChip = chip(getString(R.string.chip_measuring), tint)
        row.chips.addView(sizeChip)
        lifecycleScope.launch {
            val bytes = withContext(Dispatchers.IO) { env.diskUsageOf(dir) }
            sizeChip.text = LinuxEnvironment.formatBytes(bytes)
        }

        row.actions.removeAllViews()
        row.actions.addView(MaterialButton(this, null, com.google.android.material.R.attr.materialButtonOutlinedStyle).apply {
            text = getString(R.string.distro_remove)
            setTextColor(color(R.color.state_partial))
            setOnClickListener { confirmRemoveOrphan(dir) }
        })
    }

    private fun confirmRemoveOrphan(dir: java.io.File) {
        lifecycleScope.launch {
            val size = withContext(Dispatchers.IO) { env.diskUsageOf(dir) }
            AlertDialog.Builder(this@DistrosActivity)
                .setTitle(getString(R.string.remove_title, dir.name))
                .setMessage(removalWarning(size))
                .setNegativeButton(android.R.string.cancel, null)
                .setPositiveButton(R.string.distro_remove) { _, _ ->
                    lifecycleScope.launch {
                        withContext(Dispatchers.IO) { env.removeOrphan(dir) }
                        rebuild()
                    }
                }
                .show()
        }
    }

    private fun addCard(d: Distro) {
        val row = ItemDistroBinding.inflate(layoutInflater, binding.distroList, true)
        val accent = ContextCompat.getColor(this, d.accentRes)
        val installed = env.rootfsReady(d)
        val isActive = d.id == env.activeDistro.id

        row.distroIcon.setImageResource(d.iconRes)
        row.distroIcon.imageTintList = ColorStateList.valueOf(accent)
        row.distroName.text = d.name
        row.distroTagline.text = d.tagline

        // The active card is outlined in its own accent so it stands out without
        // an extra label competing with the chips.
        row.card.strokeColor = if (isActive) accent else color(R.color.card_outline)
        row.card.strokeWidth = if (isActive) 3 else 1

        row.chips.removeAllViews()
        if (isActive) row.chips.addView(chip(getString(R.string.chip_active), accent))
        when {
            !installed -> row.chips.addView(chip(getString(R.string.chip_not_installed), color(R.color.state_absent)))
            env.desktopReady(d) -> row.chips.addView(chip(getString(R.string.chip_desktop_ready), color(R.color.state_ready)))
            else -> row.chips.addView(chip(getString(R.string.chip_no_desktop), color(R.color.state_partial)))
        }
        if (d.experimental) row.chips.addView(chip(getString(R.string.chip_experimental), color(R.color.state_partial)))
        // Size is filled in asynchronously: walking a populated rootfs is tens of
        // thousands of files and would visibly stall the screen if done here.
        if (installed) {
            val sizeChip = chip(getString(R.string.chip_measuring), color(R.color.state_absent))
            row.chips.addView(sizeChip)
            lifecycleScope.launch {
                val bytes = withContext(Dispatchers.IO) { env.diskUsage(d) }
                sizeChip.text = LinuxEnvironment.formatBytes(bytes)
            }
        }

        row.actions.removeAllViews()
        if (!isActive) {
            row.actions.addView(MaterialButton(this, null, com.google.android.material.R.attr.materialButtonOutlinedStyle).apply {
                text = getString(R.string.distro_use)
                setOnClickListener { env.activeDistro = d; finish() }
            })
        }
        if (installed && !isActive) {
            row.actions.addView(MaterialButton(this, null, com.google.android.material.R.attr.materialButtonOutlinedStyle).apply {
                text = getString(R.string.distro_remove)
                setTextColor(color(R.color.state_partial))
                setOnClickListener { confirmRemove(d) }
            })
        }
    }

    /**
     * What the user is about to lose, said in full.
     *
     * The size is the obvious part. The part worth adding is the always-on
     * service, when one is configured: its working files live inside this
     * system, and for something like a peer-to-peer node they include an
     * identity that cannot be regenerated — delete it and the node comes back
     * as a stranger with a new name. That is not recoverable by reinstalling,
     * which is what the ordinary warning implies, so it is said separately.
     */
    private fun removalWarning(size: Long): String {
        val base = getString(R.string.remove_message, LinuxEnvironment.formatBytes(size))
        val svc = env.serviceCommand
        return if (svc.isBlank()) base
               else base + "\n\n" + getString(R.string.remove_message_service, svc)
    }

    /**
     * Removing a distro deletes gigabytes and everything installed inside it.
     * It used to happen on a single tap with no warning and no way back.
     */
    private fun confirmRemove(d: Distro) {
        lifecycleScope.launch {
            val size = withContext(Dispatchers.IO) { env.diskUsage(d) }
            AlertDialog.Builder(this@DistrosActivity)
                .setTitle(getString(R.string.remove_title, d.name))
                .setMessage(removalWarning(size))
                .setNegativeButton(android.R.string.cancel, null)
                .setPositiveButton(R.string.distro_remove) { _, _ ->
                    lifecycleScope.launch {
                        withContext(Dispatchers.IO) { env.removeDistro(d) }
                        rebuild()
                    }
                }
                .show()
        }
    }

    private fun updateStorageSummary() {
        lifecycleScope.launch {
            val (used, free) = withContext(Dispatchers.IO) { env.totalDiskUsage() to env.freeSpace() }
            binding.storageSummary.text = getString(
                R.string.storage_summary,
                LinuxEnvironment.formatBytes(used),
                LinuxEnvironment.formatBytes(free),
            )
        }
    }

    private fun color(res: Int) = ContextCompat.getColor(this, res)

    private fun chip(label: String, tint: Int) = Chip(this).apply {
        text = label
        isClickable = false
        isCheckable = false
        chipStrokeWidth = 1f
        chipStrokeColor = ColorStateList.valueOf(tint)
        setTextColor(tint)
        chipBackgroundColor = ColorStateList.valueOf(tint and 0x18FFFFFF)
        chipMinHeight = 30f * resources.displayMetrics.density
        textSize = 12f
    }
}
