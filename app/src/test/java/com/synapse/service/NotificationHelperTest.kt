package com.synapse.service

import android.app.NotificationManager
import android.content.Context
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

@RunWith(RobolectricTestRunner::class)
class NotificationHelperTest {

    private lateinit var context: Context
    private lateinit var helper: NotificationHelper
    private lateinit var notificationManager: NotificationManager

    @Before
    fun setup() {
        context = RuntimeEnvironment.getApplication()
        helper = NotificationHelper(context)
        notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
    }

    @Test
    fun `createNotificationChannels creates foreground channel`() {
        helper.createNotificationChannels()

        val channel = notificationManager.getNotificationChannel(
            NotificationHelper.CHANNEL_ID_FOREGROUND
        )
        assertNotNull(channel)
        assertEquals(NotificationManager.IMPORTANCE_MIN, channel!!.importance)
    }

    @Test
    fun `createNotificationChannels creates sync status channel`() {
        helper.createNotificationChannels()

        val channel = notificationManager.getNotificationChannel(
            NotificationHelper.CHANNEL_ID_SYNC_STATUS
        )
        assertNotNull(channel)
        assertEquals(NotificationManager.IMPORTANCE_DEFAULT, channel!!.importance)
    }

    @Test
    fun `foreground channel has badge disabled`() {
        helper.createNotificationChannels()

        val channel = notificationManager.getNotificationChannel(
            NotificationHelper.CHANNEL_ID_FOREGROUND
        )
        assertNotNull(channel)
        assertEquals(false, channel!!.canShowBadge())
    }

    @Test
    fun `createNotificationChannels is idempotent`() {
        helper.createNotificationChannels()
        helper.createNotificationChannels()

        val channels = notificationManager.notificationChannels
        val synapseChannels = channels.filter {
            it.id == NotificationHelper.CHANNEL_ID_FOREGROUND ||
            it.id == NotificationHelper.CHANNEL_ID_SYNC_STATUS
        }
        assertEquals(2, synapseChannels.size)
    }
}
