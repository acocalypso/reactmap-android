package be.mygod.reactmap

import android.app.AlertDialog
import android.content.DialogInterface
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.system.ErrnoException
import android.system.Os
import android.system.OsConstants
import android.view.WindowManager
import android.webkit.WebView
import android.widget.Toast
import androidx.activity.enableEdgeToEdge
import androidx.annotation.RequiresApi
import androidx.core.content.edit
import androidx.fragment.app.FragmentActivity
import androidx.fragment.app.commit
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import be.mygod.reactmap.App.Companion.app
import be.mygod.reactmap.util.AlertDialogFragment
import be.mygod.reactmap.util.Empty
import be.mygod.reactmap.util.UnblockCentral
import be.mygod.reactmap.util.UpdateChecker
import be.mygod.reactmap.util.readableMessage
import be.mygod.reactmap.webkit.ReactMapFragment
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import timber.log.Timber
import java.io.File
import java.io.FileDescriptor
import java.io.IOException
import java.net.InetSocketAddress

class MainActivity : FragmentActivity() {
    companion object {
        const val ACTION_CONFIGURE = "be.mygod.reactmap.action.CONFIGURE"
        const val ACTION_RESTART_GAME = "be.mygod.reactmap.action.RESTART_GAME"
        private const val KEY_WELCOME = "welcome"

        private val setInt by lazy { FileDescriptor::class.java.getDeclaredMethod("setInt$", Int::class.java) }
        @get:RequiresApi(29)
        private val os by lazy { Class.forName("libcore.io.Libcore").getDeclaredField("os").get(null) }
        private val nullFd by lazy { Os.open("/dev/null", OsConstants.O_RDONLY, 0) }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        enableEdgeToEdge()
        if (BuildConfig.DEBUG) WebView.setWebContentsDebuggingEnabled(true)
        setContentView(R.layout.layout_main)
        handleIntent(intent)
        if (currentFragment == null) reactMapFragment(null)
        if (app.pref.getBoolean(KEY_WELCOME, true)) {
            startConfigure(true)
            app.pref.edit { putBoolean(KEY_WELCOME, false) }
        }
        AlertDialogFragment.setResultListener<ConfigDialogFragment, Empty>(this) { which, _ ->
            if (which != DialogInterface.BUTTON_POSITIVE) return@setResultListener
            currentFragment?.terminate()
            try {
                for (file in File("/proc/self/fd").listFiles() ?: emptyArray()) try {
                    val fdInt = file.name.toInt()
                    val fd = FileDescriptor().apply { setInt(this, fdInt) }
                    val endpoint = try {
                        Os.getsockname(fd)
                    } catch (e: ErrnoException) {
                        if (e.errno == OsConstants.EBADF || e.errno == OsConstants.ENOTSOCK) continue else throw e
                    }
                    if (endpoint !is InetSocketAddress) continue
                    val isTcp = when (val type = UnblockCentral.getsockoptInt(null, fd, OsConstants.SOL_SOCKET,
                        OsConstants.SO_TYPE)) {
                        OsConstants.SOCK_STREAM -> true
                        OsConstants.SOCK_DGRAM -> false
                        else -> {
                            Timber.w(Exception("Unknown $type to $endpoint"))
                            continue
                        }
                    }
                    val ownerTag = if (Build.VERSION.SDK_INT >= 29) try {
                        UnblockCentral.fdsanGetOwnerTag(os, fd) as Long
                    } catch (e: Exception) {
                        Timber.w(e)
                        0
                    } else 0
                    Timber.d("Resetting $fdInt owned by $ownerTag if is 0 -> $endpoint $isTcp")
                    if (ownerTag != 0L) continue
                    if (isTcp) {
                        UnblockCentral.setsockoptLinger(null, fd, OsConstants.SOL_SOCKET,
                            OsConstants.SO_LINGER, UnblockCentral.lingerReset)
                    } else Os.dup2(nullFd, fdInt)
                } catch (e: Exception) {
                    Timber.w(e)
                }
            } catch (e: IOException) {
                Timber.d(e)
            }
            reactMapFragment(null)
        }
        supportFragmentManager.setFragmentResultListener("ReactMapFragment", this) { _, _ ->
            reactMapFragment(null)
        }
        lifecycleScope.launch { repeatOnLifecycle(Lifecycle.State.STARTED) { UpdateChecker.check() } }
        // ATTENTION: This was auto-generated to handle app links.
        val appLinkIntent: Intent = intent
        val appLinkAction: String? = appLinkIntent.action
        val appLinkData: Uri? = appLinkIntent.data
    }
    override fun onNewIntent(intent: Intent?) {
        super.onNewIntent(intent)
        handleIntent(intent)
    }

    private var currentFragment: ReactMapFragment? = null
    private fun reactMapFragment(overrideUri: Uri?) = supportFragmentManager.commit {
        replace(R.id.content, ReactMapFragment(overrideUri).also { currentFragment = it })
    }

    private fun handleIntent(intent: Intent?) {
        when (intent?.action) {
            ACTION_CONFIGURE -> startConfigure(false)
            ACTION_RESTART_GAME -> AlertDialog.Builder(this).apply {
                setTitle(R.string.restart_game_dialog_title)
                setMessage(R.string.restart_game_dialog_message)
                setPositiveButton(R.string.restart_game_standard) { _, _ -> restartGame("com.nianticlabs.pokemongo") }
                setNegativeButton(R.string.restart_game_samsung) { _, _ ->
                    restartGame("com.nianticlabs.pokemongo.ares")
                }
                setNeutralButton(android.R.string.cancel, null)
            }.show()
            Intent.ACTION_VIEW -> {
                val currentFragment = currentFragment
                if (currentFragment == null) reactMapFragment(intent.data) else currentFragment.handleUri(intent.data)
            }
        }
    }
    private fun startConfigure(welcome: Boolean) = ConfigDialogFragment().apply {
        arg(ConfigDialogFragment.Arg(welcome))
        key()
    }.show(supportFragmentManager, null)
    private fun restartGame(packageName: String) {
        val intent = packageManager.getLaunchIntentForPackage(packageName) ?: return
        val wasMultiWindow = isInMultiWindowMode
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                val process = ProcessBuilder("su", "-c", "am force-stop $packageName").start()
                val exit = process.waitFor()
                if (exit != 0) Timber.w("su exited with $exit")
                if (wasMultiWindow) {
                    intent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_LAUNCH_ADJACENT)
                    delay(1000) // wait a second for animations
                }
                startActivity(intent)
            } catch (e: Exception) {
                Timber.w(e)
                withContext(Dispatchers.Main) {
                    Toast.makeText(this@MainActivity, e.readableMessage, Toast.LENGTH_LONG).show()
                }
            }
        }
    }
}
