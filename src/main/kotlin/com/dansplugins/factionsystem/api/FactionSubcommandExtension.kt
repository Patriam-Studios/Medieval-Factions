package com.dansplugins.factionsystem.api

import org.bukkit.command.CommandSender

/**
 * A plugin-owned subcommand routed below MedievalFactions' `/faction` command.
 *
 * Register implementations with Bukkit's `ServicesManager` under this interface. MedievalFactions
 * discovers registrations dynamically, so an extension registered after MF enables is available
 * immediately and disappears when its owning plugin's services are unregistered.
 *
 * [aliases] are bare first arguments such as `causes` or `justifywar`. Matching is
 * case-insensitive; blank aliases and aliases containing whitespace are ignored. MedievalFactions'
 * built-in aliases always win a collision. If two extensions claim the same remaining alias, the
 * registration with the higher Bukkit `ServicePriority` wins, with plugin name as the deterministic
 * tie-breaker.
 *
 * Execution and completion run inline on the command-dispatch thread, normally the server thread.
 * An extension owns its permission denial and user-facing error messages. A permission on a
 * [FactionSubcommandHelpLine] controls only whether that row is included in `/f help`; it is not an
 * authorization check for [execute].
 *
 * @since the Patriam fork
 */
interface FactionSubcommandExtension {

    val aliases: Set<String>

    val helpLines: List<FactionSubcommandHelpLine>

    /** Handle [alias], receiving only the arguments after the routed first argument. */
    fun execute(sender: CommandSender, alias: String, arguments: List<String>): Boolean

    /** Complete [alias], receiving only the arguments after the routed first argument. */
    fun tabComplete(sender: CommandSender, alias: String, arguments: List<String>): List<String>
}

/** One plain-text row contributed to `/f help`, optionally visible only with [permission]. */
data class FactionSubcommandHelpLine @JvmOverloads constructor(
    val text: String,
    val permission: String? = null
)
