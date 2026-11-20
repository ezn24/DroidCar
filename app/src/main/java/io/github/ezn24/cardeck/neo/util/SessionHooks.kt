package io.github.ezn24.cardeck.neo.util

import android.content.Context
import android.content.Intent
import android.net.Uri
import io.github.ezn24.cardeck.neo.SessionController
import org.json.JSONArray
import org.json.JSONObject

/**
 * 连接成功 / 断开时发出用户自定义的 Intent（2026-11-18 用户要求，MacroDroid「发送 Intent」式）。
 *
 * 每个时机一组 [HookIntent]，逐个按「目标」发出：广播 / 启动 Activity / 启动服务。字段与 MacroDroid
 * 对齐：Action、包名、类名、Data、MIME、Categories、带类型的 Extras。统一附加 extra
 * `cardeck_event` = `connected` / `disconnected`。
 *
 * 断开只在发过「连接」之后发一次（会话收尾有 onEnded / disconnect 两条路径，避免重复）。
 */
object SessionHooks {
    private const val PREFS = "session_hooks"
    const val EVENT_CONNECTED = "connected"
    const val EVENT_DISCONNECTED = "disconnected"
    const val EXTRA_EVENT = "cardeck_event"

    /** 新格式存在「事件名_list」键下（JSON 数组）；旧版单行文本存在「事件名」键下，首次读取时迁移。 */
    private fun listKey(event: String) = "${event}_list"

    enum class Target { BROADCAST, ACTIVITY, SERVICE }

    enum class ExtraType { STRING, INT, LONG, BOOLEAN, FLOAT }

    data class Extra(
        val key: String,
        val type: ExtraType,
        val value: String,
    )

    data class HookIntent(
        val target: Target = Target.BROADCAST,
        val action: String = "",
        val packageName: String = "",
        val className: String = "",
        val data: String = "",
        val mimeType: String = "",
        val categories: List<String> = emptyList(),
        val extras: List<Extra> = emptyList(),
        val enabled: Boolean = true,
    ) {
        /** 列表里显示的一行摘要。 */
        fun summary(): String =
            when {
                action.isNotBlank() -> action
                className.isNotBlank() -> "$packageName/$className"
                packageName.isNotBlank() -> packageName
                else -> data
            }
    }

    @Volatile private var connectedFired = false

    // ---- 存取 ----

    fun load(
        context: Context,
        event: String,
    ): List<HookIntent> {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        prefs.getString(listKey(event), null)?.let { return runCatching { decode(it) }.getOrDefault(emptyList()) }
        // 旧版：单行 action 名或 intent: URI
        val legacy = prefs.getString(event, "").orEmpty().trim()
        val migrated = if (legacy.isBlank()) emptyList() else listOfNotNull(fromLegacy(legacy))
        save(context, event, migrated)
        prefs.edit().remove(event).apply()
        return migrated
    }

    fun save(
        context: Context,
        event: String,
        intents: List<HookIntent>,
    ) {
        context
            .getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putString(listKey(event), encode(intents))
            .apply()
    }

    // ---- 会话钩子 ----

    /**
     * 两个钩子都在后台发：调用点分别是推流起步回调（车机看门狗计时中）与断开（可能在主线程），
     * 读偏好、启动别的 App 都不该占这些线程。没配置时只多一次内存里的偏好读取。
     */
    fun onConnected(context: Context) {
        connectedFired = true
        val app = context.applicationContext
        Bg.run("hook-connected") { fireAll(app, EVENT_CONNECTED) }
    }

    fun onDisconnected(context: Context) {
        if (!connectedFired) return
        connectedFired = false
        val app = context.applicationContext
        Bg.run("hook-disconnected") { fireAll(app, EVENT_DISCONNECTED) }
    }

    private fun fireAll(
        context: Context,
        event: String,
    ) {
        load(context, event).filter { it.enabled }.forEach { send(context, it, event) }
    }

    /** 发出一个 Intent，返回结果说明（测试按钮显示用）。任意线程可调。 */
    fun send(
        context: Context,
        hook: HookIntent,
        event: String,
    ): String {
        val app = context.applicationContext
        val result =
            runCatching {
                val intent = build(hook).putExtra(EXTRA_EVENT, event)
                when (hook.target) {
                    Target.BROADCAST -> {
                        app.sendBroadcast(intent)
                        "OK"
                    }

                    Target.ACTIVITY -> {
                        app.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                        "OK"
                    }

                    Target.SERVICE -> {
                        val started =
                            runCatching { app.startService(intent) }
                                .recoverCatching { app.startForegroundService(intent) }
                                .getOrThrow()
                        if (started == null) "找不到服务" else "OK"
                    }
                }
            }.getOrElse { "${it.javaClass.simpleName}: ${it.message}" }
        SessionController.appendLog("HOOK: $event → ${hook.target} ${hook.summary()} → $result")
        return result
    }

