package com.noordrive.app

import android.service.notification.NotificationListenerService

/**
 * Intentionally empty. Having an enabled notification listener is what lets Noor Drive
 * read the "now playing" media sessions of other apps (YouTube, YouTube Music, Spotify...).
 * The user switches it on in Android Settings > Notification access.
 */
class NoorNotificationListener : NotificationListenerService()
