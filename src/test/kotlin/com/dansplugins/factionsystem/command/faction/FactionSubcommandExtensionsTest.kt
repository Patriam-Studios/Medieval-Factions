package com.dansplugins.factionsystem.command.faction

import com.dansplugins.factionsystem.MedievalFactions
import com.dansplugins.factionsystem.api.FactionSubcommandExtension
import com.dansplugins.factionsystem.api.FactionSubcommandHelpLine
import org.bukkit.Server
import org.bukkit.command.CommandSender
import org.bukkit.plugin.Plugin
import org.bukkit.plugin.RegisteredServiceProvider
import org.bukkit.plugin.ServicePriority
import org.bukkit.plugin.ServicesManager
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockito.Mockito.mock
import org.mockito.Mockito.`when`

class FactionSubcommandExtensionsTest {

    private lateinit var servicesManager: ServicesManager
    private lateinit var extensions: FactionSubcommandExtensions
    private var registrations = emptyList<RegisteredServiceProvider<FactionSubcommandExtension>>()

    @BeforeEach
    fun setUp() {
        val plugin = mock(MedievalFactions::class.java)
        val server = mock(Server::class.java)
        servicesManager = mock(ServicesManager::class.java)
        `when`(plugin.server).thenReturn(server)
        `when`(server.servicesManager).thenReturn(servicesManager)
        `when`(servicesManager.getRegistrations(FactionSubcommandExtension::class.java))
            .thenAnswer { registrations }
        extensions = FactionSubcommandExtensions(plugin)
    }

    @Test
    fun routesExecutionAndCompletionCaseInsensitivelyWithoutTheRootVerb() {
        val extension = RecordingExtension(setOf("JustifyWar", "jw"))
        register(extension)
        val sender = mock(CommandSender::class.java)

        val result = extensions.execute(sender, "JUSTIFYWAR", listOf("murder", "Target"), emptySet())
        val completions = extensions.tabComplete(sender, "jw", listOf("mur"), emptySet())

        assertEquals(true, result)
        assertEquals(listOf("justifywar" to listOf("murder", "Target")), extension.executions)
        assertEquals(listOf("candidate"), completions)
        assertEquals(listOf("jw" to listOf("mur")), extension.completions)
    }

    @Test
    fun builtInAliasesWinAndAreNotAdvertisedAgain() {
        val extension = RecordingExtension(setOf("help", "causes"))
        register(extension)
        val sender = mock(CommandSender::class.java)

        val result = extensions.execute(sender, "HELP", emptyList(), setOf("help"))

        assertNull(result)
        assertTrue(extension.executions.isEmpty())
        assertEquals(listOf("causes"), extensions.aliases(setOf("help")))
    }

    @Test
    fun helpRowsAreFilteredByTheirOwnPermissions() {
        val extension = RecordingExtension(
            setOf("causes"),
            listOf(
                FactionSubcommandHelpLine("/f causes"),
                FactionSubcommandHelpLine("/f causes <faction>", "addon.inspect"),
                FactionSubcommandHelpLine("/f causes admin", "addon.admin")
            )
        )
        register(extension)
        val sender = mock(CommandSender::class.java)
        `when`(sender.hasPermission("addon.inspect")).thenReturn(true)
        `when`(sender.hasPermission("addon.admin")).thenReturn(false)

        val lines = extensions.helpLines(sender, emptySet())

        assertEquals(listOf("/f causes", "/f causes <faction>"), lines.map { it.text })
    }

    @Test
    fun higherServicePriorityWinsAnExtensionAliasCollision() {
        val low = RecordingExtension(setOf("causes"), executeResult = false)
        val high = RecordingExtension(setOf("causes"), executeResult = true)
        register(low, ServicePriority.Low, "LowPlugin")
        register(high, ServicePriority.High, "HighPlugin")

        val result = extensions.execute(mock(CommandSender::class.java), "causes", emptyList(), emptySet())

        assertEquals(true, result)
        assertTrue(low.executions.isEmpty())
        assertEquals(1, high.executions.size)
    }

    private fun register(
        extension: FactionSubcommandExtension,
        priority: ServicePriority = ServicePriority.Normal,
        pluginName: String = "ExtensionPlugin"
    ) {
        val owner = mock(Plugin::class.java)
        `when`(owner.name).thenReturn(pluginName)
        registrations = registrations + RegisteredServiceProvider(
            FactionSubcommandExtension::class.java,
            extension,
            priority,
            owner
        )
    }

    private class RecordingExtension(
        override val aliases: Set<String>,
        override val helpLines: List<FactionSubcommandHelpLine> = emptyList(),
        private val executeResult: Boolean = true
    ) : FactionSubcommandExtension {

        val executions = mutableListOf<Pair<String, List<String>>>()
        val completions = mutableListOf<Pair<String, List<String>>>()

        override fun execute(sender: CommandSender, alias: String, arguments: List<String>): Boolean {
            executions += alias to arguments
            return executeResult
        }

        override fun tabComplete(sender: CommandSender, alias: String, arguments: List<String>): List<String> {
            completions += alias to arguments
            return listOf("candidate")
        }
    }
}
