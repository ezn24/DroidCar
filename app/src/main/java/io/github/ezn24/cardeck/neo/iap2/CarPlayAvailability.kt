package io.github.ezn24.cardeck.neo.iap2

internal object CarPlayAvailability {
    /**
     * 0x4300 CarPlayAvailability 体。默认单群组（群组 1：可用 + 蓝牙 MAC，奥迪/BMW/测试台实测）。
     * [dualGroup]：再加群组 0（可用 + 24 位大写十六进制 CarPlay 设备标识）——真 iPhone 对通用车机
     * 发的是这种双群组；fork chengshl/cardeck（MIT）的 Buick E5（B233）改成它后车机才继续推进，
     * 上游别克/凯迪拉克多份日志卡在 0x4301 之前即此阶段。只对通用车机启用，其它车型不受影响。
     */
    fun body(
        bluetoothMac: String,
        dualGroup: Boolean,
    ): ByteArray {
        val bluetoothGroup = Iap2Csm.group(1, Iap2Csm.u8(0, 1), Iap2Csm.string(1, bluetoothMac))
        if (!dualGroup) return bluetoothGroup
        return Iap2Csm.group(0, Iap2Csm.u8(0, 1), Iap2Csm.string(1, deviceIdentifier(bluetoothMac))) + bluetoothGroup
    }

    /** 由蓝牙 MAC 确定性派生的 24 位大写十六进制标识（SHA-256 前 12 字节），跨会话稳定。 */
    fun deviceIdentifier(bluetoothMac: String): String =
        java.security.MessageDigest
            .getInstance("SHA-256")
            .digest(("CarDeck-CarPlay:" + bluetoothMac.uppercase()).toByteArray())
            .take(12)
            .joinToString("") { "%02X".format(it.toInt() and 0xFF) }
}