    fun build(hook: HookIntent): Intent {
        val intent = Intent()
        if (hook.action.isNotBlank()) intent.action = hook.action.trim()
        val pkg = hook.packageName.trim()
        val cls = hook.className.trim()
        when {
            pkg.isNotEmpty() && cls.isNotEmpty() -> intent.setClassName(pkg, if (cls.startsWith(".")) pkg + cls else cls)
            pkg.isNotEmpty() -> intent.setPackage(pkg)
        }
        val data =
            hook.data
                .trim()
                .takeIf { it.isNotEmpty() }
                ?.let(Uri::parse)
        val mime = hook.mimeType.trim().takeIf { it.isNotEmpty() }
        when {
            data != null && mime != null -> intent.setDataAndType(data, mime)
            data != null -> intent.data = data
            mime != null -> intent.type = mime
        }
        hook.categories
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .forEach { intent.addCategory(it) }
        hook.extras.filter { it.key.isNotBlank() }.forEach { extra ->
            val key = extra.key.trim()
            when (extra.type) {
                ExtraType.STRING -> intent.putExtra(key, extra.value)
                ExtraType.INT -> intent.putExtra(key, extra.value.trim().toInt())
                ExtraType.LONG -> intent.putExtra(key, extra.value.trim().toLong())
                ExtraType.BOOLEAN -> intent.putExtra(key, extra.value.trim().equals("true", ignoreCase = true))
                ExtraType.FLOAT -> intent.putExtra(key, extra.value.trim().toFloat())
            }
        }
        return intent
    }

    // ---- JSON（纯函数，有单测） ----

    fun encode(intents: List<HookIntent>): String =
        JSONArray()
            .apply {
                intents.forEach { hook ->
                    put(
                        JSONObject()
                            .put("target", hook.target.name)
                            .put("action", hook.action)
                            .put("package", hook.packageName)
                            .put("class", hook.className)
                            .put("data", hook.data)
                            .put("mime", hook.mimeType)
                            .put("categories", JSONArray(hook.categories))
                            .put("enabled", hook.enabled)
                            .put(
                                "extras",
                                JSONArray().apply {
                                    hook.extras.forEach { e ->
                                        put(JSONObject().put("key", e.key).put("type", e.type.name).put("value", e.value))
                                    }
                                },
                            ),
                    )
                }
            }.toString()

    fun decode(json: String): List<HookIntent> {
        val array = JSONArray(json)
        return (0 until array.length()).map { i ->
            val o = array.getJSONObject(i)
            val cats = o.optJSONArray("categories")
            val extras = o.optJSONArray("extras")
            HookIntent(
                target = runCatching { Target.valueOf(o.optString("target")) }.getOrDefault(Target.BROADCAST),
                action = o.optString("action"),
                packageName = o.optString("package"),
                className = o.optString("class"),
                data = o.optString("data"),
                mimeType = o.optString("mime"),
                categories = cats?.let { a -> (0 until a.length()).map { a.getString(it) } }.orEmpty(),
                extras =
                    extras
                        ?.let { a ->
                            (0 until a.length()).map {
                                val e = a.getJSONObject(it)
                                Extra(
                                    e.optString("key"),
                                    runCatching { ExtraType.valueOf(e.optString("type")) }.getOrDefault(ExtraType.STRING),
                                    e.optString("value"),
                                )
                            }
                        }.orEmpty(),
                enabled = o.optBoolean("enabled", true),
            )
        }
    }

    /** 旧版单行文本 → 结构化：intent: URI 拆字段（extras 只保留字符串值），否则当 action 名。 */
    private fun fromLegacy(spec: String): HookIntent? =
        if (spec.startsWith("intent:") || spec.startsWith("#Intent;")) {
            runCatching {
                val intent = Intent.parseUri(spec, Intent.URI_INTENT_SCHEME)
                HookIntent(
                    target = Target.BROADCAST,
                    action = intent.action.orEmpty(),
                    packageName = intent.component?.packageName ?: intent.`package`.orEmpty(),
                    className = intent.component?.className.orEmpty(),
                    data = intent.dataString.orEmpty(),
                    mimeType = intent.type.orEmpty(),
                    categories = intent.categories?.toList().orEmpty(),
                    extras =
                        intent.extras
                            ?.keySet()
                            ?.map {
                                Extra(
                                    it,
                                    ExtraType.STRING,
                                    intent.extras
                                        ?.get(it)
                                        ?.toString()
                                        .orEmpty(),
                                )
                            }.orEmpty(),
                )
            }.getOrNull()
        } else {
            HookIntent(action = spec)
        }
}
