package com.dansplugins.factionsystem.command.faction.version

import com.dansplugins.factionsystem.MedievalFactions
import org.bukkit.ChatColor.DARK_GRAY
import org.bukkit.ChatColor.GREEN
import org.bukkit.ChatColor.RED
import org.bukkit.ChatColor.STRIKETHROUGH
import org.bukkit.ChatColor.WHITE
import org.bukkit.ChatColor.YELLOW
import org.bukkit.command.Command
import org.bukkit.command.CommandExecutor
import org.bukkit.command.CommandSender
import org.bukkit.command.TabCompleter

/**
 * Says which build of Medieval-Factions this server is running.
 *
 * ## Why a fork needs this
 *
 * Bukkit's own `/version MedievalFactions` reads plugin.yml, and until now this fork reported
 * `5.9.0-SNAPSHOT` -- the same string an upstream 5.9.0 build reports. That is a real operational
 * hazard rather than an untidiness: Patriam's own plugins link against API that exists only in this
 * fork, so a stock jar dropped in by mistake presents identically and then fails at the moment
 * somebody claims a chunk, which is a long way from the mistake that caused it.
 *
 * The version string itself is now marked, so `/version` alone is enough to tell them apart. This
 * command exists for the other half: saying what the difference actually *is*, to somebody who is
 * looking at a server they did not set up.
 *
 * ## It reads the version, it does not restate it
 *
 * Everything shown is derived from [org.bukkit.plugin.PluginDescriptionFile], which comes from
 * plugin.yml, which comes from the Gradle version. A second hardcoded copy would be one more thing
 * to forget to change, and a version command that lies is worse than no version command.
 *
 * That is also why an *unmarked* build says so out loud rather than silently rendering an empty
 * upstream line. If this command is running at all the jar is the fork -- upstream has no such
 * command -- so a missing marker means somebody built it without one, and the operator should know
 * before they rely on the string.
 */
class MfFactionVersionCommand(private val plugin: MedievalFactions) : CommandExecutor, TabCompleter {

    override fun onCommand(sender: CommandSender, command: Command, label: String, args: Array<out String>): Boolean {
        if (!sender.hasPermission("mf.version")) {
            sender.sendMessage("$RED${plugin.language["CommandFactionVersionNoPermission"]}")
            return true
        }

        val version = plugin.description.version
        val marked = version.contains(FORK_MARKER)

        sender.sendMessage("$DARK_GRAY$STRIKETHROUGH$RULE")
        sender.sendMessage("$YELLOW${plugin.language["CommandFactionVersionTitle"]}")
        sender.sendMessage("$GREEN${plugin.language["CommandFactionVersionVersion", value(version)]}")
        sender.sendMessage("$GREEN${plugin.language["CommandFactionVersionConfig", value(plugin.configSchemaStatus())]}")
        if (marked) {
            sender.sendMessage("$GREEN${plugin.language["CommandFactionVersionUpstream", value(version.substringBefore(FORK_MARKER))]}")
            sender.sendMessage("$YELLOW${plugin.language["CommandFactionVersionForkNotice"]}")
        } else {
            // Red, because this one is a fault rather than information: the version string above
            // cannot be relied on to tell this jar from an upstream one.
            sender.sendMessage("$RED${plugin.language["CommandFactionVersionUnmarked"]}")
        }
        sender.sendMessage("$DARK_GRAY$STRIKETHROUGH$RULE")
        return true
    }

    override fun onTabComplete(sender: CommandSender, command: Command, label: String, args: Array<out String>) = emptyList<String>()

    /**
     * A version number, coloured apart from the sentence around it.
     *
     * The colour is reapplied on the way out rather than left hanging, so the language string keeps
     * its {0} wherever a translator wants it: a value in the middle of a sentence returns the line
     * to its own colour instead of painting the rest of it white.
     */
    private fun value(text: String) = "$WHITE$text$GREEN"

    companion object {
        /**
         * What makes this fork's version string distinguishable from upstream's.
         *
         * Kept here rather than in the build, because this is the only code that has to recognise
         * it. If the Gradle version stops containing it, [onCommand] says so instead of guessing.
         */
        private const val FORK_MARKER = "-patriam"

        private const val RULE = "                                                  "
    }
}
