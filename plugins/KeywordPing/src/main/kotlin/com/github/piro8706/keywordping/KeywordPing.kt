package com.github.piro8706.keywordping

import android.content.Context
import android.os.Bundle
import android.text.Editable
import android.text.InputType
import android.text.TextWatcher
import android.view.View
import android.widget.TextView
import com.aliucord.Utils
import com.aliucord.annotations.AliucordPlugin
import com.aliucord.api.NotificationsAPI
import com.aliucord.api.SettingsAPI
import com.aliucord.entities.NotificationData
import com.aliucord.entities.Plugin
import com.aliucord.patcher.before
import com.aliucord.views.TextInput
import com.aliucord.widgets.BottomSheet
import com.aliucord.wrappers.ChannelWrapper.Companion.guildId
import com.aliucord.wrappers.ChannelWrapper.Companion.name
import com.aliucord.wrappers.users.globalName
import com.discord.models.user.CoreUser
import com.discord.stores.StoreStream
import com.discord.utilities.user.UserUtils
import com.discord.views.CheckedSetting
import java.util.regex.Pattern
import com.discord.api.message.Message as ApiMessage
import com.discord.api.user.User as ApiUser
import com.discord.models.message.Message as ModelMessage

private const val KEY_KEYWORDS = "keywords"
private const val KEY_VIPS = "vips"
private const val KEY_IGNORED = "ignoredGuilds"
private const val KEY_NATIVE = "nativeMention"
private const val KEY_POPUP = "popup"

sealed class Scope {
    object All : Scope()
    data class User(val name: String) : Scope()
    data class Channel(val id: Long) : Scope()
    data class Guild(val id: Long) : Scope()
}

class Rule(val scope: Scope, val pattern: Pattern)

// Java regex is used on purpose: Discord's bundled copy of Kotlin's Regex clashes at compile time.
private val scopedLine: Pattern = Pattern.compile("^(@[^:\\s]+|#\\d+|\\d{15,}):(.+)$")
private val regexLiteral: Pattern = Pattern.compile("^/(.+)/([gimsu]*)$")

fun parseRule(line: String): Rule? {
    val text = line.trim()
    if (text.isEmpty()) return null

    var scope: Scope = Scope.All
    var body = text
    val sm = scopedLine.matcher(text)
    if (sm.matches()) {
        val prefix = sm.group(1) ?: return null
        scope = when {
            prefix.startsWith("@") -> Scope.User(prefix.substring(1).lowercase())
            prefix.startsWith("#") -> Scope.Channel(prefix.substring(1).toLongOrNull() ?: return null)
            else -> Scope.Guild(prefix.toLongOrNull() ?: return null)
        }
        body = sm.group(2) ?: return null
    }

    val lm = regexLiteral.matcher(body)
    val pattern: Pattern = if (lm.matches()) {
        val expr = lm.group(1) ?: return null
        val flags = lm.group(2) ?: ""
        var f = 0
        if (flags.indexOf('i') >= 0) f = f or Pattern.CASE_INSENSITIVE
        if (flags.indexOf('m') >= 0) f = f or Pattern.MULTILINE
        if (flags.indexOf('s') >= 0) f = f or Pattern.DOTALL
        try {
            Pattern.compile(expr, f)
        } catch (e: Exception) {
            return null
        }
    } else {
        Pattern.compile("(?<!\\w)" + Pattern.quote(body) + "(?!\\w)", Pattern.CASE_INSENSITIVE)
    }
    return Rule(scope, pattern)
}

fun parseRules(raw: String): List<Rule> = raw.lineSequence().mapNotNull { parseRule(it) }.toList()

fun parseLines(raw: String): Set<String> =
    raw.lineSequence().map { it.trim().lowercase() }.filter { it.isNotEmpty() }.toSet()

class IncomingMessage(
    val content: String,
    val authorId: Long,
    val authorNames: Set<String>,
    val channelId: Long,
    val guildId: Long?,
)

class Config(val rules: List<Rule>, val vips: Set<String>, val ignoredGuilds: Set<Long>)

fun evaluate(cfg: Config, msg: IncomingMessage): String? {
    if (msg.guildId != null && msg.guildId in cfg.ignoredGuilds) return null
    if (cfg.vips.isNotEmpty() &&
        (msg.authorId.toString() in cfg.vips || msg.authorNames.any { it in cfg.vips })
    ) {
        return "VIP user"
    }
    for (r in cfg.rules) {
        val inScope = when (val s = r.scope) {
            is Scope.All -> true
            is Scope.User -> s.name in msg.authorNames
            is Scope.Channel -> s.id == msg.channelId
            is Scope.Guild -> s.id == msg.guildId
        }
        if (inScope) {
            val m = r.pattern.matcher(msg.content)
            if (m.find()) return "matched \"" + m.group() + "\""
        }
    }
    return null
}

