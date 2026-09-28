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

    override fun onReady(event: ReadyEvent) {
        val guildId = discordProperties.guildId
        val update =
            if (guildId.isBlank()) {
                event.jda.updateCommands()
            } else {
                event.jda.getGuildById(guildId)?.updateCommands() ?: run {
                    log.warn("Bot is not in guild {}, skip registering /mute commands", guildId)
                    return
                }
            }
        update.addCommands(COMMANDS).queue(
            { log.info("Registered /mute commands (guildId={})", guildId.ifBlank { "global" }) },
            { log.error("Failed to register /mute commands (guildId={})", guildId, it) },
        )
    }

    override fun onSlashCommandInteraction(event: SlashCommandInteractionEvent) {
        if (event.name != MUTE && event.name != UNMUTE) return
        val channelId = event.channelId ?: return
        if (!handler.isAlertChannel(channelId)) {
            event.reply("This command only works in alert channels.").setEphemeral(true).queue()
            return
        }
        val userId = event.user.id

        // 응답(defer)을 먼저 해서 성공한 쪽만 처리한다. 배포 중 파드가 2개면 같은 명령어가 두 번 오는데,
        // Discord는 interaction 하나에 한 번만 응답을 받는다.
        event.deferReply(true).queue(
            { hook ->
                val reply =
                    runCatching {
                        when (event.name) {
                            MUTE -> {
                                val duration = MuteDuration.fromLabel(event.getOption(DURATION)?.asString)
                                if (duration == null) {
                                    "Unknown duration."
                                } else {
                                    handler.mute(channelId, userId, duration, event.getOption(KEYWORD)?.asString)
                                    "Done."
                                }
                            }
                            else ->
                                if (handler.unmute(
                                        channelId,
                                        userId,
                                        event.getOption(TARGET)?.asString,
                                    )
                                ) {
                                    "Done."
                                } else {
                                    "No active mute found."
                                }
                        }
                    }.getOrElse {
                        log.error("Failed to handle /{} (channelId={})", event.name, channelId, it)
                        "Failed. Please try again."
                    }
                hook.editOriginal(reply).queue()
            },
            { log.info("/{} was acknowledged elsewhere, skip (channelId={})", event.name, channelId) },
        )
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
