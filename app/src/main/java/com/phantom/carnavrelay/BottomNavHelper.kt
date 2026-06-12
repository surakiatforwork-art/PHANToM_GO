package com.phantom.carnavrelay

import android.content.Context
import android.view.View
import android.widget.ImageView
import android.widget.TextView

object BottomNavHelper {

    enum class Tab(val index: Int) {
        HOME(0),
        DISPLAY(1),
        SETTINGS(2),
        SECURITY(3);

        companion object {
            fun fromName(name: String?): Tab {
                return entries.firstOrNull { it.name == name } ?: HOME
            }
        }
    }

    fun bind(root: View, currentTab: Tab, onTabSelected: (Tab) -> Unit) {
        val homeAction = root.findViewById<View>(R.id.navHomeAction) ?: return
        val displayAction = root.findViewById<View>(R.id.navDisplayAction) ?: return
        val settingsAction = root.findViewById<View>(R.id.navSettingsAction) ?: return
        val securityAction = root.findViewById<View>(R.id.navSecurityAction) ?: return

        val homeIcon = root.findViewById<ImageView>(R.id.navHomeIcon)
        val displayIcon = root.findViewById<ImageView>(R.id.navDisplayIcon)
        val settingsIcon = root.findViewById<ImageView>(R.id.navSettingsIcon)
        val securityIcon = root.findViewById<ImageView>(R.id.navSecurityIcon)

        val homeLabel = root.findViewById<TextView>(R.id.navHomeLabel)
        val displayLabel = root.findViewById<TextView>(R.id.navDisplayLabel)
        val settingsLabel = root.findViewById<TextView>(R.id.navSettingsLabel)
        val securityLabel = root.findViewById<TextView>(R.id.navSecurityLabel)

        styleItem(root.context, homeAction, homeIcon, homeLabel, currentTab == Tab.HOME)
        styleItem(root.context, displayAction, displayIcon, displayLabel, currentTab == Tab.DISPLAY)
        styleItem(root.context, settingsAction, settingsIcon, settingsLabel, currentTab == Tab.SETTINGS)
        styleItem(root.context, securityAction, securityIcon, securityLabel, currentTab == Tab.SECURITY)

        homeAction.setOnClickListener { onTabSelected(Tab.HOME) }
        displayAction.setOnClickListener { onTabSelected(Tab.DISPLAY) }
        settingsAction.setOnClickListener { onTabSelected(Tab.SETTINGS) }
        securityAction.setOnClickListener { onTabSelected(Tab.SECURITY) }
    }

    private fun styleItem(
        context: Context,
        action: View,
        icon: ImageView?,
        label: TextView?,
        isActive: Boolean
    ) {
        val activeColor = context.getColor(R.color.ui_nav_active_content)
        val inactiveColor = context.getColor(R.color.ui_text_secondary)
        action.isEnabled = !isActive
        action.isClickable = !isActive
        action.alpha = if (isActive) 1f else 0.94f
        action.setBackgroundResource(
            if (isActive) R.drawable.main_bottom_nav_active else R.drawable.bottom_nav_item_idle
        )
        icon?.setColorFilter(if (isActive) activeColor else inactiveColor)
        label?.setTextColor(if (isActive) activeColor else inactiveColor)
    }
}
