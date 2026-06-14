package com.whatsthat.linux

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.whatsthat.linux.databinding.ActivityMainBinding
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Single-screen driver: a big action button, a live log, and a progress bar.
 * The button's job changes with state — Install Ubuntu → Install Desktop →
 * Launch Desktop — so the user always has exactly one obvious next step.
 */
class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var env: LinuxEnvironment

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        env = LinuxEnvironment(this)
        env.prepareScripts()

        refreshState()
        binding.actionButton.setOnClickListener { onAction() }
        binding.runButton.setOnClickListener { runConsoleCommand() }
        binding.cmdInput.setOnEditorActionListener { _, _, _ -> runConsoleCommand(); true }
    }

    /** Run whatever the user typed in the console, inside the container. */
    private fun runConsoleCommand() {
        val cmd = binding.cmdInput.text.toString().trim()
        if (cmd.isEmpty()) return
        binding.cmdInput.text.clear()
        appendLog("$ $cmd")
        binding.runButton.isEnabled = false
        lifecycleScope.launch {
            val code = withContext(Dispatchers.IO) { env.runCommand(cmd, ::appendLog) }
            if (code != 0) appendLog("[exit $code]")
            binding.runButton.isEnabled = true
        }
    }

    private fun refreshState() {
        binding.actionButton.text = when {
            !env.isBootstrapped -> getString(R.string.action_install_ubuntu)
            !env.isDesktopInstalled -> getString(R.string.action_install_desktop)
            else -> getString(R.string.action_launch_desktop)
        }
        binding.statusText.text = getString(R.string.status_arch, env.arch)
    }

    private fun onAction() {
        setBusy(true)
        lifecycleScope.launch {
            val result = withContext(Dispatchers.IO) {
                when {
                    !env.isBootstrapped -> env.bootstrap(::appendLog).let { Step.BOOTSTRAP to it }
                    !env.isDesktopInstalled -> env.installDesktop(::appendLog).let { Step.DESKTOP to it }
                    else -> {
                        val endpoint = env.startDesktop("1280x720", ::appendLog)
                        Step.LAUNCH to (if (endpoint != null) 0 else 1).also {
                            if (endpoint != null) launchVncViewer(endpoint)
                        }
                    }
                }
            }
            appendLog(if (result.second == 0) "✓ ${result.first} ok" else "✗ ${result.first} failed (${result.second})")
            setBusy(false)
            refreshState()
        }
    }

    /** Hand the loopback display to a VNC viewer. An embedded SurfaceView
     *  client is the next milestone; for now we open the device's viewer. */
    private fun launchVncViewer(endpoint: String) {
        val (host, port) = endpoint.split(":")
        runCatching {
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("vnc://$host:$port")))
        }.onFailure {
            appendLog(getString(R.string.hint_no_vnc_viewer, endpoint))
        }
    }

    private fun setBusy(busy: Boolean) {
        binding.actionButton.isEnabled = !busy
        binding.progress.visibility = if (busy) android.view.View.VISIBLE else android.view.View.GONE
    }

    private fun appendLog(line: String) {
        runOnUiThread {
            binding.logView.append(line + "\n")
            binding.logScroll.post { binding.logScroll.fullScroll(android.view.View.FOCUS_DOWN) }
        }
    }

    private enum class Step { BOOTSTRAP, DESKTOP, LAUNCH }
}
