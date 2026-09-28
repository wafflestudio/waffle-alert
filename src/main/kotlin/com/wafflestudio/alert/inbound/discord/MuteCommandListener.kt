package com.wafflestudio.alert.inbound.discord

import com.wafflestudio.alert.config.DiscordProperties
import net.dv8tion.jda.api.events.interaction.command.CommandAutoCompleteInteractionEvent
import net.dv8tion.jda.api.events.interaction.command.SlashCommandInteractionEvent
import net.dv8tion.jda.api.events.session.ReadyEvent
import net.dv8tion.jda.api.hooks.ListenerAdapter
import net.dv8tion.jda.api.interactions.commands.Command
import net.dv8tion.jda.api.interactions.commands.OptionType
import net.dv8tion.jda.api.interactions.commands.build.Commands
import net.dv8tion.jda.api.interactions.commands.build.OptionData
import net.dv8tion.jda.api.interactions.commands.build.SlashCommandData
import org.slf4j.LoggerFactory

/** JDA 이벤트를 [MuteCommandHandler]로 넘기는 얇은 층. */
class MuteCommandListener(
    private val handler: MuteCommandHandler,
    private val discordProperties: DiscordProperties,
) : ListenerAdapter() {
    private val log = LoggerFactory.getLogger(javaClass)

    /**
     * 명령어를 guild-id 서버(비어 있으면 global)에 등록하고, 반대쪽 범위에 남아 있을 수 있는 이전 등록은
     * 지운다. guild-id를 나중에 채우거나 비우면 같은 명령어가 두 번 보이는 것을 막는다.
     */
    override fun onReady(event: ReadyEvent) {
        val jda = event.jda
        val guildId = discordProperties.guildId
        if (guildId.isBlank()) {
            jda.updateCommands().addCommands(COMMANDS).queue(
                { log.info("Registered /mute commands globally") },
                { log.error("Failed to register /mute commands globally", it) },
            )
            jda.guilds.forEach { it.updateCommands().queue() }
            return
        }
        val guild =
            jda.getGuildById(guildId) ?: run {
                log.warn("Bot is not in guild {}, skip registering /mute commands", guildId)
                return
            }
        guild.updateCommands().addCommands(COMMANDS).queue(
            { log.info("Registered /mute commands (guildId={})", guildId) },
            { log.error("Failed to register /mute commands (guildId={})", guildId, it) },
        )
        jda.updateCommands().queue()
    }

    override fun onSlashCommandInteraction(event: SlashCommandInteractionEvent) {
        if (event.name != MUTE && event.name != UNMUTE) return
        val channelId = event.channelId ?: return
        if (!handler.isAlertChannel(channelId)) {
            event.reply("This command only works in alert channels.").setEphemeral(true).queue()
            return
        }

        // 응답(defer)을 먼저 해서 성공한 쪽만 처리한다. 배포 중 파드가 2개면 같은 명령어가 두 번 오는데,
        // Discord는 interaction 하나에 한 번만 응답을 받는다.
        event.deferReply(true).queue(
            { hook ->
                val reply =
                    runCatching { handle(event, channelId) }.getOrElse {
                        log.error("Failed to handle /{} (channelId={})", event.name, channelId, it)
                        "Failed. Please try again."
                    }
                hook.editOriginal(reply).queue()
            },
            { log.info("Could not acknowledge /{}, skip (channelId={}): {}", event.name, channelId, it.message) },
        )
    }

    /** @return 입력한 사람에게만 보이는 응답 문구. */
    private fun handle(
        event: SlashCommandInteractionEvent,
        channelId: String,
    ): String {
        val userId = event.user.id
        if (event.name == UNMUTE) {
            val unmuted = handler.unmute(channelId, userId, event.getOption(TARGET)?.asString)
            return if (unmuted) "Done." else "No active mute found."
        }
        val duration = MuteDuration.fromLabel(event.getOption(DURATION)?.asString) ?: return "Unknown duration."
        handler.mute(channelId, userId, duration, event.getOption(KEYWORD)?.asString)
        return "Done."
    }

    override fun onCommandAutoCompleteInteraction(event: CommandAutoCompleteInteractionEvent) {
        if (event.name != UNMUTE || event.focusedOption.name != TARGET) return
        val channelId = event.channelId ?: return
        val choices =
            runCatching { handler.unmuteChoices(channelId, event.focusedOption.value) }
                .getOrElse {
                    log.warn("Failed to load /unmute choices (channelId={})", channelId, it)
                    emptyList()
                }.map { (name, value) -> Command.Choice(name, value) }
        event.replyChoices(choices).queue()
    }

    companion object {
        const val MUTE = "mute"
        const val UNMUTE = "unmute"
        const val DURATION = "duration"
        const val KEYWORD = "keyword"
        const val TARGET = "target"

        val COMMANDS: List<SlashCommandData> =
            listOf(
                Commands
                    .slash(MUTE, "Mute alerts in this channel")
                    .addOptions(
                        OptionData(OptionType.STRING, DURATION, "How long to mute", true)
                            .addChoices(MuteDuration.entries.map { Command.Choice(it.label, it.label) }),
                        OptionData(OptionType.STRING, KEYWORD, "Mute only alerts whose title contains this. Empty = all alerts", false)
                            .setMaxLength(100),
                    ),
                Commands
                    .slash(UNMUTE, "Unmute alerts in this channel")
                    .addOptions(OptionData(OptionType.STRING, TARGET, "Mute to lift", true, true)),
            )
    }
}
