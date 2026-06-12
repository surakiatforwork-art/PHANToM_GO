package com.phantom.carnavrelay

import android.app.KeyguardManager
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.view.View
import android.view.WindowManager
import androidx.appcompat.app.AppCompatActivity
import androidx.fragment.app.Fragment

class MainActivity : AppCompatActivity() {

    companion object {
        private const val EXTRA_INITIAL_TAB = "initial_tab"
        private const val STATE_CURRENT_TAB = "current_tab"
        const val EXTRA_WAKE_SCREEN = "wake_screen"

        fun createIntent(context: Context, tab: BottomNavHelper.Tab = BottomNavHelper.Tab.HOME): Intent {
            return Intent(context, MainActivity::class.java).apply {
                putExtra(EXTRA_INITIAL_TAB, tab.name)
                addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            }
        }
    }

    private lateinit var bottomNav: View
    private var currentTab: BottomNavHelper.Tab = BottomNavHelper.Tab.HOME
    private var previousTab: BottomNavHelper.Tab = BottomNavHelper.Tab.HOME

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        applyWakeIfRequested(intent)
        setContentView(R.layout.activity_main_shell)

        bottomNav = findViewById(R.id.bottomDock)

        currentTab = savedInstanceState?.getString(STATE_CURRENT_TAB)?.let {
            BottomNavHelper.Tab.fromName(it)
        } ?: intent?.getStringExtra(EXTRA_INITIAL_TAB)?.let {
            BottomNavHelper.Tab.fromName(it)
        } ?: if (PrefsManager(this).getDeviceMode() == PrefsManager.MODE_DISPLAY) {
            BottomNavHelper.Tab.DISPLAY
        } else {
            BottomNavHelper.Tab.HOME
        }
        previousTab = BottomNavHelper.Tab.HOME

        bindBottomNavigation()

        if (savedInstanceState == null) {
            showTab(currentTab, animate = false)
        } else {
            updateBottomNavigation()
        }

        maybeLaunchInitialPermissionSetup()
        CrashReporter.checkAndShowCrashReport(this)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        applyWakeIfRequested(intent)
        intent.getStringExtra(EXTRA_INITIAL_TAB)?.let { tabName ->
            selectTab(BottomNavHelper.Tab.fromName(tabName))
        }
    }

    /**
     * When launched by Display power automation (charger connected), turn the screen on and
     * show over the keyguard. This is the reliable, non-deprecated way to wake a modern device.
     */
    private fun applyWakeIfRequested(intent: Intent?) {
        if (intent?.getBooleanExtra(EXTRA_WAKE_SCREEN, false) != true) return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            setShowWhenLocked(true)
            setTurnScreenOn(true)
            (getSystemService(Context.KEYGUARD_SERVICE) as? KeyguardManager)
                ?.requestDismissKeyguard(this, null)
        } else {
            @Suppress("DEPRECATION")
            window.addFlags(
                WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or
                    WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON or
                    WindowManager.LayoutParams.FLAG_DISMISS_KEYGUARD
            )
        }
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
    }

    fun selectTab(tab: BottomNavHelper.Tab, animate: Boolean = true) {
        if (tab == currentTab) {
            updateBottomNavigation()
            return
        }
        previousTab = currentTab
        showTab(tab, animate)
    }

    fun navigateBackToPreviousTab() {
        if (currentTab == BottomNavHelper.Tab.HOME) {
            onBackPressedDispatcher.onBackPressed()
            return
        }

        val target = if (previousTab == currentTab) BottomNavHelper.Tab.HOME else previousTab
        selectTab(target)
    }

    private fun bindBottomNavigation() {
        BottomNavHelper.bind(bottomNav, currentTab) { tab ->
            selectTab(tab)
        }
    }

    private fun updateBottomNavigation() {
        BottomNavHelper.bind(bottomNav, currentTab) { tab ->
            selectTab(tab)
        }
    }

    private fun showTab(tab: BottomNavHelper.Tab, animate: Boolean) {
        val fragment = createFragment(tab)
        val transaction = supportFragmentManager.beginTransaction()
        if (animate) {
            if (tab.index > currentTab.index) {
                transaction.setCustomAnimations(
                    R.anim.slide_in_right,
                    R.anim.slide_out_left
                )
            } else {
                transaction.setCustomAnimations(
                    R.anim.slide_in_left,
                    R.anim.slide_out_right
                )
            }
        }

        currentTab = tab
        transaction.replace(R.id.mainContentContainer, fragment, tab.name)
        transaction.commit()
        updateBottomNavigation()
    }

    private fun createFragment(tab: BottomNavHelper.Tab): Fragment {
        return when (tab) {
            BottomNavHelper.Tab.HOME -> HomeFragment()
            BottomNavHelper.Tab.DISPLAY -> DisplayScreenFragment()
            BottomNavHelper.Tab.SETTINGS -> SettingsScreenFragment()
            BottomNavHelper.Tab.SECURITY -> SecurityFragment()
        }
    }

    private fun maybeLaunchInitialPermissionSetup() {
        val prefsManager = PrefsManager(this)
        if (prefsManager.hasShownInitialPermissionSetup()) return
        startActivity(Intent(this, PermissionSetupActivity::class.java))
    }

    override fun onBackPressed() {
        val currentFragment = supportFragmentManager.findFragmentById(R.id.mainContentContainer)
        if (currentFragment is BackPressHandler && currentFragment.handleBackPressed()) {
            return
        }
        super.onBackPressed()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putString(STATE_CURRENT_TAB, currentTab.name)
    }
}
