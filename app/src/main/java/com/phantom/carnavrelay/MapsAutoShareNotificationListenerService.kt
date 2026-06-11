package com.phantom.carnavrelay

import android.app.Notification
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification

class MapsAutoShareNotificationListenerService : NotificationListenerService() {

    override fun onNotificationPosted(sbn: StatusBarNotification?) {
        if (sbn?.packageName != MapsUiVocabulary.MAPS_PACKAGE) return

        val notification = sbn.notification ?: return
        val extras = notification.extras
        val signalValues = listOf(
            extras.getCharSequence(Notification.EXTRA_TITLE),
            extras.getCharSequence(Notification.EXTRA_TEXT),
            extras.getCharSequence(Notification.EXTRA_BIG_TEXT),
            extras.getCharSequence(Notification.EXTRA_SUB_TEXT)
        )
        // Use the literal value to remain safe on API 23-27 where the constant is unavailable.
        val isNavigationCategory = notification.category == "navigation"
        if (!isNavigationCategory && !MapsUiVocabulary.isLikelyNavigationSignal(signalValues)) return

        MapsAutoShareCoordinator.onNotificationSignal(applicationContext, sbn.key)
    }

    override fun onNotificationRemoved(sbn: StatusBarNotification?) {
        if (sbn?.packageName == MapsUiVocabulary.MAPS_PACKAGE) {
            MapsAutoShareCoordinator.onNotificationRemoved(sbn.key)
        }
    }
}
