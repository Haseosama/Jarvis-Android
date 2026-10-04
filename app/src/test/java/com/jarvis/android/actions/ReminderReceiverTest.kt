package com.jarvis.android.actions

import com.jarvis.android.reminders.ReminderAlarm
import org.junit.Assert.assertEquals
import org.junit.Test

class ReminderReceiverTest {
    @Test
    fun `reminder alarms still name the receiver`() {
        // the reminders package names the receiver by its class name; alarms already set on phones name it too
        assertEquals(ReminderReceiver::class.java.name, ReminderAlarm.RECEIVER_CLASS)
    }
}
