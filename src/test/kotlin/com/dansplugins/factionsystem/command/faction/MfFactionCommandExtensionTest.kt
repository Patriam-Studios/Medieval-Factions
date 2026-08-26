package com.dansplugins.factionsystem.command.faction

import com.dansplugins.factionsystem.MedievalFactions
import com.dansplugins.factionsystem.api.FactionSubcommandExtension
import com.dansplugins.factionsystem.api.FactionSubcommandHelpLine
import com.dansplugins.factionsystem.lang.Language
import net.md_5.bungee.api.chat.BaseComponent
import org.bukkit.Server
import org.bukkit.command.Command
import org.bukkit.command.CommandSender
import org.bukkit.configuration.file.FileConfiguration
import org.bukkit.plugin.Plugin
import org.bukkit.plugin.RegisteredServiceProvider
import org.bukkit.plugin.ServicePriority
import org.bukkit.plugin.ServicesManager
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockito.Answers
import org.mockito.Mockito.mock
import org.mockito.Mockito.`when`
import org.mockito.stubbing.Answer
import java.util.Locale

/** Proves the root command actually hands only otherwise-unknown verbs to the service seam. */
class MfFactionCommandExtensionTest {

    private lateinit var extension: RecordingExtension
    private lateinit var command: MfFactionCommand
    private lateinit var sender: CommandSender
    private lateinit var bukkitCommand: Command

    @BeforeEach
    fun setUp() {
        val plugin = mock(MedievalFactions::class.java)
        val server = mock(Server::class.java)
        val servicesManager = mock(ServicesManager::class.java)
        val config = mock(FileConfiguration::class.java)
        val language = mock(
            Language::class.java,
            Answer { invocation ->
                if (invocation.method.returnType == String::class.java) {
                    invocation.arguments.firstOrNull()?.toString().orEmpty()
                } else {
                    Answers.RETURNS_DEFAULTS.answer(invocation)
                }
            }
        )
        `when`(language.locale).thenReturn(Locale.US)
        `when`(plugin.server).thenReturn(server)
        `when`(server.servicesManager).thenReturn(servicesManager)
        `when`(plugin.config).thenReturn(config)
        `when`(plugin.language).thenReturn(language)

        extension = RecordingExtension()
        val owner = mock(Plugin::class.java)
        `when`(owner.name).thenReturn("CasusPlugin")
        val registration = RegisteredServiceProvider(
            FactionSubcommandExtension::class.java,
            extension,
            ServicePriority.Normal,
            owner
        )
        `when`(servicesManager.getRegistrations(FactionSubcommandExtension::class.java))
            .thenReturn(listOf(registration))

        sender = mock(CommandSender::class.java)
        bukkitCommand = mock(Command::class.java)
        command = MfFactionCommand(plugin)
    }

    @Test
    fun otherwiseUnknownVerbRoutesAndCompletesThroughTheExtension() {
        val executed = command.onCommand(
            sender,
            bukkitCommand,
            "f",
            arrayOf("JUSTIFYWAR", "murder", "Target")
        )
        val topLevel = command.onTabComplete(sender, bukkitCommand, "f", arrayOf("just")).orEmpty()
        val nested = command.onTabComplete(sender, bukkitCommand, "f", arrayOf("justifywar", "mur")).orEmpty()

        assertTrue(executed)
        assertEquals(listOf("justifywar" to listOf("murder", "Target")), extension.executions)
        assertTrue("justifywar" in topLevel)
        assertEquals(listOf("murder"), nested)
        assertEquals(listOf("justifywar" to listOf("mur")), extension.completions)
    }

    @Test
    fun builtInVerbWinsAConflictBeforeTheExtensionIsConsulted() {
        `when`(sender.hasPermission("mf.help")).thenReturn(false)

        val handled = command.onCommand(sender, bukkitCommand, "f", arrayOf("help"))

        assertTrue(handled)
        assertFalse(extension.executions.any { it.first == "help" })
        assertEquals(
            1,
            command.onTabComplete(sender, bukkitCommand, "f", arrayOf("help")).orEmpty().count { it == "help" }
        )
    }

    @Test
    fun helpIncludesOnlyExtensionRowsTheSenderMaySee() {
        `when`(sender.hasPermission("mf.help")).thenReturn(true)
        `when`(sender.hasPermission("casus.justify")).thenReturn(true)
        `when`(sender.hasPermission("casus.admin")).thenReturn(false)
        val messages = mutableListOf<String>()
        `when`(sender.spigot()).thenReturn(
            object : CommandSender.Spigot() {
                override fun sendMessage(vararg components: BaseComponent) {
                    messages += components.joinToString(separator = "") { it.toPlainText() }
                }

                override fun sendMessage(component: BaseComponent) {
                    messages += component.toPlainText()
                }
            }
        )

        val handled = command.onCommand(sender, bukkitCommand, "f", arrayOf("help", "9"))

        assertTrue(handled)
        assertTrue(
            messages.any { "/f justifywar <cause> <target>" in it },
            "extension help row missing from rendered page: $messages"
        )
        assertFalse(messages.any { "/f causes admin" in it })
    }

    private class RecordingExtension : FactionSubcommandExtension {
        override val aliases = setOf("justifywar", "help")
        override val helpLines = listOf(
            FactionSubcommandHelpLine("/f justifywar <cause> <target>", "casus.justify"),
            FactionSubcommandHelpLine("/f causes admin", "casus.admin")
        )
        val executions = mutableListOf<Pair<String, List<String>>>()
        val completions = mutableListOf<Pair<String, List<String>>>()

        override fun execute(sender: CommandSender, alias: String, arguments: List<String>): Boolean {
            executions += alias to arguments
            return true
        }

        override fun tabComplete(sender: CommandSender, alias: String, arguments: List<String>): List<String> {
            completions += alias to arguments
            return listOf("murder")
        }
    }
}
