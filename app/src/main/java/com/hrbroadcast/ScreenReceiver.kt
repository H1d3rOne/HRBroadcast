package com.hrbroadcast

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build

class ScreenReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context?, intent: Intent?) {
        when (intent?.action) {
            Intent.ACTION_SCREEN_ON,
            Intent.ACTION_SCREEN_OFF -> {
                context?.let {
                    if (!HeartRateService.isRunning) {
                        val serviceIntent = Intent(it, HeartRateService::class.java)
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                            it.startForegroundService(serviceIntent)
                        } else {
                            it.startService(serviceIntent)
                        }
                    }
                }
            }
        }
    }
}
