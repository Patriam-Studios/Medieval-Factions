package com.dansplugins.factionsystem.command.faction

import com.dansplugins.factionsystem.MedievalFactions
import com.dansplugins.factionsystem.api.FactionSubcommandExtension
import com.dansplugins.factionsystem.api.FactionSubcommandHelpLine
import org.bukkit.command.CommandSender
import org.bukkit.plugin.RegisteredServiceProvider

/** Dynamic view of plugin-provided `/f` subcommands registered through Bukkit services. */
internal class FactionSubcommandExtensions(private val plugin: MedievalFactions) {

    fun aliases(reservedAliases: Set<String>): List<String> {
        val claimed = reservedAliases.toMutableSet()
        return buildList {
            registrations().forEach { registration ->
                validAliases(registration.provider).forEach { alias ->
                    if (claimed.add(alias)) add(alias)
                }
            }
        }
    }

    fun execute(
        sender: CommandSender,
        alias: String,
        arguments: List<String>,
        reservedAliases: Set<String>
    ): Boolean? = resolve(alias, reservedAliases)?.execute(sender, alias.lowercase(), arguments)

    fun tabComplete(
        sender: CommandSender,
        alias: String,
        arguments: List<String>,
        reservedAliases: Set<String>
    ): List<String>? = resolve(alias, reservedAliases)?.tabComplete(sender, alias.lowercase(), arguments)

    fun helpLines(sender: CommandSender, reservedAliases: Set<String>): List<FactionSubcommandHelpLine> =
        registrations()
            .filter { registration -> validAliases(registration.provider).any { it !in reservedAliases } }
            .flatMap { it.provider.helpLines }
            .filter { line -> line.permission.isNullOrBlank() || sender.hasPermission(line.permission!!) }

    private fun resolve(alias: String, reservedAliases: Set<String>): FactionSubcommandExtension? {
        val normalized = alias.lowercase()
        if (normalized in reservedAliases) return null
        return registrations()
            .firstOrNull { registration -> normalized in validAliases(registration.provider) }
            ?.provider
    }

    private fun registrations(): List<RegisteredServiceProvider<FactionSubcommandExtension>> =
        plugin.server.servicesManager
            .getRegistrations(FactionSubcommandExtension::class.java)
            .sortedWith(
                compareByDescending<RegisteredServiceProvider<FactionSubcommandExtension>> { it.priority.ordinal }
                    .thenBy { it.plugin.name.lowercase() }
            )

    private fun validAliases(extension: FactionSubcommandExtension): Set<String> =
        extension.aliases
            .asSequence()
            .map(String::trim)
            .filter { it.isNotEmpty() && it.none(Char::isWhitespace) }
            .map(String::lowercase)
            .toCollection(linkedSetOf())
}
