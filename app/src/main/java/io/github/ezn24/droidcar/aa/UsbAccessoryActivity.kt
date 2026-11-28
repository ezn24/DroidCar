package io.github.ezn24.droidcar.aa

import android.app.Activity
import android.hardware.usb.UsbAccessory
import android.hardware.usb.UsbManager
import android.os.Build
import android.os.Bundle

/**
 * Started by Android when a head unit switches the phone into USB accessory mode as an Android Auto car
 * (res/xml/aa_accessory_filter.xml). Hands the accessory to [AndroidAutoController] and closes at once.
 */
class UsbAccessoryActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val accessory =
            if (Build.VERSION.SDK_INT >= 33) {
                intent?.getParcelableExtra(UsbManager.EXTRA_ACCESSORY, UsbAccessory::class.java)
            } else {
                @Suppress("DEPRECATION")
                intent?.getParcelableExtra(UsbManager.EXTRA_ACCESSORY)
            }
        if (accessory != null) AndroidAutoController.onUsbAccessory(this, accessory)
        finish()
    }
}
