package io.github.ezn24.droidcar.iccoa

import android.net.Network
import java.net.InetSocketAddress
import java.net.Socket

/**
 * The Wi-Fi path an ICCOA session runs on. The car's server info names its network type
 * (OpenCarLink notes: 1001 = Wi-Fi Direct with the car as group owner, 1002 = SoftAP, 1003 = both):
 * SoftAP is an app-scoped [Network] from a specifier, Wi-Fi Direct has no Network object, so sockets are
 * bound to the phone's address on the p2p interface instead.
 */
sealed class IccoaLink {
    abstract fun bind(socket: Socket)

    data class Wifi(
        val network: Network,
    ) : IccoaLink() {
        override fun bind(socket: Socket) = network.bindSocket(socket)
    }

    data class Direct(
        val localAddress: String,
    ) : IccoaLink() {
        override fun bind(socket: Socket) = socket.bind(InetSocketAddress(localAddress, 0))
    }

    companion object {
        const val TYPE_WIFI_DIRECT = 1001
        const val TYPE_SOFT_AP = 1002
        const val TYPE_BOTH = 1003
    }
}
