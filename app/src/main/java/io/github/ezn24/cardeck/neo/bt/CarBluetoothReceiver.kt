package io.github.ezn24.cardeck.neo.bt

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import io.github.ezn24.cardeck.neo.service.CarPlayService
import io.github.ezn24.cardeck.neo.util.DebugFileLog

/**
 * Auto-connect when getting in the car / auto-teardown when leaving.
 *
 * **Why a manifest-registered receiver**: `ACTION_ACL_CONNECTED` / `ACTION_ACL_DISCONNECTED` are on the
 * implicit broadcast exception list (explicitly listed in the Android developer docs "Implicit broadcast exceptions"), so even if the
 * app process was killed, the moment the head unit Bluetooth connects it can start the process.
 *
 * **Why a foreground service may start**: Android 12+ restricts background FGS starts, and the exemption list has **no**
 * "Bluetooth broadcast" entry; this app can start because it holds `SYSTEM_ALERT_WINDOW` (the dim overlay / touch probe
 * need it), an official exemption (under targetSdk 34 only the permission must exist; Android 15+ additionally
 * requires a visible overlay window at that moment). Without the overlay permission `startForegroundService` throws
 * `ForegroundServiceStartNotAllowedException`; we only log it, and the user can open the app and connect once by hand
 * (that time a visible Activity exists, so no restriction applies).
 *
 * **Target filter**: the user-selected target head unit ([TargetDevicePrefs]) wins; with none selected we fall back to head unit name
 * keywords. ACL connections of headsets/watches do not trigger it by mistake.
 *
 * Audio note: auto-connect only pushes video, because AudioPlaybackCapture needs a MediaProjection grant token and
 * the system does not allow acquiring it silently without one (appops PROJECT_MEDIA is only one gate and yields no token).
 */
class CarBluetoothReceiver : BroadcastReceiver() {
    @SuppressLint("MissingPermission")
    override fun onReceive(
        context: Context,
        intent: Intent,
    ) {
        val action = intent.action ?: return
        if (action != BluetoothDevice.ACTION_ACL_CONNECTED &&
            action != BluetoothDevice.ACTION_ACL_DISCONNECTED
        ) {
            return
        }
        val device = deviceOf(intent) ?: return
        val app = context.applicationContext
        // "Auto-connect when getting in the car" switch (Settings -> Display, default on)
        if (!AutoConnectPrefs.enabled(app)) return
        // These accesses need BLUETOOTH_CONNECT; give up when not granted (cannot tell whether it is the target head unit)
        val address = runCatching { device.address }.getOrNull() ?: return
        val name = runCatching { device.name }.getOrNull()

        if (!isCarDevice(app, address, name)) {
            DebugFileLog.append(app, "AUTO: ignoring non-car Bluetooth ${name ?: "?"} ($address)")
            return
        }

        if (action == BluetoothDevice.ACTION_ACL_CONNECTED) {
            DebugFileLog.append(app, "AUTO: car Bluetooth connected ${name ?: "?"} ($address), auto-starting session")
            runCatching {
                context.startForegroundService(
                    Intent(context, CarPlayService::class.java)
                        .setAction(CarPlayService.ACTION_AUTO_START_SESSION),
                )
            }.onFailure { error ->
                DebugFileLog.append(
                    app,
                    "AUTO: auto-start blocked by the system (${error.javaClass.simpleName}: ${error.message})" +
                        "; grant \"Display over other apps\" and retry, or open the app and connect once by hand",
                )
            }
        } else {
            DebugFileLog.append(app, "AUTO: car Bluetooth disconnected ${name ?: "?"} ($address), ending session")
            // While a session runs the service is the foreground process so startService is allowed; if not running there was no
            // session anyway
            runCatching {
                context.startService(
                    Intent(context, CarPlayService::class.java)
                        .setAction(CarPlayService.ACTION_AUTO_STOP_SESSION),
                )
            }
        }
    }

    private fun deviceOf(intent: Intent): BluetoothDevice? =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE, BluetoothDevice::class.java)
        } else {
            @Suppress("DEPRECATION")
            intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE)
        }

    private fun isCarDevice(
        context: Context,
        address: String,
        name: String?,
    ): Boolean {
        val target = TargetDevicePrefs.get(context)
        if (target != null) return TargetDevicePrefs.matches(target, address)
        val label = name ?: return false
        return CAR_KEYWORDS.any { label.contains(it, ignoreCase = true) }
    }

    private companion object {
        /** Fallback when no target head unit is selected: common head unit Bluetooth name keywords (real cars + test bench). */
        val CAR_KEYWORDS = listOf("BMW", "MINI", "AUDI", "PORSCHE", "MERCEDES", "BENZ", "VOLVO", "CARPLAY")
    }
}