class KeywordPingSettings(private val settings: SettingsAPI) : BottomSheet() {
    override fun onViewCreated(view: View, bundle: Bundle?) {
        super.onViewCreated(view, bundle)
        val ctx = requireContext()
        addView(TextView(ctx).apply { text = "KeywordPing Settings"; textSize = 20f })
        addBox("Keywords (one per line)", KEY_KEYWORDS)
        addBox("VIP users (name or user id)", KEY_VIPS)
        addBox("Ignored server IDs", KEY_IGNORED)
        addSwitch("Real mention (badge + Discord notification)", KEY_NATIVE, true)
        addSwitch("Also show in-app popup banner", KEY_POPUP, false)
    }

    private fun addSwitch(label: String, key: String, default: Boolean) {
        val sw = Utils.createCheckedSetting(requireContext(), CheckedSetting.ViewType.SWITCH, label, null)
        sw.isChecked = settings.getBool(key, default)
        sw.setOnCheckedListener {
            settings.setBool(key, !settings.getBool(key, default))
        }
        addView(sw)
    }

    private fun addBox(hint: String, key: String) {
        val input = TextInput(requireContext(), hint)
        input.editText.apply {
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE
            isSingleLine = false
            minLines = 3
            setText(settings.getString(key, ""))
            addTextChangedListener(object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
                override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
                override fun afterTextChanged(s: Editable?) {
                    settings.setString(key, s?.toString() ?: "")
                }
            })
        }
        addView(input)
    }
}

@AliucordPlugin(requiresRestart = false)
@Suppress("unused")
class KeywordPing : Plugin() {
    override fun start(context: Context) {
        settingsTab = SettingsTab(KeywordPingSettings::class.java, SettingsTab.Type.BOTTOM_SHEET)
            .withArgs(settings)

        // "before" so we can modify the message before Discord's stores process it
        patcher.before<StoreStream>(
            "handleMessageCreate",
            ApiMessage::class.java,
        ) { param ->
            val apiMsg = param.args[0] as? ApiMessage ?: return@before
            onMessage(apiMsg)
        }
    }

    override fun stop(context: Context) {
        patcher.unpatchAll()
    }

    fun onMessage(apiMsg: ApiMessage) {
        try {
            val msg = ModelMessage(apiMsg)
            val content = msg.content ?: return
            val author = CoreUser(msg.author)

            // Don't ping on your own messages
            if (author.id == StoreStream.getUsers().me.id) return

            val channelId = msg.channelId
            val channel = StoreStream.getChannels().getChannel(channelId)
            val guildId = channel?.guildId?.takeIf { it != 0L }

            val authorName = author.globalName ?: author.username ?: "Someone"
            val names = setOfNotNull(
                author.username?.lowercase(),
                author.globalName?.lowercase(),
            )
            val incoming = IncomingMessage(content, author.id, names, channelId, guildId)

            val cfg = Config(
                rules = parseRules(settings.getString(KEY_KEYWORDS, "")),
                vips = parseLines(settings.getString(KEY_VIPS, "")),
                ignoredGuilds = parseLines(settings.getString(KEY_IGNORED, ""))
                    .mapNotNull { it.toLongOrNull() }.toSet(),
            )
            val reason = evaluate(cfg, incoming) ?: return

            val nativeOk = settings.getBool(KEY_NATIVE, true) && injectMention(apiMsg)

            // Banner only if you turned it on, or as a backup when the real mention failed
            if (settings.getBool(KEY_POPUP, false) || !nativeOk) {
                val channelName = channel?.name?.takeIf { it.isNotEmpty() }
                showPopup(authorName, channelName, content, channelId, reason)
            }
        } catch (t: Throwable) {
            logger.warn("KeywordPing: error handling message: ${t.message}")
        }
    }

    // Adds you to the message's mention list so Discord treats it as a real @mention.
    private fun injectMention(apiMsg: ApiMessage): Boolean {
        return try {
            val me = StoreStream.getUsers().me
            val field = ApiMessage::class.java.getDeclaredField("mentions")
            field.isAccessible = true
            val old = (field.get(apiMsg) as? List<*>) ?: emptyList<Any?>()
            val already = old.any { it is ApiUser && CoreUser(it).id == me.id }
            if (!already) {
                val updated = ArrayList<Any?>(old)
                updated.add(UserUtils.INSTANCE.synthesizeApiUser(me))
                field.set(apiMsg, updated)
            }
            true
        } catch (t: Throwable) {
            logger.warn("KeywordPing: could not add real mention: ${t.message}")
            false
        }
    }

    private fun showPopup(author: String, channelName: String?, content: String, channelId: Long, reason: String) {
        Utils.mainThread.post {
            try {
                val data = NotificationData()
                    .setTitle(if (channelName != null) "$author in #$channelName" else author)
                    .setSubtitle("KeywordPing: $reason")
                    .setBody(content)
                NotificationsAPI.display(data, channelId)
            } catch (t: Throwable) {
                logger.warn("KeywordPing: could not show popup: ${t.message}")
            }
        }
    }
}
