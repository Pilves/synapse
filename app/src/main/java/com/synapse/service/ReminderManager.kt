package com.synapse.service

import android.content.Context
import android.content.Intent
import android.provider.AlarmClock
import android.provider.CalendarContract
import com.synapse.model.IntentData
import java.util.Calendar

class ReminderManager(private val context: Context) {

    fun createAlarm(reminderData: IntentData.ReminderData, label: String) {
        val intent = Intent(AlarmClock.ACTION_SET_ALARM).apply {
            putExtra(AlarmClock.EXTRA_MESSAGE, label)
            reminderData.parsedTime?.let { time ->
                val calendar = Calendar.getInstance().apply { timeInMillis = time }
                putExtra(AlarmClock.EXTRA_HOUR, calendar.get(Calendar.HOUR_OF_DAY))
                putExtra(AlarmClock.EXTRA_MINUTES, calendar.get(Calendar.MINUTE))
            }
            putExtra(AlarmClock.EXTRA_SKIP_UI, false)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }

        if (intent.resolveActivity(context.packageManager) != null) {
            context.startActivity(intent)
        }
    }

    fun createCalendarEvent(reminderData: IntentData.ReminderData, title: String) {
        val intent = Intent(Intent.ACTION_INSERT).apply {
            data = CalendarContract.Events.CONTENT_URI
            putExtra(CalendarContract.Events.TITLE, title)
            reminderData.parsedTime?.let { time ->
                putExtra(CalendarContract.EXTRA_EVENT_BEGIN_TIME, time)
                putExtra(CalendarContract.EXTRA_EVENT_END_TIME, time + 3600000) // +1 hour
            }
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }

        context.startActivity(intent)
    }

    fun canCreateAlarms(): Boolean {
        val intent = Intent(AlarmClock.ACTION_SET_ALARM)
        return intent.resolveActivity(context.packageManager) != null
    }

    fun canCreateCalendarEvents(): Boolean {
        val intent = Intent(Intent.ACTION_INSERT).apply {
            data = CalendarContract.Events.CONTENT_URI
        }
        return intent.resolveActivity(context.packageManager) != null
    }
}
