package com.wafflestudio.alert.outbound.notification.routing

/** Discord 서버에 등록된 멘션용 role. roleId로 `<@&roleId>` 멘션 문자열을 만든다. */
/**
 * TODO 각 팀별 이름 - roleId 추가해야함
 */
enum class DiscordMentionRole(
    val roleName: String,
    val roleId: String,
) {
    INFRA("Infra", "1499456697251397633"),
    ;

    val mention: String
        get() = "<@&$roleId>"
}
