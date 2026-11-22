package io.github.ezn24.droidcar.shizuku

import android.annotation.SuppressLint
import android.content.Context
import android.hardware.input.InputManager
import android.os.Build
import android.os.IBinder
import android.os.SystemClock
import android.util.Log
import android.view.InputDevice
import android.view.InputEvent
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.Surface
import android.view.SurfaceControl
import java.lang.reflect.Method

/**
 * Shizuku UserService implementation: runs in the resident shell process (uid 2000) and
 * injects touch events straight into a given display by reflecting InputManager.injectInputEvent.
 *
 * The location of injectInputEvent varies by version:
 * - Android 13 and below: InputManager instance method;
 * - Android 14+: moved to the InputManagerGlobal.getInstance() singleton.
 * Some versions take a third targetDisplayId parameter (adapted by parameter type);
 * the old two-parameter signature relies on InputEvent.setDisplayId to pick the target display.
 *
 * Reflection failures (ROM hidden-API differences) are exposed through ping()/diag(); callers fall back to `input -d`.
 * (Ported from the reference project android-auto's CarInputServiceImpl)
 */
class CarInputServiceImpl(
    private val context: Context,
) : ICarInput.Stub() {
    /**
     * Whether we turned the physical panel OFF. Must be restored before the service exits (stale self-reap after an APK upgrade),
     * otherwise the phone is left on a screen that is "logically awake, physically dark" (recoverable by pressing the power key twice).
     */
    @Volatile
    private var physicalScreenOff = false

    @Volatile
    private var outputRecorder: android.media.AudioRecord? = null
    private var outputPipe: android.os.ParcelFileDescriptor? = null

    /** Shell output capture bypasses the app opt-out of normal playback capture and does not use the microphone source. */
    @SuppressLint("MissingPermission", "WrongConstant", "SoonBlockedPrivateApi", "PrivateApi")
    @Synchronized
    override fun startAudioCapture(
        sampleRate: Int,
        channels: Int,
    ): android.os.ParcelFileDescriptor? {
        stopAudioCapture()
        if (sampleRate !in listOf(44100, 48000) || channels != 2) return null
        val identity = android.os.Binder.clearCallingIdentity()
        var reader: android.os.ParcelFileDescriptor? = null
        var writer: android.os.ParcelFileDescriptor? = null
        var recorder: android.media.AudioRecord? = null
        return try {
            // AudioRecord needs the actual shell UID attribution; the main app's package name cannot be reused.
            val shellContext =
                object : android.content.ContextWrapper(context) {
                    override fun getPackageName(): String = "com.android.shell"

                    override fun getOpPackageName(): String = "com.android.shell"

                    override fun getAttributionSource(): android.content.AttributionSource =
                        android.content.AttributionSource
                            .Builder(android.os.Process.myUid())
                            .setPackageName("com.android.shell")
                            .build()
                }
            val format =
                android.media.AudioFormat
                    .Builder()
                    .setSampleRate(sampleRate)
                    .setEncoding(android.media.AudioFormat.ENCODING_PCM_16BIT)
                    .setChannelMask(android.media.AudioFormat.CHANNEL_IN_STEREO)
                    .build()
            val builder =
                android.media.AudioRecord
                    .Builder()
                    .setAudioSource(8)
                    .setAudioFormat(format)
            android.media.AudioRecord.Builder::class.java
                .getMethod("setContext", Context::class.java)
                .invoke(builder, shellContext)
            val minimum =
                android.media.AudioRecord.getMinBufferSize(
                    sampleRate,
                    android.media.AudioFormat.CHANNEL_IN_STEREO,
                    android.media.AudioFormat.ENCODING_PCM_16BIT,
                )
            recorder = builder.setBufferSizeInBytes(maxOf(minimum, 4096) * 4).build()
            check(recorder.state == android.media.AudioRecord.STATE_INITIALIZED)
            recorder.startRecording()
            check(recorder.recordingState == android.media.AudioRecord.RECORDSTATE_RECORDING)
            val pipe = android.os.ParcelFileDescriptor.createPipe()
            reader = pipe[0]
            writer = pipe[1]
            outputRecorder = recorder
            outputPipe = writer
            val activeRecorder = requireNotNull(recorder)
            val activeWriter = requireNotNull(writer)
            Thread({
                try {
                    android.os.ParcelFileDescriptor.AutoCloseOutputStream(activeWriter).use { stream ->
                        val pcm = ByteArray(4096)
                        while (outputRecorder === activeRecorder) {
                            val read = activeRecorder.read(pcm, 0, pcm.size, android.media.AudioRecord.READ_BLOCKING)
                            if (read <= 0) break
                            stream.write(pcm, 0, read)
                        }
                    }
                } catch (error: Exception) {
                    fileLog("system audio pipe ended: ${error.message}")
                } finally {
                    synchronized(this) {
                        if (outputRecorder === activeRecorder) stopAudioCapture()
                    }
                }
            }, "shell-audio-output").apply {
                isDaemon = true
                start()
            }
            fileLog("system audio started: REMOTE_SUBMIX ${sampleRate}Hz stereo")
            reader
        } catch (error: Throwable) {
            runCatching { recorder?.release() }
            runCatching { reader?.close() }
            runCatching { writer?.close() }
            outputRecorder = null
            outputPipe = null
            fileLog("system audio unavailable: $error")
            null
        } finally {
            android.os.Binder.restoreCallingIdentity(identity)
        }
    }

    // ---- Car microphone injection (AudioPolicy recording injection, verified by experiments 1/2 in docs/MIC-RESEARCH.md) ----

    @Volatile
    private var micPolicy: Any? = null

    @Volatile
    private var micTrack: android.media.AudioTrack? = null
    private var micPipe: android.os.ParcelFileDescriptor? = null

    /**
     * Playback track used for injection. Built by hand with the attributes of AOSP
     * `AudioPolicy.createAudioTrackSource`, with one extra step,
     * `setContext(shell)`: when the system method builds the track its attribution uses the process opPackageName (= DroidCar's
     * package name) while the uid is shell 2000, so AudioFlinger sees a mismatched identity and refuses; observed as
     * "UnsupportedOperationException: Cannot create AudioTrack". Same pitfall as the AudioRecord in startAudioCapture,
     * which also needs setContext.
     */
    @androidx.annotation.RequiresApi(34)
    private fun injectionTrack(
        mix: Any,
        format: android.media.AudioFormat,
    ): android.media.AudioTrack {
        val registration = mix.javaClass.getMethod("getRegistration").invoke(mix) as String
        val attrs = android.media.AudioAttributes.Builder()
        // USAGE_VIRTUAL_SOURCE(15) is a @SystemApi constant: call by reflection (AOSP builds its own injection track with this usage)
        android.media.AudioAttributes.Builder::class.java
            .getMethod("setUsage", Int::class.java)
            .invoke(attrs, 15)
        val addTag = android.media.AudioAttributes.Builder::class.java.getMethod("addTag", String::class.java)
        // There may be only the addr= tag: APM routes the track into the mix's submix depending on whether tags **starts with** "addr=".
        // An extra fixedVolume (used by createAudioRecordSink) once made tags "fixedVolume;addr=...",
        // the track never reached the injection device and the recording came back empty (pinned down 2026-11-17 by a
        // dumpsys audio A/B experiment).
        addTag.invoke(attrs, "addr=$registration")
        val minimum =
            android.media.AudioTrack.getMinBufferSize(
                format.sampleRate,
                format.channelMask,
                format.encoding,
            )
        return android.media.AudioTrack
            .Builder()
            .setAudioAttributes(attrs.build())
            .setAudioFormat(format)
            .setBufferSizeInBytes(maxOf(minimum, format.sampleRate / 5 * 2))
            .setTransferMode(android.media.AudioTrack.MODE_STREAM)
            .setContext(shellContext())
            .build()
            .also { check(it.state == android.media.AudioTrack.STATE_INITIALIZED) { "injection track state=${it.state}" } }
    }

    private var micLooperThread: android.os.HandlerThread? = null

    private fun micLooper(): android.os.Looper {
        val existing = micLooperThread
        if (existing != null && existing.isAlive) return existing.looper
        return android.os
            .HandlerThread("shell-mic-policy")
            .also {
                it.start()
                micLooperThread = it
            }.looper
    }

    /** AudioPolicy needs the shell UID attribution (the main app's package name cannot be reused). */
    private fun shellContext(): Context =
        object : android.content.ContextWrapper(context) {
            override fun getPackageName(): String = "com.android.shell"

            override fun getOpPackageName(): String = "com.android.shell"

            override fun getAttributionSource(): android.content.AttributionSource =
                android.content.AttributionSource
                    .Builder(android.os.Process.myUid())
                    .setPackageName("com.android.shell")
                    .build()
        }

    @Synchronized
    override fun startMicInjection(sampleRate: Int): android.os.ParcelFileDescriptor? {
        stopMicInjection()
        if (sampleRate !in 8_000..48_000) return null
        val identity = android.os.Binder.clearCallingIdentity()
        var policy: Any? = null
        var track: android.media.AudioTrack? = null
        val audioManager = context.getSystemService(android.media.AudioManager::class.java)
        val policyClass = Class.forName("android.media.audiopolicy.AudioPolicy")
        val mixClass = Class.forName("android.media.audiopolicy.AudioMix")
        return try {
            val ruleBuilderClass = Class.forName("android.media.audiopolicy.AudioMixingRule\$Builder")
            val ruleBuilder = ruleBuilderClass.getConstructor().newInstance()
            // MIX_ROLE_INJECTOR=1: this mix "injects into recordings" rather than "captures playback"
            runCatching { ruleBuilderClass.getMethod("setTargetMixRole", Int::class.java).invoke(ruleBuilder, 1) }
            val setPreset =
                android.media.AudioAttributes.Builder::class.java.getMethod("setCapturePreset", Int::class.java)
            val addRule =
                ruleBuilderClass.getMethod("addRule", android.media.AudioAttributes::class.java, Int::class.java)
            // HOTWORD is excluded: the hotword goes through SoundTrigger/DSP and cannot be injected into (experiment 2)
            for (preset in MIC_INJECT_PRESETS) {
                val attrs = android.media.AudioAttributes.Builder()
                setPreset.invoke(attrs, preset)
                addRule.invoke(ruleBuilder, attrs.build(), 2) // RULE_MATCH_ATTRIBUTE_CAPTURE_PRESET
            }
            val rule = ruleBuilderClass.getMethod("build").invoke(ruleBuilder)
            val format =
                android.media.AudioFormat
                    .Builder()
                    .setEncoding(android.media.AudioFormat.ENCODING_PCM_16BIT)
                    .setSampleRate(sampleRate)
                    .setChannelMask(android.media.AudioFormat.CHANNEL_OUT_MONO)
                    .build()
            val mixBuilderClass = Class.forName("android.media.audiopolicy.AudioMix\$Builder")
            val mixBuilder =
                mixBuilderClass
                    .getConstructor(Class.forName("android.media.audiopolicy.AudioMixingRule"))
                    .newInstance(rule)
            mixBuilderClass.getMethod("setFormat", android.media.AudioFormat::class.java).invoke(mixBuilder, format)
            mixBuilderClass.getMethod("setRouteFlags", Int::class.java).invoke(mixBuilder, 2) // ROUTE_FLAG_LOOP_BACK
            val mix = mixBuilderClass.getMethod("build").invoke(mixBuilder)
            val policyBuilderClass = Class.forName("android.media.audiopolicy.AudioPolicy\$Builder")
            val policyBuilder = policyBuilderClass.getConstructor(Context::class.java).newInstance(shellContext())
            policyBuilderClass.getMethod("addMix", mixClass).invoke(policyBuilder, mix)
            // The UserService binder thread has no Looper (the standalone app_process experiment calls prepareMainLooper):
            // AudioPolicy status callbacks need a Looper, so give it a dedicated thread
            policyBuilderClass.getMethod("setLooper", android.os.Looper::class.java).invoke(policyBuilder, micLooper())
            policy = policyBuilderClass.getMethod("build").invoke(policyBuilder)
            val registered =
                android.media.AudioManager::class.java
                    .getMethod("registerAudioPolicy", policyClass)
                    .invoke(audioManager, policy) as Int
            check(registered == 0) { "registerAudioPolicy=$registered" }
            track =
                if (android.os.Build.VERSION.SDK_INT >= 34) {
                    injectionTrack(mix, format)
                } else {
                    // Android 12/13 has no AudioTrack.Builder.setContext: only the system method is usable (it may be
                    // refused over identity)
                    policyClass.getMethod("createAudioTrackSource", mixClass).invoke(policy, mix) as android.media.AudioTrack
                }
            track.play()
            val pipe = android.os.ParcelFileDescriptor.createPipe()
            micPolicy = policy
            micTrack = track
            micPipe = pipe[0]
            val activeTrack: android.media.AudioTrack = track
            val reader = pipe[0]
            Thread({
                try {
                    android.os.ParcelFileDescriptor.AutoCloseInputStream(reader).use { input ->
                        val pcm = ByteArray(4096)
                        while (micTrack === activeTrack) {
                            val read = input.read(pcm)
                            if (read <= 0) break
                            activeTrack.write(pcm, 0, read and 1.inv(), android.media.AudioTrack.WRITE_BLOCKING)
                        }
                    }
                } catch (error: Exception) {
                    fileLog("mic injection pipe ended: ${error.message}")
                } finally {
                    // App closed the pipe / app process died: unregister at once and hand the real microphone back to other apps
                    synchronized(this) {
                        if (micTrack === activeTrack) stopMicInjection()
                    }
                }
            }, "shell-mic-inject").apply {
                isDaemon = true
                start()
            }
            fileLog("mic injection started: ${sampleRate}Hz mono")
            pipe[1]
        } catch (error: Throwable) {
            var root: Throwable = error
            while (root.cause != null && root.cause !== root) root = root.cause!!
            fileLog("mic injection failed: ${root.javaClass.name}: ${root.message}")
            runCatching { track?.release() }
            policy?.let { p ->
                runCatching {
                    android.media.AudioManager::class.java
                        .getMethod("unregisterAudioPolicy", policyClass)
                        .invoke(audioManager, p)
                }
            }
            null
        } finally {
            android.os.Binder.restoreCallingIdentity(identity)
        }
    }

    @Synchronized
    override fun stopMicInjection() {
        val track = micTrack
        val policy = micPolicy
        micTrack = null
        micPolicy = null
        runCatching { track?.stop() }
        runCatching { track?.release() }
        runCatching { micPipe?.close() }
        micPipe = null
        if (policy != null) {
            runCatching {
                val audioManager = context.getSystemService(android.media.AudioManager::class.java)
                android.media.AudioManager::class.java
                    .getMethod("unregisterAudioPolicy", Class.forName("android.media.audiopolicy.AudioPolicy"))
                    .invoke(audioManager, policy)
            }
            fileLog("mic injection stopped")
        }
    }

    // ---- Call audio (VoIP far-end voice) to the car ----

    @Volatile
    private var callPolicy: Any? = null

    @Volatile
    private var callRecord: android.media.AudioRecord? = null

    /**
     * Loopback capture of `USAGE_VOICE_COMMUNICATION` playback (WhatsApp's far-end voice, measured 2026-11-21): the mix takes
     * the voice off the phone speaker and hands it to us as PCM through the returned pipe. Needs
     * CAPTURE_VOICE_COMMUNICATION_OUTPUT + MODIFY_AUDIO_ROUTING (shell holds both). The record sink is built by hand like
     * [injectionTrack] (setContext(shell)), with attributes from AOSP `AudioPolicy.createAudioRecordSink`.
     */
    @SuppressLint("MissingPermission", "WrongConstant")
    @Synchronized
    override fun startCallCapture(sampleRate: Int): android.os.ParcelFileDescriptor? {
        stopCallCapture()
        if (sampleRate !in 8_000..48_000 || android.os.Build.VERSION.SDK_INT < 34) return null
        val identity = android.os.Binder.clearCallingIdentity()
        var policy: Any? = null
        var record: android.media.AudioRecord? = null
        val audioManager = context.getSystemService(android.media.AudioManager::class.java)
        val policyClass = Class.forName("android.media.audiopolicy.AudioPolicy")
        val mixClass = Class.forName("android.media.audiopolicy.AudioMix")
        return try {
            val ruleBuilderClass = Class.forName("android.media.audiopolicy.AudioMixingRule\$Builder")
            val ruleBuilder = ruleBuilderClass.getConstructor().newInstance()
            val attrs =
                android.media.AudioAttributes
                    .Builder()
                    .setUsage(android.media.AudioAttributes.USAGE_VOICE_COMMUNICATION)
                    .build()
            ruleBuilderClass
                .getMethod("addRule", android.media.AudioAttributes::class.java, Int::class.java)
                .invoke(ruleBuilder, attrs, 1) // RULE_MATCH_ATTRIBUTE_USAGE
            // Without it APM refuses to route VOICE_COMMUNICATION into a mix
            ruleBuilderClass.getMethod("voiceCommunicationCaptureAllowed", Boolean::class.java).invoke(ruleBuilder, true)
            val rule = ruleBuilderClass.getMethod("build").invoke(ruleBuilder)
            val mixFormat =
                android.media.AudioFormat
                    .Builder()
                    .setEncoding(android.media.AudioFormat.ENCODING_PCM_16BIT)
                    .setSampleRate(sampleRate)
                    .setChannelMask(android.media.AudioFormat.CHANNEL_OUT_MONO)
                    .build()
            val mixBuilderClass = Class.forName("android.media.audiopolicy.AudioMix\$Builder")
            val mixBuilder =
                mixBuilderClass
                    .getConstructor(Class.forName("android.media.audiopolicy.AudioMixingRule"))
                    .newInstance(rule)
            mixBuilderClass.getMethod("setFormat", android.media.AudioFormat::class.java).invoke(mixBuilder, mixFormat)
            mixBuilderClass.getMethod("setRouteFlags", Int::class.java).invoke(mixBuilder, 2) // ROUTE_FLAG_LOOP_BACK
            val mix = mixBuilderClass.getMethod("build").invoke(mixBuilder)
            val policyBuilderClass = Class.forName("android.media.audiopolicy.AudioPolicy\$Builder")
            val policyBuilder = policyBuilderClass.getConstructor(Context::class.java).newInstance(shellContext())
            policyBuilderClass.getMethod("addMix", mixClass).invoke(policyBuilder, mix)
            policyBuilderClass.getMethod("setLooper", android.os.Looper::class.java).invoke(policyBuilder, micLooper())
            policy = policyBuilderClass.getMethod("build").invoke(policyBuilder)
            val registered =
                android.media.AudioManager::class.java
                    .getMethod("registerAudioPolicy", policyClass)
                    .invoke(audioManager, policy) as Int
            check(registered == 0) { "registerAudioPolicy=$registered" }
            val registration = mix.javaClass.getMethod("getRegistration").invoke(mix) as String
            val recordAttrs = android.media.AudioAttributes.Builder()
            android.media.AudioAttributes.Builder::class.java
                .getMethod("setInternalCapturePreset", Int::class.java)
                .invoke(recordAttrs, android.media.MediaRecorder.AudioSource.REMOTE_SUBMIX)
            android.media.AudioAttributes.Builder::class.java
                .getMethod("addTag", String::class.java)
                .invoke(recordAttrs, "addr=$registration")
            val recordFormat =
                android.media.AudioFormat
                    .Builder()
                    .setEncoding(android.media.AudioFormat.ENCODING_PCM_16BIT)
                    .setSampleRate(sampleRate)
                    .setChannelMask(android.media.AudioFormat.CHANNEL_IN_MONO)
                    .build()
            val builder = android.media.AudioRecord.Builder()
            android.media.AudioRecord.Builder::class.java
                .getMethod("setAudioAttributes", android.media.AudioAttributes::class.java)
                .invoke(builder, recordAttrs.build())
            builder.setAudioFormat(recordFormat)
            android.media.AudioRecord.Builder::class.java
                .getMethod("setContext", Context::class.java)
                .invoke(builder, shellContext())
            val minimum =
                android.media.AudioRecord.getMinBufferSize(
                    sampleRate,
                    android.media.AudioFormat.CHANNEL_IN_MONO,
                    android.media.AudioFormat.ENCODING_PCM_16BIT,
                )
            record = builder.setBufferSizeInBytes(maxOf(minimum, 2048) * 2).build()
            check(record.state == android.media.AudioRecord.STATE_INITIALIZED) { "record sink not initialized" }
            record.startRecording()
            val pipe = android.os.ParcelFileDescriptor.createPipe()
            callPolicy = policy
            callRecord = record
            val activeRecord: android.media.AudioRecord = record
            val writer = pipe[1]
            Thread({
                try {
                    android.os.ParcelFileDescriptor.AutoCloseOutputStream(writer).use { output ->
                        // 10ms chunks keep the call path short
                        val pcm = ByteArray(sampleRate / 100 * 2)
                        while (callRecord === activeRecord) {
                            val read = activeRecord.read(pcm, 0, pcm.size)
                            if (read < 0) break
                            if (read > 0) output.write(pcm, 0, read)
                        }
                    }
                } catch (error: Exception) {
                    fileLog("call capture pipe ended: ${error.message}")
                } finally {
                    // App closed the pipe / died: give the voice back to the phone speaker at once
                    synchronized(this) {
                        if (callRecord === activeRecord) stopCallCapture()
                    }
                }
            }, "shell-call-capture").apply {
                isDaemon = true
                start()
            }
            fileLog("call capture started: ${sampleRate}Hz mono")
            pipe[0]
        } catch (error: Throwable) {
            var root: Throwable = error
            while (root.cause != null && root.cause !== root) root = root.cause!!
            fileLog("call capture failed: ${root.javaClass.name}: ${root.message}")
            runCatching { record?.release() }
            policy?.let { p ->
                runCatching {
                    android.media.AudioManager::class.java
                        .getMethod("unregisterAudioPolicy", policyClass)
                        .invoke(audioManager, p)
                }
            }
            null
        } finally {
            android.os.Binder.restoreCallingIdentity(identity)
        }
    }

    @Synchronized
    override fun stopCallCapture() {
        val record = callRecord
        val policy = callPolicy
        callRecord = null
        callPolicy = null
        runCatching { record?.stop() }
        runCatching { record?.release() }
        if (policy != null) {
            runCatching {
                val audioManager = context.getSystemService(android.media.AudioManager::class.java)
                android.media.AudioManager::class.java
                    .getMethod("unregisterAudioPolicy", Class.forName("android.media.audiopolicy.AudioPolicy"))
                    .invoke(audioManager, policy)
            }
            fileLog("call capture stopped")
        }
    }

    // ---- Who is recording: trigger source for automatic car-microphone switching ----

    private val activeRecorders = java.util.concurrent.ConcurrentHashMap<String, Boolean>()

    @Volatile
    private var recorderWatchStarted = false

    /**
     * Answer / end a call through ITelecomService with the shell's identity (TelecomManager would attribute the call to
     * DroidCar's package name, which does not match uid 2000). Signatures differ between releases, so the methods are
     * looked up by name and called with the calling package as the first String argument.
     */
    override fun callControl(action: Int): Int {
        val name =
            when (action) {
                1 -> "acceptRingingCall"
                2 -> "endCall"
                else -> return 0
            }
        val identity = android.os.Binder.clearCallingIdentity()
        return try {
            val binder =
                Class
                    .forName("android.os.ServiceManager")
                    .getMethod("getService", String::class.java)
                    .invoke(null, "telecom") as android.os.IBinder
            val telecom =
                Class
                    .forName("com.android.internal.telecom.ITelecomService\$Stub")
                    .getMethod("asInterface", android.os.IBinder::class.java)
                    .invoke(null, binder)
            val method =
                telecom.javaClass.methods
                    .filter { it.name == name }
                    .minByOrNull { it.parameterTypes.size }
                    ?: return 0
            val args =
                method.parameterTypes
                    .map { type ->
                        when (type) {
                            String::class.java -> "com.android.shell"
                            Int::class.javaPrimitiveType -> 0
                            Boolean::class.javaPrimitiveType -> false
                            else -> null
                        }
                    }.toTypedArray()
            val result = method.invoke(telecom, *args)
            fileLog("callControl $name -> $result")
            if (result == false) 0 else 1
        } catch (t: Throwable) {
            fileLog("callControl $name failed: $t")
            0
        } finally {
            android.os.Binder.restoreCallingIdentity(identity)
        }
    }

    override fun removeTasks(taskIds: IntArray): Int {
        val atm =
            runCatching {
                Class.forName("android.app.ActivityTaskManager").getMethod("getService").invoke(null)
            }.getOrElse {
                fileLog("removeTasks: ActivityTaskManager lookup failed $it")
                return 0
            } ?: return 0
        val remove = runCatching { atm.javaClass.getMethod("removeTask", Int::class.javaPrimitiveType) }.getOrNull() ?: return 0
        return taskIds.count { id ->
            runCatching { remove.invoke(atm, id) as? Boolean == true }
                .onFailure { fileLog("removeTasks: #$id failed ${it.cause ?: it}") }
                .getOrDefault(false)
        }
    }

    override fun activeMicApps(): String {
        if (!recorderWatchStarted) startRecorderWatch()
        return activeRecorders.keys.joinToString(",")
    }

    override fun ringingApps(): String =
        try {
            val context = shellContext()
            val audio = context.getSystemService(android.media.AudioManager::class.java)
            val pm = context.packageManager
            val cls = android.media.AudioPlaybackConfiguration::class.java
            // getClientUid / getPlayerState are @SystemApi: reflection; PLAYER_STATE_STARTED = 2
            val uidOf = cls.getMethod("getClientUid")
            val stateOf = cls.getMethod("getPlayerState")
            audio.activePlaybackConfigurations
                .asSequence()
                .filter { it.audioAttributes.usage == android.media.AudioAttributes.USAGE_NOTIFICATION_RINGTONE }
                .filter { (stateOf.invoke(it) as Int) == 2 }
                .map { uidOf.invoke(it) as Int }
                .filter { it >= android.os.Process.FIRST_APPLICATION_UID }
                .flatMap { pm.getPackagesForUid(it).orEmpty().asSequence() }
                .distinct()
                .joinToString(",")
        } catch (t: Throwable) {
            fileLog("ringingApps failed: $t")
            ""
        }

    @Synchronized
    private fun startRecorderWatch() {
        if (recorderWatchStarted) return
        try {
            val appOps = shellContext().getSystemService(android.app.AppOpsManager::class.java)
            appOps.startWatchingActive(
                arrayOf(android.app.AppOpsManager.OPSTR_RECORD_AUDIO),
                java.util.concurrent.Executors
                    .newSingleThreadExecutor(),
            ) { _, uid, packageName, active ->
                val key = "$uid:$packageName"
                if (active) activeRecorders[key] = true else activeRecorders.remove(key)
            }
            recorderWatchStarted = true
            fileLog("recorder watch started")
        } catch (t: Throwable) {
            fileLog("recorder watch failed: $t")
        }
    }

    @Synchronized
    override fun stopAudioCapture() {
        val recorder = outputRecorder
        outputRecorder = null
        runCatching { recorder?.stop() }
        runCatching { recorder?.release() }
        runCatching { outputPipe?.close() }
        outputPipe = null
    }

    /**
     * Zombie self-reap (the real fix, 2026-10-16): Shizuku reuses the resident UserService process by
     * "component + process name + version (APK lastUpdateTime)". After an APK reinstall the old-version process is
     * unaffected by force-stop and Shizuku does not reap it, and the virtual displays it created **can only be released by itself**
     * (releaseDisplay checks only its own map), shown as "leftover displays detected after restart, cleanup releases 0/N".
     * This process periodically compares the "lastUpdateTime read at startup" with the currently installed value: a mismatch
     * means its own dex is stale, so it exits and lets the system reap the process, releasing every virtual display it holds.
     */
    private val startupLastUpdateTime: Long =
        runCatching {
            @Suppress("DEPRECATION")
            context.packageManager.getPackageInfo(context.packageName, 0).lastUpdateTime
        }.getOrDefault(-1L)

    init {
        fileLog("service start: pkg=${context.packageName} lastUpdate=$startupLastUpdateTime pid=${android.os.Process.myPid()}")
        Thread({
            while (true) {
                try {
                    Thread.sleep(STALE_CHECK_INTERVAL_MS)
                } catch (_: InterruptedException) {
                    return@Thread
                }
                val now =
                    runCatching {
                        @Suppress("DEPRECATION")
                        context.packageManager.getPackageInfo(context.packageName, 0).lastUpdateTime
                    }.getOrDefault(-1L)
                if (startupLastUpdateTime > 0 && now > 0 && now != startupLastUpdateTime) {
                    // If we turned the physical panel OFF it must be restored before exiting: otherwise the new-
                    // version process is not up yet
                    // and the phone sits on a "logically awake, physically dark" screen (recoverable only by pressing the power key twice).
                    if (physicalScreenOff) {
                        fileLog("stale self-reap: restoring physical panel power (avoid a black screen after the upgrade)")
                        runCatching { setPhysicalScreenPower(true) }
                    }
                    fileLog(
                        "stale self-reap: lastUpdateTime $startupLastUpdateTime -> $now, " +
                            "exiting pid=${android.os.Process.myPid()} (virtual displays it holds are released with the process)",
                    )
                    android.os.Process.killProcess(android.os.Process.myPid())
                    return@Thread
                }
            }
        }, "carinput-stale-check").apply {
            isDaemon = true
            start()
        }
    }

    private class Injector(
        val receiver: Any,
        val method: Method,
    )

    private val injector: Injector? by lazy {
        val im =
            try {
                context.getSystemService(Context.INPUT_SERVICE) as? InputManager
            } catch (t: Throwable) {
                Log.d(TAG, "probe getSystemService(INPUT_SERVICE) exception", t)
                null
            }
        if (im != null) {
            val m = findInjectMethod(im.javaClass.methods)
            if (m != null) return@lazy Injector(im, m)
        }
        try {
            val cls = Class.forName("android.hardware.input.InputManagerGlobal")
            val global = cls.getMethod("getInstance").invoke(null)
            val m = findInjectMethod(cls.methods)
            if (m != null) return@lazy Injector(global, m)
        } catch (t: Throwable) {
            Log.d(TAG, "probe InputManagerGlobal exception", t)
        }
        null
    }

    private fun findInjectMethod(methods: Array<Method>): Method? =
        methods.firstOrNull {
            it.name == "injectInputEvent" &&
                it.parameterTypes.size >= 2 &&
                it.parameterTypes[0] == InputEvent::class.java &&
                it.parameterTypes[1] == Integer.TYPE
        }

    private val setDisplayIdMethod: Method? by lazy {
        (InputEvent::class.java.methods + MotionEvent::class.java.methods)
            .firstOrNull { it.name == "setDisplayId" && it.parameterTypes.size == 1 }
    }

    private var injectFailLogged = false

    private fun inject(
        event: InputEvent,
        displayId: Int,
    ): Boolean {
        val inj = injector
        if (inj == null) {
            recycle(event)
            return false
        }
        return try {
            setDisplayIdMethod?.invoke(event, displayId)
            val m = inj.method
            // Mode 0 = INJECT_INPUT_EVENT_MODE_ASYNC (same as scrcpy). Mode 2 (WAIT_FOR_FINISH) was used once:
            // every event waited until the target app finished handling it. Holding and dragging on Google Maps (spinner)
            // redraws the map heavily every frame, MOVEs queued up one by one and the touch reader thread was held back,
            // showing as "can't drag, and after release the map keeps spinning on its own" (2026-11-20 user test).
            val result: Boolean =
                when {
                    m.parameterTypes.size == 2 -> m.invoke(inj.receiver, event, INJECT_MODE_ASYNC) as Boolean
                    m.parameterTypes[2] == java.lang.Long.TYPE ->
                        m.invoke(inj.receiver, event, INJECT_MODE_ASYNC, 0L) as Boolean
                    else -> m.invoke(inj.receiver, event, INJECT_MODE_ASYNC, displayId) as Boolean
                }
            if (!result && !injectFailLogged) {
                injectFailLogged = true
                Log.d(TAG, "injectInputEvent returned false method=$m")
            }
            result
        } catch (t: Throwable) {
            if (!injectFailLogged) {
                injectFailLogged = true
                Log.d(TAG, "inject exception method=${inj.method}", t)
            }
            false
        } finally {
            recycle(event)
        }
    }

    /** KeyEvent has no reusable pooling semantics (recycle is deprecated); only MotionEvent needs recycling. */
    private fun recycle(event: InputEvent) {
        (event as? MotionEvent)?.recycle()
    }

    /**
     * Key injection (iDrive knob -> D-pad/confirm key): shares injectInputEvent with touch;
     * the only difference is the event is a KeyEvent, likewise setDisplayId'd to the target virtual display.
     */
    override fun keyEvent(
        displayId: Int,
        keyCode: Int,
    ): Boolean {
        val now = SystemClock.uptimeMillis()
        val down = KeyEvent(now, now, KeyEvent.ACTION_DOWN, keyCode, 0)
        if (!inject(down, displayId)) return false
        val up = KeyEvent(now, now + 24, KeyEvent.ACTION_UP, keyCode, 0)
        return inject(up, displayId)
    }

    @Suppress("DEPRECATION")
    private fun build(
        downTime: Long,
        eventTime: Long,
        action: Int,
        x: Float,
        y: Float,
    ): MotionEvent? =
        try {
            val e =
                MotionEvent.obtain(
                    downTime,
                    eventTime,
                    action,
                    x,
                    y,
                    1f,
                    1f,
                    0,
                    1f,
                    1f,
                    0,
                    0,
                )
            e.setSource(InputDevice.SOURCE_TOUCHSCREEN)
            e
        } catch (t: Throwable) {
            Log.d(TAG, "build MotionEvent exception", t)
            null
        }

    override fun tap(
        displayId: Int,
        x: Int,
        y: Int,
    ): Boolean {
        val now = SystemClock.uptimeMillis()
        val down = build(now, now, MotionEvent.ACTION_DOWN, x.toFloat(), y.toFloat()) ?: return false
        if (!inject(down, displayId)) return false
        val up = build(now, now + 50, MotionEvent.ACTION_UP, x.toFloat(), y.toFloat()) ?: return false
        return inject(up, displayId)
    }

    override fun swipe(
        displayId: Int,
        startX: Int,
        startY: Int,
        endX: Int,
        endY: Int,
        durationMs: Int,
    ): Boolean {
        val now = SystemClock.uptimeMillis()
        val duration = durationMs.coerceIn(50, 1500)
        val down = build(now, now, MotionEvent.ACTION_DOWN, startX.toFloat(), startY.toFloat()) ?: return false
        if (!inject(down, displayId)) return false
        val steps = 8
        for (i in 1 until steps) {
            val t = duration * i / steps
            val ix = startX + (endX - startX) * i / steps
            val iy = startY + (endY - startY) * i / steps
            val move = build(now, now + t, MotionEvent.ACTION_MOVE, ix.toFloat(), iy.toFloat()) ?: return false
            if (!inject(move, displayId)) return false
        }
        val up = build(now, now + duration, MotionEvent.ACTION_UP, endX.toFloat(), endY.toFloat()) ?: return false
        return inject(up, displayId)
    }

    private var dragDownTime = 0L
    private var dragDisplay = -1

    override fun pointerDown(
        displayId: Int,
        x: Int,
        y: Int,
    ): Boolean {
        val now = SystemClock.uptimeMillis()
        dragDownTime = now
        dragDisplay = displayId
        val down = build(now, now, MotionEvent.ACTION_DOWN, x.toFloat(), y.toFloat()) ?: return false
        return inject(down, displayId)
    }

    override fun pointerMove(
        displayId: Int,
        x: Int,
        y: Int,
    ): Boolean {
        if (dragDisplay != displayId) return false
        val now = SystemClock.uptimeMillis()
        val move = build(dragDownTime, now, MotionEvent.ACTION_MOVE, x.toFloat(), y.toFloat()) ?: return false
        return inject(move, displayId)
    }

    override fun pointerUp(
        displayId: Int,
        x: Int,
        y: Int,
    ): Boolean {
        if (dragDisplay != displayId) return false
        dragDisplay = -1
        val now = SystemClock.uptimeMillis()
        val up = build(dragDownTime, now, MotionEvent.ACTION_UP, x.toFloat(), y.toFloat()) ?: return false
        return inject(up, displayId)
    }

    // ---- Multi-touch: one stream, pointers keyed by contact slot (upstream shaonialife/cardeck 3a83dc6) ----

    private class SlotTouch(
        val pointerId: Int,
        val displayId: Int,
        var x: Float,
        var y: Float,
    )

    private val slotTouches = java.util.TreeMap<Int, SlotTouch>()
    private var touchDownTime = 0L

    /** The stream has carried two or more pointers: from then on every event lists all pointers (multi form). */
    private var multiStream = false

    @Synchronized
    override fun touchDown(
        slot: Int,
        displayId: Int,
        x: Int,
        y: Int,
    ): Boolean {
        if (slotTouches.containsKey(slot)) return true
        val now = SystemClock.uptimeMillis()
        if (slotTouches.isEmpty()) {
            val down = build(now, now, MotionEvent.ACTION_DOWN, x.toFloat(), y.toFloat()) ?: return false
            val ok = inject(down, displayId)
            if (ok) {
                touchDownTime = now
                multiStream = false
                // The single-pointer obtain always uses pointer id 0
                slotTouches[slot] = SlotTouch(0, displayId, x.toFloat(), y.toFloat())
            }
            return ok
        }
        // Both fingers of a pinch are on one display; another display cannot join this stream
        if (slotTouches.values.first().displayId != displayId) return false
        val used = slotTouches.values.map { it.pointerId }.toSet()
        val id = (0..31).first { it !in used }
        slotTouches[slot] = SlotTouch(id, displayId, x.toFloat(), y.toFloat())
        multiStream = true
        val event = buildMulti(now, MotionEvent.ACTION_POINTER_DOWN, slot)
        val ok = event != null && inject(event, displayId)
        if (!ok) slotTouches.remove(slot)
        return ok
    }

    @Synchronized
    override fun touchMove(
        slot: Int,
        displayId: Int,
        x: Int,
        y: Int,
    ): Boolean {
        val touch = slotTouches[slot] ?: return true // stale MOVE after its stream ended
        if (touch.displayId != displayId) return true
        touch.x = x.toFloat()
        touch.y = y.toFloat()
        val now = SystemClock.uptimeMillis()
        val event =
            if (multiStream) {
                buildMulti(now, MotionEvent.ACTION_MOVE, slot)
            } else {
                build(touchDownTime, now, MotionEvent.ACTION_MOVE, touch.x, touch.y)
            } ?: return false
        return inject(event, displayId)
    }

    @Synchronized
    override fun touchUp(
        slot: Int,
        displayId: Int,
        x: Int,
        y: Int,
    ): Boolean {
        val touch = slotTouches[slot] ?: return true
        if (touch.displayId != displayId) return true
        if (x != 0 || y != 0) {
            touch.x = x.toFloat()
            touch.y = y.toFloat()
        }
        val now = SystemClock.uptimeMillis()
        val remaining = slotTouches.size - 1
        // The lifting pointer must still be in the event, so it is removed only afterwards. In a multi stream a
        // single-pointer UP would carry only id 0 and leave the other pointer stuck down (upstream device finding).
        val event =
            if (multiStream) {
                buildMulti(now, if (remaining > 0) MotionEvent.ACTION_POINTER_UP else MotionEvent.ACTION_UP, slot)
            } else {
                build(touchDownTime, now, MotionEvent.ACTION_UP, touch.x, touch.y)
            }
        val ok = event != null && inject(event, displayId)
        slotTouches.remove(slot)
        if (slotTouches.isEmpty()) multiStream = false
        return ok
    }

    /**
     * All active pointers of the stream, sorted by slot. toolType must be FINGER (the default UNKNOWN made apps ignore
     * the injected pinch upstream) and the contact size fields non-zero. Uses the public 14-argument obtain; the target
     * display is set in [inject].
     */
    private fun buildMulti(
        now: Long,
        action: Int,
        actionSlot: Int,
    ): MotionEvent? =
        try {
            val entries = slotTouches.entries.toList()
            val count = entries.size
            val properties = Array(count) { MotionEvent.PointerProperties() }
            val coords = Array(count) { MotionEvent.PointerCoords() }
            var actionIndex = 0
            entries.forEachIndexed { index, (slot, touch) ->
                properties[index].id = touch.pointerId
                properties[index].toolType = MotionEvent.TOOL_TYPE_FINGER
                coords[index].x = touch.x
                coords[index].y = touch.y
                coords[index].pressure = 1f
                coords[index].size = 1f
                coords[index].touchMajor = 40f
                coords[index].touchMinor = 40f
                coords[index].toolMajor = 40f
                coords[index].toolMinor = 40f
                if (slot == actionSlot) actionIndex = index
            }
            val fullAction =
                if (action == MotionEvent.ACTION_POINTER_DOWN || action == MotionEvent.ACTION_POINTER_UP) {
                    action or (actionIndex shl MotionEvent.ACTION_POINTER_INDEX_SHIFT)
                } else {
                    action
                }
            MotionEvent
                .obtain(
                    touchDownTime,
                    now,
                    fullAction,
                    count,
                    properties,
                    coords,
                    0, // metaState
                    0, // buttonState
                    1f, // xPrecision
                    1f, // yPrecision
                    0, // deviceId
                    0, // edgeFlags
                    InputDevice.SOURCE_TOUCHSCREEN,
                    0, // flags
                ).apply { setSource(InputDevice.SOURCE_TOUCHSCREEN) }
        } catch (t: Throwable) {
            Log.d(TAG, "build multi-pointer MotionEvent exception", t)
            null
        }

    // ---- Trusted virtual display ----

    // VIRTUAL_DISPLAY_FLAG_TRUSTED = 1 << 30 (@hide/@SystemApi, not referable at compile time, so the raw value is written)
    private val virtualDisplays = HashMap<Int, android.hardware.display.VirtualDisplay>()

    // ---- IME display policy ----

    /**
     * A trusted virtual display's IME policy defaults to FALLBACK: the system routes the keyboard to the phone's default
     * display and the car never sees it (pinned down 2026-10-17: why tapping the "Settings" search box showed no keyboard on the car).
     * After creating a trusted display we explicitly set LOCAL so the keyboard is drawn on this display and streamed
     * to the car with the composition.
     *
     * The enum value is reflected from the SystemApi constant instead of hard-coding 0: vendor ROMs may tamper with the enum values
     * (a defensive choice from the 2026-10-17 investigation; this phone matches AOSP = 0). If it cannot be read, fall back to 0 and log it.
     */
    private val imePolicyLocal: Int by lazy {
        val value =
            try {
                Class
                    .forName("android.view.WindowManager")
                    .getField("DISPLAY_IME_POLICY_LOCAL")
                    .getInt(null)
            } catch (t: Throwable) {
                fileLog("DISPLAY_IME_POLICY_LOCAL reflection failed, falling back to the AOSP value 0: $t")
                0
            }
        fileLog("DISPLAY_IME_POLICY_LOCAL=$value")
        value
    }

    /**
     * IWindowManager proxy (reflected by method name, independent of AIDL transaction numbers).
     * On this ROM it goes through ServiceManager+Stub.asInterface; the WindowManagerGlobal.getWindowManagerService
     * path gets the process killed (observed 2026-10-17), do not use it.
     */
    private val windowManagerProxy: Any? by lazy {
        try {
            val binder =
                Class
                    .forName("android.os.ServiceManager")
                    .getMethod("getService", String::class.java)
                    .invoke(null, "window")
            Class
                .forName("android.view.IWindowManager\$Stub")
                .getMethod("asInterface", IBinder::class.java)
                .invoke(null, binder)
        } catch (t: Throwable) {
            fileLog("IWindowManager reflection failed (the IME policy will not take effect): $t")
            null
        }
    }

    /**
     * Sets a trusted virtual display's IME policy to LOCAL (keyboard drawn on this display).
     * A failure does not affect the main path: worst case is the same as before the change, the keyboard stays on
     * the phone's main display and is invisible on the car.
     */
    private fun applyImePolicyLocal(displayId: Int) {
        val proxy = windowManagerProxy ?: return
        try {
            proxy.javaClass
                .getMethod("setDisplayImePolicy", Integer.TYPE, Integer.TYPE)
                .invoke(proxy, displayId, imePolicyLocal)
            val readBack =
                runCatching {
                    proxy.javaClass
                        .getMethod("getDisplayImePolicy", Integer.TYPE)
                        .invoke(proxy, displayId)
                }.getOrNull()
            fileLog("setDisplayImePolicy($displayId, LOCAL=$imePolicyLocal) ok, read back=$readBack")
        } catch (t: Throwable) {
            fileLog("setDisplayImePolicy($displayId, LOCAL=$imePolicyLocal) failed: $t")
        }
    }

    /** File log: logcat in the shell process is HKS-encrypted, so write to /data/local/tmp for adb to read. */
    private fun fileLog(message: String) {
        try {
            java.io.FileWriter("/data/local/tmp/carinput_svc.log", true).use { w ->
                w
                    .append(
                        android.text.format.DateFormat
                            .format("HH:mm:ss.SSS", System.currentTimeMillis())
                            .toString(),
                    ).append(' ')
                    .append(message)
                    .append('\n')
            }
        } catch (_: Throwable) {
        }
    }

    // ---- Lock-screen streaming: displays on a VirtualDeviceManager device (see VirtualDeviceSession) ----

    @Volatile
    private var companionMac: String? = null

    @Volatile
    private var vdmFailedThisSession = false

    private val vdmLock = Any()
    private var vdmSession: VirtualDeviceSession? = null

    override fun setCompanionDevice(btMac: String?) {
        val mac = btMac?.trim()?.takeIf { it.isNotEmpty() }
        synchronized(vdmLock) {
            if (!mac.equals(companionMac, ignoreCase = true)) {
                // Another car: the old device and its displays (a parked map of that car) go
                vdmSession?.let { old -> runCatching { old.close() } }
                vdmSession = null
            }
            vdmSession?.closeWhenEmpty = false
            companionMac = mac
            vdmFailedThisSession = false
        }
        fileLog("VDM: companion device " + (mac ?: "none, legacy displays"))
    }

    override fun closeVirtualDevice(): Boolean {
        synchronized(vdmLock) {
            val session = vdmSession ?: return true
            if (session.displayCount() > 0) {
                // A display stays parked (navigation map kept across a disconnect): close when it is released
                session.closeWhenEmpty = true
                fileLog("VDM: close deferred, ${session.displayCount()} display(s) still on the device")
                return true
            }
            vdmSession = null
            return runCatching { session.close() }.isSuccess
        }
    }

    private fun obtainVdmSession(): VirtualDeviceSession? {
        if (!VirtualDeviceRoute.shouldAttempt(Build.VERSION.SDK_INT, companionMac, vdmFailedThisSession)) return null
        synchronized(vdmLock) {
            if (vdmFailedThisSession) return null
            val mac = companionMac ?: return null
            vdmSession?.let { if (it.mac.equals(mac, ignoreCase = true)) return it }
            return try {
                VirtualDeviceSession(shellContext(), mac, ::fileLog).also {
                    it.ensure()
                    vdmSession = it
                    fileLog("VDM: ready ${it.describe()}")
                }
            } catch (t: Throwable) {
                vdmFailedThisSession = true
                fileLog("VDM: unavailable this session, legacy displays (${rootCause(t)})")
                null
            }
        }
    }

    private fun rootCause(t: Throwable): String {
        var cause = t
        var depth = 0
        while (cause.cause != null && cause.cause !== cause && depth++ < 5) cause = cause.cause!!
        return "${cause.javaClass.name}: ${cause.message}"
    }

    /** Display on the virtual device, or null to fall back to the legacy path. */
    private fun createVdmDisplay(
        name: String,
        width: Int,
        height: Int,
        dpi: Int,
        surface: Surface,
        trusted: Boolean,
    ): Int? {
        val session = obtainVdmSession() ?: return null
        val candidates = if (trusted) VirtualDeviceRoute.trustedFlagCandidates else VirtualDeviceRoute.dockFlagCandidates
        for (flags in candidates) {
            val display =
                runCatching { session.createDisplay(name, width, height, dpi, surface, flags) }
                    .onFailure { fileLog("VDM: $name flags=0x${flags.toString(16)} failed: ${rootCause(it)}") }
                    .getOrNull() ?: continue
            val id = display.display.displayId
            synchronized(virtualDisplays) { virtualDisplays[id] = display }
            if (trusted) applyImePolicyLocal(id)
            fileLog("VDM: $name id=$id flags=0x${flags.toString(16)} on the virtual device")
            return id
        }
        vdmFailedThisSession = true
        fileLog("VDM: $name could not be created on the virtual device - legacy displays this session")
        return null
    }

    // WrongConstant: TRUSTED/ALWAYS_UNLOCKED/OWN_DISPLAY_GROUP in flags are @hide constants
    // (runtime viability confirmed on a real device); lint's IntDef model only knows the public API.
    @android.annotation.SuppressLint("WrongConstant")
    override fun createDisplay(
        name: String,
        width: Int,
        height: Int,
        dpi: Int,
        surface: Surface,
        trusted: Boolean,
    ): Int {
        return try {
            fileLog("createDisplay $name ${width}x$height@$dpi start")
            // Virtual device first (keeps streaming while the phone is locked or asleep); any failure -> legacy below
            createVdmDisplay(name, width, height, dpi, surface, trusted)?.let { return it }
            // Key point: the "shell package Context" must be used: the system checks that packageName matches the calling uid (2000),
            // and the app's own Context throws SecurityException('packageName must match the owner uid')
            val shellContext =
                try {
                    context.createPackageContext("com.android.shell", 0)
                } catch (t: Throwable) {
                    fileLog("createPackageContext(com.android.shell) failed: $t, falling back to the app context")
                    context
                }
            fileLog("ctx package=${shellContext.packageName}")
            val dm = shellContext.getSystemService(Context.DISPLAY_SERVICE) as android.hardware.display.DisplayManager
            // VIRTUAL_DISPLAY_FLAG_SUPPORTS_TOUCH = 1 << 6 (@hide):
            // when not declared, apps see a "-touch" configuration (no touch device) and some apps (e.g. Toutiao)
            // switch to a TV/desktop-style layout with a huge header (2026-10-11 field test: root cause of the search
            // area filling half the screen).
            //
            // VIRTUAL_DISPLAY_FLAG_PUBLIC (public API = 1) must be declared along with the trusted display (pinned down 2026-10-17):
            // without it DisplayDeviceInfo carries FLAG_PRIVATE (owner=shell 2000), and the access gate of WMS.addWindow,
            // displayContent.hasAccess(session.mUid) -> Display.hasAccess, lets only owner/system/root/UID-present into a private display,
            // so a third-party IME's (uid 10xxx) keyboard window is refused with
            // ADD_INVALID_DISPLAY(-9) and can never be added to this display (the final root cause of no keyboard when tapping the
            // "Settings" search box on the car: policy already LOCAL, token already moved, IME service already called
            // showWindow, yet the window cannot be added).
            // Making it public only relaxes window access; the ROM's "public displays auto-mirror by default" applies only when
            // OWN_CONTENT_ONLY is not declared, which we do declare, so it is unaffected. The dock display is untrusted and has no input
            // scenario, so it stays private (this also keeps unrelated windows from attaching).
            val baseFlags =
                (if (trusted) FLAG_TRUSTED or android.hardware.display.DisplayManager.VIRTUAL_DISPLAY_FLAG_PUBLIC else 0) or
                    FLAG_SUPPORTS_TOUCH or
                    FLAG_DESTROY_CONTENT_ON_REMOVAL or
                    android.hardware.display.DisplayManager.VIRTUAL_DISPLAY_FLAG_OWN_CONTENT_ONLY
            // Trusted displays additionally need "own display group + always unlocked":
            //  - OWN_DISPLAY_GROUP: the virtual display does not sleep with the phone's main display (frames continue
            //  after the panel is powered off);
            //  - ALWAYS_UNLOCKED: when the device is locked this display does not draw Keyguard (otherwise the car
            //  sees a lock screen/black).
            // OWN_DISPLAY_GROUP reuses ADD_TRUSTED_DISPLAY (trusted displays need it anyway); ALWAYS_UNLOCKED
            // also needs ADD_ALWAYS_UNLOCKED_DISPLAY, which some ROMs do not grant to shell and passing it throws
            // SecurityException that kills the whole display, hence the stepwise degrade-and-retry.
            var vd: android.hardware.display.VirtualDisplay? = null
            if (trusted) {
                vd =
                    createVirtualDisplayAttempt(
                        dm,
                        name,
                        width,
                        height,
                        dpi,
                        surface,
                        baseFlags or FLAG_OWN_DISPLAY_GROUP or FLAG_ALWAYS_UNLOCKED,
                        "trusted+own group+ALWAYS_UNLOCKED",
                    )
                if (vd == null) {
                    fileLog("$name with ALWAYS_UNLOCKED failed - degrading to own group (the ROM may not grant shell that permission)")
                    vd =
                        createVirtualDisplayAttempt(
                            dm,
                            name,
                            width,
                            height,
                            dpi,
                            surface,
                            baseFlags or FLAG_OWN_DISPLAY_GROUP,
                            "trusted+own group",
                        )
                }
                if (vd == null) {
                    fileLog("$name with OWN_DISPLAY_GROUP failed - falling back to base flags")
                    vd = createVirtualDisplayAttempt(dm, name, width, height, dpi, surface, baseFlags, "trusted base")
                }
            } else {
                // The dock display (untrusted) lands in the **default display group** by default: when the physical
                // main display is powered off/locked the whole group goes dark,
                // shown on the car as "app display fine, dock bar all black", recovering after unlock (2026-10-23 field test).
                // Same three-step degradation as the trusted display: group+unlocked -> group -> base.
                //  - AOSP clears OWN_DISPLAY_GROUP only with AUTO_MIRROR, and AUTO_MIRROR is set automatically only with
                //    PUBLIC; the dock does not declare PUBLIC, so the flag is kept;
                //  - both only require permissions (ADD_TRUSTED_DISPLAY / ADD_ALWAYS_UNLOCKED_DISPLAY) and
                //    do not change the dock's "untrusted" semantics (third-party tasks still cannot get onto it).
                vd =
                    createVirtualDisplayAttempt(
                        dm,
                        name,
                        width,
                        height,
                        dpi,
                        surface,
                        baseFlags or FLAG_OWN_DISPLAY_GROUP or FLAG_ALWAYS_UNLOCKED,
                        "untrusted+own group+ALWAYS_UNLOCKED",
                    )
                if (vd == null) {
                    fileLog("$name with ALWAYS_UNLOCKED failed - degrading to own group (the ROM may not grant shell that permission)")
                    vd =
                        createVirtualDisplayAttempt(
                            dm,
                            name,
                            width,
                            height,
                            dpi,
                            surface,
                            baseFlags or FLAG_OWN_DISPLAY_GROUP,
                            "untrusted+own group",
                        )
                }
                if (vd == null) {
                    fileLog(
                        "$name with OWN_DISPLAY_GROUP failed - falling back to base flags " +
                            "(this display will sleep with the main display when it powers off)",
                    )
                    vd = createVirtualDisplayAttempt(dm, name, width, height, dpi, surface, baseFlags, "untrusted")
                }
            }
            val created = vd
            val id = created?.display?.displayId ?: -1
            fileLog("createVirtualDisplay -> vd=$created id=$id")
            // Read-back check (2026-10-23 Honor field test): when the flag takes effect the Display-layer flags should include
            // ALWAYS_UNLOCKED (1 << 9); without OWN_DISPLAY_GROUP the system silently strips
            // ALWAYS_UNLOCKED (creation succeeds with no exception but the read-back lacks it), so only a read-back can reveal it.
            if (id >= 0) {
                val realFlags =
                    try {
                        dm.getDisplay(id)?.flags ?: 0
                    } catch (_: Throwable) {
                        0
                    }
                val effective = (realFlags and 0x200) != 0
                fileLog(
                    "ALWAYS_UNLOCKED read-back display.flags=0x${realFlags.toString(16)} -> " +
                        if (effective) "in effect" else "not in effect (lock screen still affects this display)",
                )
            }
            if (created == null || id < 0) {
                Log.w(TAG, "createDisplay $name -> null")
                return -1
            }
            synchronized(virtualDisplays) { virtualDisplays[id] = created }
            // Only trusted displays can set the IME policy (WMS throws SecurityException for untrusted ones), and they need it:
            // the dock display hosts only its own DockActivity, no input scenario
            if (trusted) applyImePolicyLocal(id)
            Log.i(TAG, "createDisplay $name id=$id size=${width}x$height@$dpi")
            id
        } catch (t: Throwable) {
            fileLog("createDisplay $name failed: $t")
            Log.w(TAG, "createDisplay $name failed: $t")
            -1
        }
    }

    override fun resizeDisplay(
        displayId: Int,
        width: Int,
        height: Int,
        dpi: Int,
    ): Boolean {
        val vd = synchronized(virtualDisplays) { virtualDisplays[displayId] } ?: return false
        return try {
            vd.resize(width, height, dpi)
            Log.i(TAG, "resizeDisplay id=$displayId -> ${width}x$height@$dpi")
            true
        } catch (t: Throwable) {
            Log.w(TAG, "resizeDisplay $displayId failed: $t")
            false
        }
    }

    /** Assistant package name -> uid cache (-1 = not installed). */
    private val assistantUids = HashMap<String, Int>()

    override fun isAssistantListening(): Boolean =
        try {
            val shellContext =
                try {
                    context.createPackageContext("com.android.shell", 0)
                } catch (_: Throwable) {
                    context
                }
            val appOps = shellContext.getSystemService(android.app.AppOpsManager::class.java)
            ASSISTANT_PACKAGES.any { pkg ->
                val uid =
                    synchronized(assistantUids) {
                        assistantUids.getOrPut(pkg) {
                            try {
                                context.packageManager.getPackageUid(pkg, 0)
                            } catch (_: Throwable) {
                                -1
                            }
                        }
                    }
                uid >= 0 && ASSISTANT_MIC_OPS.any { op -> runCatching { appOps.isOpActive(op, uid, pkg) }.getOrDefault(false) }
            }
        } catch (t: Throwable) {
            fileLog("isAssistantListening failed: $t")
            false
        }

    override fun detachDisplaySurface(displayId: Int): Boolean =
        synchronized(virtualDisplays) {
            val display = virtualDisplays[displayId] ?: return@synchronized false
            runCatching { display.setSurface(null) }
                .onFailure { fileLog("detachDisplaySurface #$displayId failed: $it") }
                .isSuccess
        }

    override fun releaseDisplay(displayId: Int): Boolean {
        val vd = synchronized(virtualDisplays) { virtualDisplays.remove(displayId) }
        if (vd == null) {
            // Not in this process's map: the display was created by another (old-version) UserService process, and
            // only its creator can release it.
            // The app-side fallback is CarInputClient.reapStaleUserServices (kills the holding process).
            fileLog("releaseDisplay id=$displayId -> not held by this process (cannot be released across processes)")
            return false
        }
        return try {
            vd.release()
            Log.i(TAG, "releaseDisplay id=$displayId")
            synchronized(vdmLock) {
                val session = vdmSession
                if (session != null && session.discard(displayId)) {
                    vdmSession = null
                    runCatching { session.close() }
                }
            }
            true
        } catch (t: Throwable) {
            Log.w(TAG, "releaseDisplay $displayId failed: $t")
            false
        }
    }

    /**
     * Creates a virtual display once; on failure (including SecurityException / ROM silently returning null) returns
     * null and leaves a trace.
     */
    private fun createVirtualDisplayAttempt(
        dm: android.hardware.display.DisplayManager,
        name: String,
        width: Int,
        height: Int,
        dpi: Int,
        surface: Surface,
        flags: Int,
        label: String,
    ): android.hardware.display.VirtualDisplay? =
        try {
            fileLog("createDisplay($name) $label flags=0x${flags.toString(16)}")
            dm.createVirtualDisplay(name, width, height, dpi, surface, flags, null, null)
        } catch (t: Throwable) {
            fileLog("createDisplay($name) $label exception: $t")
            null
        }

    // ---- Physical main display power (true screen-off; the system stays awake) ----

    /**
     * Hardware-level power off/on of the **physical main display** (same as scrcpy `-S/--turn-screen-off`):
     * instead of pressing the power key (which locks the screen) it calls SurfaceFlinger's setDisplayPowerMode directly.
     * The system still believes the screen is on -> no lock, no Doze, and virtual displays keep producing frames.
     */
    override fun setPhysicalScreenPower(on: Boolean): Boolean {
        val ok = setPanelPower(on)
        if (ok) physicalScreenOff = !on
        fileLog("setPhysicalScreenPower($on) ok=$ok")
        return ok
    }

    /**
     * Core of panel power (merge point of two implementations, 2026-10-24):
     * the three token paths are ordered by measured pass rate (any ROM works as long as one path is usable);
     * power off/on all go through SurfaceControl.setDisplayPowerMode by reflection.
     */
    private fun setPanelPower(on: Boolean): Boolean {
        val token =
            physicalDisplayToken() ?: run {
                fileLog("setPanelPower($on) failed: no entry point gave the built-in display token")
                return false
            }
        val mode = if (on) POWER_MODE_NORMAL else POWER_MODE_OFF
        return try {
            Class
                .forName("android.view.SurfaceControl")
                .getMethod("setDisplayPowerMode", IBinder::class.java, Integer.TYPE)
                .invoke(null, token, mode)
            true
        } catch (t: Throwable) {
            fileLog("setPanelPower($on) failed: $t")
            false
        }
    }

    /**
     * Physical main display token, three paths tried in order (different ROMs remove different surfaces, trying all is the most robust):
     * 1. SurfaceControl.getInternalDisplayToken: the only one that worked on Honor A17 (SDK 37),
     *    put first (MagicOS kept this "older" API);
     * 2. DisplayControl class loader: the canonical SDK 34+ entry (same as scrcpy: classes in services.jar
     *    are visible only via ClassLoaderFactory + android_servers JNI);
     * 3. SurfaceControl.getPhysicalDisplayIds/getPhysicalDisplayToken: the old Android 11~14 entry.
     * The first physical display id is the built-in one (AOSP convention: the first array element is the default display).
     */
    // BlockedPrivateApi/DEPRECATION: this method deliberately uses hidden/deprecated APIs (the UserService runs in the shell
    // domain and is not bound by the app-layer hidden-API enforcement; the runtime behavior of the three paths is
    // confirmed on a real device).
    @android.annotation.SuppressLint("BlockedPrivateApi")
    @Suppress("DEPRECATION")
    private fun physicalDisplayToken(): IBinder? {
        // 1. getInternalDisplayToken (still kept in SurfaceControl on some ROMs)
        runCatching {
            val sc = SurfaceControl::class.java
            (sc.getDeclaredMethod("getInternalDisplayToken").invoke(null) as? IBinder)?.let {
                fileLog("token via getInternalDisplayToken")
                return it
            }
        }.onFailure { fileLog("getInternalDisplayToken entry failed: ${it.message}") }
        // 2. DisplayControl (newer systems: the methods moved out of SurfaceControl)
        displayControlClass?.let { cls ->
            runCatching {
                val ids = cls.getMethod("getPhysicalDisplayIds").invoke(null) as LongArray
                val id = ids.firstOrNull() ?: return@runCatching
                (cls.getMethod("getPhysicalDisplayToken", Long::class.javaPrimitiveType!!).invoke(null, id) as? IBinder)?.let {
                    fileLog("token via DisplayControl id=$id")
                    return it
                }
            }.onFailure { fileLog("DisplayControl token entry failed: ${it.message}") }
        }
        // 3. getPhysicalDisplayIds/getPhysicalDisplayToken (Android 11~14)
        runCatching {
            val sc = SurfaceControl::class.java
            val ids = sc.getDeclaredMethod("getPhysicalDisplayIds").invoke(null) as LongArray
            val id = ids.firstOrNull() ?: return@runCatching
            (sc.getDeclaredMethod("getPhysicalDisplayToken", Long::class.javaPrimitiveType).invoke(null, id) as? IBinder)?.let {
                fileLog("token via SurfaceControl.getPhysicalDisplayToken id=$id")
                return it
            }
        }.onFailure { fileLog("getPhysicalDisplayToken entry failed: ${it.message}") }
        return null
    }

    /** DisplayControl class loader (for true screen-off): runs only inside the Shizuku UserService (shell uid). */
    private val displayControlClass: Class<*>? by lazy {
        try {
            val factory = Class.forName("com.android.internal.os.ClassLoaderFactory")
            val create =
                factory.getDeclaredMethod(
                    "createClassLoader",
                    String::class.java,
                    String::class.java,
                    String::class.java,
                    ClassLoader::class.java,
                    Integer.TYPE,
                    java.lang.Boolean.TYPE,
                    String::class.java,
                )
            val classpath = System.getenv("SYSTEMSERVERCLASSPATH")
            if (classpath.isNullOrEmpty()) {
                fileLog("SYSTEMSERVERCLASSPATH is empty - cannot load DisplayControl")
                return@lazy null
            }
            val loader =
                create.invoke(
                    null,
                    classpath,
                    null,
                    null,
                    ClassLoader.getSystemClassLoader(),
                    0,
                    true,
                    null,
                ) as ClassLoader
            val cls = loader.loadClass("com.android.server.display.DisplayControl")
            // The static JNI methods are registered in libandroid_servers.so; it must be loaded into this ClassLoader
            registerAndroidServersLibrary(cls)
            fileLog("DisplayControl ready")
            cls
        } catch (t: Throwable) {
            fileLog("DisplayControl load failed (SDK ${Build.VERSION.SDK_INT}): $t")
            null
        }
    }

    /**
     * Registers libandroid_servers.so's JNI into the ClassLoader of [cls] (same path as scrcpy).
     *
     * This class runs only in the Shizuku UserService (app_process of shell uid 2000) and is not subject to the app process's
     * hidden-API restrictions; lint's BlockedPrivateApi judges by the targetSdk app process, hence the exemption here.
     */
    @SuppressLint("BlockedPrivateApi")
    private fun registerAndroidServersLibrary(cls: Class<*>) {
        val loadLibrary0 =
            Runtime::class.java.getDeclaredMethod("loadLibrary0", Class::class.java, String::class.java)
        loadLibrary0.isAccessible = true
        loadLibrary0.invoke(Runtime.getRuntime(), cls, "android_servers")
    }

    override fun ping(): Boolean = injector != null

    override fun setScreenPower(on: Boolean): Boolean {
        val ok = setPanelPower(on)
        fileLog("setScreenPower($on) ok=$ok")
        return ok
    }

    override fun diag(): String =
        "injector=" + (injector?.let { "${it.receiver.javaClass.name} ${it.method}" } ?: "null") +
            " setDisplayId=${setDisplayIdMethod != null}"

    override fun pid(): Int = android.os.Process.myPid()

    companion object {
        private const val TAG = "CarInputSvc"

        /** InputManager.INJECT_INPUT_EVENT_MODE_ASYNC (@hide, always 0 in AOSP). */
        private const val INJECT_MODE_ASYNC = 0

        /** Self-reap patrol period: after an APK reinstall the old process exits on its own within ~15s at most (cost: one PM query). */
        private const val STALE_CHECK_INTERVAL_MS = 15_000L

        /** VIRTUAL_DISPLAY_FLAG_TRUSTED (@hide; AOSP defines = 1 << 10 = 0x400). */
        /** Apps that pop up the voice-assistant overlay: the Google app (Gemini overlay host) and the standalone Gemini app. */
        private val ASSISTANT_PACKAGES = listOf("com.google.android.googlequicksearchbox", "com.google.android.apps.bard")

        /** Mic-related AppOps (as strings; hotword is a hidden constant, but passing it as a string works just as well). */
        private val ASSISTANT_MIC_OPS = listOf("android:record_audio", "android:record_audio_hotword")

        private const val FLAG_TRUSTED = 1 shl 10

        /** VIRTUAL_DISPLAY_FLAG_SUPPORTS_TOUCH (@hide; AOSP defines = 1 << 6 = 0x40). */
        private const val FLAG_SUPPORTS_TOUCH = 1 shl 6

        /**
         * VIRTUAL_DISPLAY_FLAG_DESTROY_CONTENT_ON_REMOVAL (@hide; AOSP defines = 1 << 8 = 0x100).
         * When not declared, releasing a virtual display makes the system **move its tasks to the phone's main display**, adding
         * one map/music card to the phone's recents on every disconnect (2026-11-18 user test). When declared the tasks are destroyed
         * with the display (the process is not killed; navigation/music foreground services carry on).
         */
        private const val FLAG_DESTROY_CONTENT_ON_REMOVAL = 1 shl 8

        /**
         * VIRTUAL_DISPLAY_FLAG_OWN_DISPLAY_GROUP (@hide; AOSP defines = 1 << 11 = 0x800).
         * The virtual display joins its own DisplayGroup: when the phone's main display sleeps/powers off it does not go OFF with it.
         * Needs ADD_TRUSTED_DISPLAY (trusted displays need it anyway); see the check in DisplayManagerService.
         */
        private const val FLAG_OWN_DISPLAY_GROUP = 1 shl 11

        /**
         * VIRTUAL_DISPLAY_FLAG_ALWAYS_UNLOCKED (@hide; AOSP defines = 1 << 12 = 0x1000).
         * While the device is locked this display does not show Keyguard. **Also needs ADD_ALWAYS_UNLOCKED_DISPLAY**,
         * which some ROMs do not grant to shell; createDisplay must be able to degrade and retry, or the whole display fails to be created.
         */
        private const val FLAG_ALWAYS_UNLOCKED = 1 shl 12

        /** SurfaceControl display power modes: OFF = hardware power off, NORMAL = lit normally. */
        private const val POWER_MODE_OFF = 0
        private const val POWER_MODE_NORMAL = 2

        /** SurfaceControl display power modes (@hide; AOSP defines OFF=0 / NORMAL=2, used as a fallback when reflection fails). */
    }
}

/** Injected recording sources: MIC / VOICE_RECOGNITION / VOICE_COMMUNICATION / DEFAULT / UNPROCESSED. */
private val MIC_INJECT_PRESETS = intArrayOf(1, 6, 7, 0, 9)
