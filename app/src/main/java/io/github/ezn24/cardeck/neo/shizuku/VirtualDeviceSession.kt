package io.github.ezn24.cardeck.neo.shizuku

import android.content.Context
import android.os.IBinder
import android.view.Surface

/**
 * Lock-screen streaming through VirtualDeviceManager (ported from upstream shaonialife/cardeck d760a40).
 *
 * The legacy display flags (TRUSTED / ALWAYS_UNLOCKED / OWN_DISPLAY_GROUP) only cover part of the keyguard and
 * display-off tokens; pressing the power button still strips window surfaces. A virtual device with
 * LOCK_STATE_UNLOCKED is the platform's own answer: displays created on it ignore the phone's lock and sleep.
 *
 * Steps (all as shell, uid 2000, no dialog): a COMPANION_DEVICE_APP_STREAMING association between the shell package
 * and the car's Bluetooth address (`cmd companiondevice associate`), the matching role for the shell package, then
 * `VirtualDeviceManager.createVirtualDevice(associationId, params)`. Any failure throws and the caller creates the
 * display the legacy way.
 *
 * Lifetime: one CarPlay session, but a display parked on disconnect (the navigating map) keeps the device open until
 * it is released ([closeWhenEmpty]). An association this session created is removed on close; one that already
 * existed is reused and left alone.
 */
