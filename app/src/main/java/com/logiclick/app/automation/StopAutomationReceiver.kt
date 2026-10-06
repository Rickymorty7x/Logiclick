package com.logiclick.app.automation

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.logiclick.app.data.ConfigStore

class StopAutomationReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        if (intent?.action != ACTION_STOP) return
        ConfigStore(context).apply {
            armed = false
            appendLog("Stopped from notification")
        }
        LogiclickAccessibilityService.instance?.stopAutomation()
    }

    companion object {
        const val ACTION_STOP = "com.logiclick.app.STOP_AUTOMATION"
    }
}
