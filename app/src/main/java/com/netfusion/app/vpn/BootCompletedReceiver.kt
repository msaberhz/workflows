package com.netfusion.app.vpn

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.net.VpnService
import android.os.Build
import com.netfusion.app.data.preferences.UserPreferencesRepository
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import javax.inject.Inject

@AndroidEntryPoint
class BootCompletedReceiver : BroadcastReceiver() {

    @Inject lateinit var prefsRepository: UserPreferencesRepository

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED) return
        val pendingResult = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val settings = prefsRepository.settingsFlow.first()
                if (settings.autoConnectOnBoot && VpnService.prepare(context) == null) {
                    val vpnIntent = Intent(context, NetFusionVpnService::class.java)
                        .setAction(NetFusionVpnService.ACTION_START)
                        .putExtra(NetFusionVpnService.EXTRA_MODE, settings.preferredMode.name)
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                        context.startForegroundService(vpnIntent)
                    } else {
                        context.startService(vpnIntent)
                    }
                }
            } finally {
                pendingResult.finish()
            }
        }
    }
}
