package xyz.bluspring.valhollclan.client

import com.google.gson.JsonParser
import com.mojang.authlib.GameProfile
import com.mojang.serialization.JsonOps
import net.fabricmc.api.ClientModInitializer
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback
import net.fabricmc.fabric.api.client.command.v2.ClientCommands
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientLevelEvents
import net.fabricmc.loader.api.FabricLoader
import net.minecraft.ChatFormatting
import net.minecraft.network.chat.Component
import net.minecraft.network.chat.ComponentUtils
import net.minecraft.network.chat.FontDescription
import net.minecraft.resources.Identifier
import net.minecraft.world.entity.player.Player
import org.slf4j.LoggerFactory
import xyz.bluspring.valhollclan.client.clan.ClanPlayerInfo
import xyz.bluspring.valhollclan.client.clan.ClanRole
import java.net.URI
import java.util.*

class ValhollClanClient : ClientModInitializer {
    override fun onInitializeClient() {
        loadInfos()

        ClientLevelEvents.AFTER_CLIENT_LEVEL_CHANGE.register { client, world ->
            if (world == null)
                return@register

            val clanPlayers = world.players().filter { players.any { p -> p.uuid == it.uuid } }

            if (clanPlayers.isNotEmpty()) {
                client.gui.chat.addClientSystemMessage(getAvailable(clanPlayers))
            }
        }

        ClientCommandRegistrationCallback.EVENT.register { dispatcher, registryAccess ->
            dispatcher.register(
                ClientCommands.literal("valholl")
                    .then(
                        ClientCommands.literal("roles")
                            .executes { ctx ->
                                for (role in ClanRole.entries) {
                                    ctx.source.player.sendSystemMessage(Component.empty()
                                        .append(
                                            Component.literal("${role.iconChar}")
                                                .withStyle {
                                                    it.withFont(FontDescription.Resource(ROLES_FONT))
                                                }
                                        )
                                        .append(" - ")
                                        .append(Component.translatable("valholl.roles.${role.serializedName}")
                                    ))
                                }

                                1
                            }
                    )
                    .then(
                        ClientCommands.literal("forcerefresh")
                            .executes {
                                loadInfos()
                                1
                            }
                    )
                    .then(
                        ClientCommands.literal("list")
                            .executes {
                                val clanPlayers = it.source.level.players().filter { players.any { p -> p.uuid == it.uuid } }
                                it.source.player.sendSystemMessage(getAvailable(clanPlayers))
                                1
                            }
                    )
            )
        }
    }

    private fun getAvailable(clanPlayers: List<Player>): Component {
        return Component.literal("Available Clan Players:")
            .append("\n")
            .append(Component.literal(" - Members: ").withStyle(ChatFormatting.BOLD, ChatFormatting.AQUA))
            .append(ComponentUtils.formatList(
                clanPlayers.filter { !it.getClanInfo()!!.role.isTarget }
                    .map { (it.displayName ?: it.name).copy().append(getClanIcon(it.gameProfile)) },
                Component.literal(", ")
            ))
            .append("\n")
            .append(Component.literal(" - Targets: ").withStyle(ChatFormatting.BOLD, ChatFormatting.RED))
            .append(ComponentUtils.formatList(
                clanPlayers.filter { it.getClanInfo()!!.role.isTarget }
                    .map { (it.displayName ?: it.name).copy().append(getClanIcon(it.gameProfile)) },
                Component.literal(", ")
            ))
    }

    companion object {
        private val logger = LoggerFactory.getLogger("Valhöll Clan")
        const val WEB_PATH = "https://raw.githubusercontent.com/BluSpring/ValhollClan/refs/heads/master/src/main/resources"
        val ROLES_FONT = Identifier.fromNamespaceAndPath("valholl_clan", "roles")

        val players = mutableListOf<ClanPlayerInfo>()

        private fun loadWithFallback(path: String): String {
            if (!FabricLoader.getInstance().isDevelopmentEnvironment) {
                try {
                    val url = URI.create("$WEB_PATH/$path").toURL()
                    return url.readText(Charsets.UTF_8)
                } catch (e: Throwable) {
                    logger.error("Failed to load URL for $path, using fallback!")
                    e.printStackTrace()
                }
            }

            return this::class.java.getResource("/$path")?.readText(Charsets.UTF_8) ?: ""
        }

        fun loadInfos() {
            players.clear()

            val allies = Properties()
            allies.load(loadWithFallback("clan_allies.properties").reader())

            for ((key, value) in allies) {
                players.add(ClanPlayerInfo(
                    UUID.fromString(value as String),
                    key as String,
                    ClanRole.ALLY
                ))
            }

            val members = ClanPlayerInfo.CODEC.listOf().decode(JsonOps.INSTANCE, JsonParser.parseString(loadWithFallback("clan_members.json"))).orThrow.first
            val targets = ClanPlayerInfo.CODEC.listOf().decode(JsonOps.INSTANCE, JsonParser.parseString(loadWithFallback("clan_targets.json"))).orThrow.first

            if (FabricLoader.getInstance().isDevelopmentEnvironment) {
                for (info in members.distinctBy { it.uuid }) {
                    val duplicates = members.filter { it.uuid == info.uuid }
                    if (duplicates.size > 1) {
                        logger.error("Multiple of ${info.uuid} exists!")
                        for (duplicate in duplicates) {
                            logger.error(" - ${duplicate.name} (${duplicate.role})")
                        }
                    }
                }
            }

            players.addAll(members)
            players.addAll(targets)
        }

        fun Player.getClanInfo(): ClanPlayerInfo? {
            return players.firstOrNull { it.uuid == this.uuid }
        }

        @JvmStatic
        fun getClanIcon(profile: GameProfile): Component {
            if (players.any { it.uuid == profile.id }) {
                val player = players.first { it.uuid == profile.id }
                return Component.literal(" ${player.role.iconChar}")
                    .withStyle {
                        it.withFont(FontDescription.Resource(ROLES_FONT))
                    }
            }

            return Component.empty()
        }
    }
}
