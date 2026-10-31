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
import com.aliucord.api.SettingsAPI
import com.aliucord.entities.Plugin
import com.aliucord.patcher.after
import com.aliucord.views.TextInput
import com.aliucord.widgets.BottomSheet
import com.aliucord.wrappers.ChannelWrapper.Companion.guildId
import com.aliucord.wrappers.users.globalName
import com.discord.models.user.CoreUser
import com.discord.stores.StoreStream
import java.util.regex.Pattern
import com.discord.api.message.Message as ApiMessage
import com.discord.models.message.Message as ModelMessage

private const val KEY_KEYWORDS = "keywords"
private const val KEY_VIPS = "vips"
private const val KEY_IGNORED = "ignoredGuilds"

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

        // Same hook Aliucord's own core plugins use for new messages
        patcher.after<StoreStream>(
            "handleMessageCreate",
            ApiMessage::class.java,
        ) { param ->
            val apiMsg = param.args[0] as? ApiMessage ?: return@after
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

            // Toast only. A real Discord mention (badge/push/Inbox) is not done yet.
            Utils.showToast("KeywordPing: $reason")
        } catch (t: Throwable) {
            logger.warn("KeywordPing: error handling message: ${t.message}")
        }
    }
}
