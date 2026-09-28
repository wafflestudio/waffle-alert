package com.wafflestudio.alert.inbound.discord

import com.wafflestudio.alert.domain.mute.MuteRule
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/** 채널에 남기는 mute 공지. 누가 했는지는 적지 않고 무엇이 바뀌었는지만 짧게 쓴다. */
object MuteMessages {
    private val UNTIL_FORMAT = DateTimeFormatter.ofPattern("MM-dd HH:mm 'KST'").withZone(ZoneId.of("Asia/Seoul"))
    private val MARKDOWN_SPECIAL_CHARS = Regex("""[\\*_~`|]""")

    fun muted(rule: MuteRule): String = "Muted ${target(rule)} until ${UNTIL_FORMAT.format(rule.expiresAt)}"

    fun unmuted(rule: MuteRule): String = "Unmuted ${target(rule)}"

    fun expired(rule: MuteRule): String = "Mute expired: ${target(rule)}"

    /** /unmute 자동완성 목록에 보이는 이름 (Discord 상한 100자). */
    fun choiceLabel(rule: MuteRule): String {
        val until = " until ${UNTIL_FORMAT.format(rule.expiresAt)}"
        val target = if (rule.isChannelWide) "all alerts" else "\"${rule.keyword.take(100 - until.length - 2)}\""
        return target + until
    }

    private fun target(rule: MuteRule): String =
        if (rule.isChannelWide) "all alerts" else "\"${rule.keyword.replace(MARKDOWN_SPECIAL_CHARS) { "\\${it.value}" }}\""
}
