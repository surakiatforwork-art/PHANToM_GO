package com.phantom.carnavrelay

import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.view.View
import androidx.appcompat.app.AppCompatActivity
import androidx.fragment.app.Fragment

class MainActivity : AppCompatActivity() {

    companion object {
        private const val EXTRA_INITIAL_TAB = "initial_tab"
        private const val STATE_CURRENT_TAB = "current_tab"

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
        intent.getStringExtra(EXTRA_INITIAL_TAB)?.let { tabName ->
            selectTab(BottomNavHelper.Tab.fromName(tabName))
        }
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