internal class VirtualDeviceSession(
    private val shellContext: Context,
    val mac: String,
    private val log: (String) -> Unit,
) {
    private var associationId = -1
    private var associationCreatedByUs = false
    private var device: Any? = null
    private val displays = LinkedHashMap<Int, android.hardware.display.VirtualDisplay>()
    private val userId: Int = android.os.Process.myUid() / 100000

    /** Close as soon as the last display goes (set when the session ends with a parked display left). */
    @Volatile
    var closeWhenEmpty = false

    private val cdm: Any? by lazy {
        runCatching {
            val binder = serviceBinder("companiondevice")
            Class
                .forName("android.companion.ICompanionDeviceManager\$Stub")
                .getMethod("asInterface", IBinder::class.java)
                .invoke(null, binder)
        }.onFailure { log("VDM: ICompanionDeviceManager unavailable: ${it.message}") }.getOrNull()
    }

    fun describe(): String =
        "mac=$mac association=$associationId createdByUs=$associationCreatedByUs device=${device != null} displays=${displays.size}"

    @Synchronized
    fun displayCount(): Int = displays.size

    /** Association (reused or created) -> role -> virtual device. Throws on failure. */
    @Synchronized
    fun ensure() {
        if (device != null) return
        janitor()
        val existing = findAssociation()
        if (existing >= 0) {
            associationId = existing
            log("VDM: reusing association id=$existing")
        } else {
            // Positional form (upstream: the flag form on Honor reported "not a valid MAC address" and still created
            // a junk association with the phone's own address, so it is only the fallback and the janitor cleans up)
            runCatching { runCmd("companiondevice", "associate", userId.toString(), SHELL_PACKAGE, mac, ROLE_APP_STREAMING, "false") }
                .onFailure { log("VDM: associate (positional) failed: ${it.message}") }
            if (findAssociation() < 0) {
                runCatching {
                    runCmd(
                        "companiondevice",
                        "associate",
                        userId.toString(),
                        SHELL_PACKAGE,
                        "--mac-address",
                        mac,
                        "--profile",
                        ROLE_APP_STREAMING,
                    )
                }.onFailure { log("VDM: associate (flags) failed: ${it.message}") }
            }
            associationCreatedByUs = true
            // The command returning does not mean the association is visible yet
            for (attempt in 0 until 20) {
                val id = findAssociation()
                if (id >= 0) {
                    associationId = id
                    break
                }
                Thread.sleep(100)
            }
            if (associationId < 0) throw java.io.IOException("association not visible 2 s after associate")
            log("VDM: association created id=$associationId")
            janitor()
        }
        ensureRoleHolder()
        createDevice()
    }

    /**
     * Associations of the shell package with the app-streaming profile but another address can only be ours (a
     * previous car, or the junk the flag form leaves), so they are removed. Other profiles are never touched.
     */
    private fun janitor() {
        for (assoc in associations()) {
            val id = idOf(assoc) ?: continue
            if (profileOf(assoc) != ROLE_APP_STREAMING) continue
            val assocMac = macOf(assoc) ?: continue
            if (assocMac.equals(mac, ignoreCase = true)) continue
            if (disassociate(id, assocMac)) log("VDM: removed stale association id=$id")
        }
    }

    private fun associations(): List<Any> {
        val manager = cdm ?: return emptyList()
        return runCatching {
            (
                Class
                    .forName("android.companion.ICompanionDeviceManager")
                    .getMethod("getAssociations", String::class.java, Int::class.javaPrimitiveType)
                    .invoke(manager, SHELL_PACKAGE, userId) as List<*>
            ).filterNotNull()
        }.getOrDefault(emptyList())
    }

    private fun idOf(assoc: Any): Int? = runCatching { assoc.javaClass.getMethod("getId").invoke(assoc) as Int }.getOrNull()

    private fun macOf(assoc: Any): String? =
        runCatching {
            assoc.javaClass
                .getMethod("getDeviceMacAddress")
                .invoke(assoc)
                ?.toString()
        }.getOrNull()

    private fun profileOf(assoc: Any): String? =
        runCatching { assoc.javaClass.getMethod("getDeviceProfile").invoke(assoc) as? String }.getOrNull()

    /** Association id for [mac] with the app-streaming profile, -1 = none. Another profile for it is never reused. */
    private fun findAssociation(): Int {
        if (cdm == null) throw java.io.IOException("CompanionDeviceManager unavailable")
        for (assoc in associations()) {
            if (macOf(assoc)?.equals(mac, ignoreCase = true) != true) continue
            val profile = profileOf(assoc)
            if (profile == ROLE_APP_STREAMING) return idOf(assoc) ?: -1
            throw java.io.IOException("an association for this address already exists with profile=$profile")
        }
        return -1
    }

    /** By id (newer platforms) or by (package, address) (older ones); the shell command form is broken on some ROMs. */
    private fun disassociate(
        id: Int,
        assocMac: String?,
    ): Boolean {
        val manager = cdm ?: return false
        val cls = Class.forName("android.companion.ICompanionDeviceManager")
        return runCatching {
            cls.getMethod("disassociate", Int::class.javaPrimitiveType).invoke(manager, id)
            true
        }.getOrElse {
            runCatching {
                if (assocMac == null) return false
                cls.getMethod("disassociate", String::class.java, String::class.java).invoke(manager, SHELL_PACKAGE, assocMac)
                true
            }.getOrDefault(false)
        }
    }

    private fun ensureRoleHolder() {
        runCatching {
            val holders = runCmd("role", "get-role-holders", "--user", userId.toString(), ROLE_APP_STREAMING)
            if (!VirtualDeviceRoute.roleHoldersContain(holders, SHELL_PACKAGE)) {
                runCmd("role", "add-role-holder", "--user", userId.toString(), ROLE_APP_STREAMING, SHELL_PACKAGE)
                log("VDM: granted role $ROLE_APP_STREAMING to the shell package")
            }
        }.onFailure { log("VDM: role check failed (continuing): ${it.message}") }
    }

    private fun createDevice() {
        val paramsClass = Class.forName("android.companion.virtual.VirtualDeviceParams")
        val builderClass = Class.forName("android.companion.virtual.VirtualDeviceParams\$Builder")
        val builder = builderClass.getConstructor().newInstance()
        val lockState = lockStateUnlocked(paramsClass)
        builderClass.getMethod("setLockState", Int::class.javaPrimitiveType).invoke(builder, lockState)
        builderClass.getMethod("setName", String::class.java).invoke(builder, "CarDeck")
        val params = builderClass.getMethod("build").invoke(builder)
        val vdmClass = Class.forName("android.companion.virtual.VirtualDeviceManager")
        val ivdmClass = Class.forName("android.companion.virtual.IVirtualDeviceManager")
        val proxy =
            Class
                .forName("android.companion.virtual.IVirtualDeviceManager\$Stub")
                .getMethod("asInterface", IBinder::class.java)
                .invoke(null, serviceBinder("virtualdevice"))
        // The shell package context has no AttributionSource on some ROMs (NPE in the constructor, upstream finding)
        val manager = vdmClass.getConstructor(ivdmClass, Context::class.java).newInstance(proxy, AttributionContext(shellContext))
        device =
            vdmClass
                .getMethod("createVirtualDevice", Int::class.javaPrimitiveType, paramsClass)
                .invoke(manager, associationId, params)
        log("VDM: virtual device created lockState=$lockState")
    }

    /** LOCK_STATE_UNLOCKED is 1 in AOSP; some ROMs name the constant differently, so look it up and log the candidates. */
    private fun lockStateUnlocked(paramsClass: Class<*>): Int {
        val fields =
            paramsClass.fields
                .filter { it.type == Int::class.javaPrimitiveType && it.name.startsWith("LOCK_STATE") }
                .mapNotNull { field -> runCatching { field.name to field.getInt(null) }.getOrNull() }
        log("VDM: lock states ${fields.joinToString { "${it.first}=${it.second}" }}")
        for (name in listOf("LOCK_STATE_ALWAYS_UNLOCKED", "LOCK_STATE_UNLOCKED")) {
            fields.firstOrNull { it.first == name }?.let { return it.second }
        }
        return 1
    }

    /** A display on the virtual device. Throws so the caller can try the next flags / the legacy path. */
    @Synchronized
    fun createDisplay(
        name: String,
        width: Int,
        height: Int,
        dpi: Int,
        surface: Surface,
        flags: Int,
    ): android.hardware.display.VirtualDisplay {
        val dev = device ?: throw java.io.IOException("virtual device not ready")
        val configClass = Class.forName("android.hardware.display.VirtualDisplayConfig")
        val builderClass = Class.forName("android.hardware.display.VirtualDisplayConfig\$Builder")
        val builder =
            builderClass
                .getConstructor(
                    String::class.java,
                    Int::class.javaPrimitiveType,
                    Int::class.javaPrimitiveType,
                    Int::class.javaPrimitiveType,
                ).newInstance(name, width, height, dpi)
        builderClass.getMethod("setSurface", Surface::class.java).invoke(builder, surface)
        builderClass.getMethod("setFlags", Int::class.javaPrimitiveType).invoke(builder, flags)
        // Own refresh rate: without it a virtual display follows the phone panel's vsync, which drops when the panel
        // goes to AOD / off, and apps on the car displays render slower while the phone is locked
        runCatching {
            builderClass.getMethod("setRequestedRefreshRate", Float::class.javaPrimitiveType).invoke(builder, REFRESH_RATE)
        }.onFailure { log("VDM: setRequestedRefreshRate unavailable: ${it.message}") }
        val config = builderClass.getMethod("build").invoke(builder)
        val display =
            dev.javaClass
                .getMethod(
                    "createVirtualDisplay",
                    configClass,
                    java.util.concurrent.Executor::class.java,
                    android.hardware.display.VirtualDisplay.Callback::class.java,
                ).invoke(dev, config, null, null) as? android.hardware.display.VirtualDisplay
                ?: throw java.io.IOException("createVirtualDisplay returned null")
        val id = display.display.displayId
        if (id <= 0) {
            runCatching { display.release() }
            throw java.io.IOException("invalid displayId $id")
        }
        displays[id] = display
        return display
    }

    /** The caller released [displayId] itself; returns whether the session should now close. */
    @Synchronized
    fun discard(displayId: Int): Boolean {
        displays.remove(displayId)
        return closeWhenEmpty && displays.isEmpty()
    }

    /** Releases remaining displays, closes the device and removes an association this session created. */
    @Synchronized
    fun close() {
        for ((id, display) in displays) {
            runCatching { display.release() }
            log("VDM: released display #$id with the session")
        }
        displays.clear()
        device?.let { dev -> runCatching { dev.javaClass.getMethod("close").invoke(dev) } }
        device = null
        if (associationCreatedByUs && associationId >= 0) {
            val removed = runCatching { disassociate(associationId, mac) }.getOrDefault(false)
            log("VDM: association id=$associationId removed=$removed")
            associationCreatedByUs = false
            associationId = -1
            ensureRoleHolder()
        }
        log("VDM: session closed")
    }

    private fun serviceBinder(name: String): IBinder =
        Class
            .forName("android.os.ServiceManager")
            .getMethod("getService", String::class.java)
            .invoke(null, name) as IBinder

    private fun runCmd(vararg args: String): String {
        val process = ProcessBuilder("/system/bin/cmd", *args).redirectErrorStream(true).start()
        val output = process.inputStream.use { it.readBytes().decodeToString().trim() }
        if (!process.waitFor(5, java.util.concurrent.TimeUnit.SECONDS)) {
            process.destroyForcibly()
            throw java.io.IOException("cmd timed out: ${args.joinToString(" ")}")
        }
        if (process.exitValue() != 0) {
            throw java.io.IOException("cmd exit=${process.exitValue()}: ${args.joinToString(" ")} -> ${output.take(200)}")
        }
        return output
    }

    /** Shell context with an explicit AttributionSource (shell uid + package). */
    private class AttributionContext(
        base: Context,
    ) : android.content.ContextWrapper(base) {
        private val source =
            android.content.AttributionSource
                .Builder(android.os.Process.myUid())
                .setPackageName(SHELL_PACKAGE)
                .build()

        override fun getAttributionSource(): android.content.AttributionSource = source

        override fun getOpPackageName(): String = SHELL_PACKAGE

        override fun getPackageName(): String = SHELL_PACKAGE

        override fun getApplicationContext(): Context = this
    }

    companion object {
        const val SHELL_PACKAGE = "com.android.shell"
        const val REFRESH_RATE = 60f
        const val ROLE_APP_STREAMING = "android.app.role.COMPANION_DEVICE_APP_STREAMING"
    }
}
