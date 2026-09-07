package com.dansplugins.factionsystem.command.faction.apply.tasks

import com.dansplugins.factionsystem.MedievalFactions
import com.dansplugins.factionsystem.faction.MfFaction
import com.dansplugins.factionsystem.faction.MfFactionApplication
import com.dansplugins.factionsystem.faction.MfFactionService
import com.dansplugins.factionsystem.faction.flag.MfFlagValues
import com.dansplugins.factionsystem.faction.role.MfFactionRoles
import com.dansplugins.factionsystem.failure.ServiceFailure
import com.dansplugins.factionsystem.failure.ServiceFailureType
import com.dansplugins.factionsystem.lang.Language
import com.dansplugins.factionsystem.player.MfPlayer
import com.dansplugins.factionsystem.player.MfPlayerId
import com.dansplugins.factionsystem.player.MfPlayerService
import com.dansplugins.factionsystem.service.Services
import dev.forkhandles.result4k.Failure
import org.bukkit.configuration.file.YamlConfiguration
import org.bukkit.entity.Player
import org.junit.jupiter.api.Test
import org.mockito.Mockito.*
import java.util.UUID
import java.util.logging.Logger

class SendApplicationTaskTest {
    @Test
    fun failedPlayerSaveStopsWithoutThrowingOrClaimingSuccess() {
        val fixture = fixture()
        `when`(fixture.players.save(MfPlayer(fixture.plugin, fixture.sender))).thenReturn(failure())
        SendApplicationTask(fixture.plugin, fixture.sender, "target").run()
        verify(fixture.factions, never()).getFaction("target")
        verify(fixture.sender).sendMessage("\u00a7cplayer-failed")
        verify(fixture.sender, never()).sendMessage("\u00a7asuccess")
    }

    @Test
    fun failedFactionSaveStopsWithoutThrowingOrClaimingSuccess() {
        val fixture = fixture()
        val player = MfPlayer(MfPlayerId(fixture.sender.uniqueId.toString()))
        val target = MfFaction(
            fixture.plugin,
            name = "target",
            flags = MfFlagValues(fixture.plugin),
            roles = mock(MfFactionRoles::class.java),
            defaultPermissionsByName = emptyMap()
        )
        `when`(fixture.players.getPlayer(fixture.sender)).thenReturn(player)
        `when`(fixture.factions.getFaction("target")).thenReturn(target)
        `when`(fixture.factions.save(target.copy(applications = listOf(MfFactionApplication(target.id, player.id))))).thenReturn(failure())
        SendApplicationTask(fixture.plugin, fixture.sender, "target").run()
        verify(fixture.sender).sendMessage("\u00a7cfaction-failed")
        verify(fixture.sender, never()).sendMessage("\u00a7asuccess")
    }

    private fun failure() = Failure(ServiceFailure(ServiceFailureType.GENERAL, "database unavailable", IllegalStateException("offline")))

    private data class Fixture(
        val plugin: MedievalFactions,
        val sender: Player,
        val players: MfPlayerService,
        val factions: MfFactionService
    )

    private fun fixture(): Fixture {
        val plugin = mock(MedievalFactions::class.java)
        val sender = mock(Player::class.java)
        val services = mock(Services::class.java)
        val players = mock(MfPlayerService::class.java)
        val factions = mock(MfFactionService::class.java)
        val language = mock(Language::class.java)
        `when`(sender.uniqueId).thenReturn(UUID.randomUUID())
        `when`(plugin.services).thenReturn(services)
        `when`(plugin.config).thenReturn(YamlConfiguration())
        `when`(plugin.logger).thenReturn(mock(Logger::class.java))
        `when`(plugin.language).thenReturn(language)
        `when`(services.playerService).thenReturn(players)
        `when`(services.factionService).thenReturn(factions)
        `when`(language["CommandFactionApplyFailedToSavePlayer"]).thenReturn("player-failed")
        `when`(language["CommandFactionApplyFailedToSaveFaction"]).thenReturn("faction-failed")
        `when`(language["CommandFactionApplySuccess"]).thenReturn("success")
        return Fixture(plugin, sender, players, factions)
    }
}
