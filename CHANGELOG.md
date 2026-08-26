# Changelog

All notable changes to this project will be documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.0.0/).

## [Unreleased]

<!--
  PATRIAM FORK. Everything under this heading is this fork's, not upstream Dans-Plugins'. Kept in one
  block so an upstream merge has an obvious seam, and so a reader can tell at a glance which changes
  they will not find in a release from SpigotMC.
-->

### Patriam fork

#### Added
- **A stable consumer API** under `com.dansplugins.factionsystem.api`, so a dependent plugin never has
  to name an MF internal: `createFaction`, `disbandFaction`, `transferMembers`, `transferAllClaims`,
  `renounceLiege`, `swearFealty`, `declareWar`, a positional `claim(faction, worldId, x, z)`,
  `primaryOwnerSince`, and `ApiOutcome<T>` for the calls that return a value.
- **`FactionClaimAttemptEvent`** -- the first *cancellable* claim event on the stable surface.
  `ClaimOverrideProvider` is additive only and can never refuse anything, so a consumer with a rule
  that has to forbid a claim previously had to bind to MF's internal `FactionClaimEvent`. Fired inside
  `MfClaimService.save`, so it sits underneath every route into a claim including autoclaim, which
  involves no command at all. It fires *before* MF's own event, so MF's stays the last word and its
  MONITOR handlers keep seeing only claims that really happened.
- **A demesne curve** (`MfDemesne`): land that costs more power the more of it you hold. Off by
  default (`factions.demesneCurve.enabled: false`) and arithmetically identical to the flat rule at
  `increment: 0.0`. On the shipped figures 64 chunks cost 64 power, 128 cost 152, 256 cost 424 and
  512 cost 1,352. Enforced at all three claim gates and at autoclaim, and reported by `/f power` and
  the Dynmap readout, so a realm sees the figure that actually decides its next claim. Nothing is ever
  unclaimed: a faction over its allowance simply cannot take more.
- **`/f version`** (also `ver`, `about`), which says which build this is and that a stock jar will not
  run Patriam's plugins. The version string itself is marked `-patriam`, because a fork reporting the
  same string as upstream is how somebody spends an hour debugging the wrong jar.
- **Configuration schema 1**, with template-first adoption of unversioned MF5 files, strict physical
  YAML checks, exact owner-only backups and atomic replacement. Startup now validates and binds one
  exact config generation before opening the database; MF4 keeps its separate historical importer,
  and `/f dpc` writes refuse concurrent operator edits while retaining the last-known-good runtime.
- Migrations **V900** and **V901**, recording a faction's head and when they took the seat. Numbered
  from 900 so upstream keeps 9 through 899: two migrations sharing a version makes Flyway refuse to
  start, and renaming one after a server has applied it means editing `schema_history` on live data.
- **A `coatofarms` faction flag**, a string, empty by default, holding whatever code the plugin that
  owns heraldry issues. MF stores it and reads nothing from it. Deliberately not validated as an arms
  code, because MF does not know the codec and a validator here would refuse codes a later version of
  it issues legally; the only check is a 64-character ceiling, which is about MF's own storage and
  display. Read it as `%MedievalFactions_faction_flag_coatofarms%`, and note the lowercase name is what
  makes both that and `SET_FLAG(coatofarms)` work.
- **`getFlag` and `setFlag` on `MedievalFactionsApi`**, so a consumer that needs one of MF's
  per-faction settings no longer has to bind to `plugin.flags` and `plugin.services.factionService` and
  rebuild a faction through a seventeen-parameter `copy`. Flags are named rather than enumerated,
  because a flag name is already public and the set is not fixed at compile time. The write goes
  through the flag's own coercion and validation and still honours `factions.allowNeutrality`; it
  checks no faction permission, since it is a plugin acting rather than a player. A write that changes
  nothing is skipped, because MF has no per-field write and a reconciler would otherwise pay a whole
  faction save on every pass.
- **`FactionPermission.SET_COAT_OF_ARMS`**, so a ruler can delegate "may change this House's arms"
  with `/f role setpermission Officer SET_FLAG(coatofarms) allow` instead of a consumer gating arms on
  `DISBAND` or `CHANGE_PREFIX` and thereby tying two unrelated rights together forever. The founding
  Owner holds it and may hand it on, so it works from the day a faction is founded. It is the first
  parameterised permission on the enum: what keeps a role id off the API is that it cannot be named
  without being handed MF's model, and a flag name is already published everywhere.
- **`layDownArms` on `MedievalFactionsApi`**, the one-sided half of a peace, which the API was missing
  entirely. A war is two mirrored `AT_WAR` rows, one per faction, and `forcePeace` deletes both -- so a
  plugin that had watched one realm agree to stop could only end the war outright, over the other
  realm's head. This deletes only the named faction's rows, exactly as `/f makepeace` does for the
  faction that runs it, leaving the second half to be given rather than taken. It answers
  `ApiOutcome<PeaceOutcome>` rather than a boolean, because "our half is down and they are still at
  war with us" (`PEACE_REQUESTED`) and "that was the last row" (`PEACE_MADE`) are different
  announcements. `FactionWarEndedEvent` fires on the second, from the relationship delete and so before
  the row is gone, the same caveat `declareWar` carries; nothing at all fires on the first, because MF
  publishes no event for a peace request. Holding no rows while the other side holds some is reported
  as peace having already been requested, as the command reports it.

#### Fixed
- **The test tier did not compile from clean.** The anonymous `FactionView` in
  `FactionHierarchyViewTest` never implemented `color`, and Kotlin's incremental compiler did not
  revisit it, so `./gradlew test` reported green against a snapshot taken before that member existed.

#### Notes for operators
- A faction created **before** the `coatofarms` flag existed can never have `SET_FLAG(coatofarms)`
  granted to any of its roles, by anybody, operator included. Both grant lists involved are written
  once when the faction is created. Such a faction's arms can still be set by an operator holding
  `mf.force.flag`, and the flag's value reads normally; it is the delegation inside the faction that is
  lost. There is no migration, so a server that wants the arms delegable everywhere must recreate its
  factions.
- `factions.defaults.flags.coatofarms` may be added to an existing `config.yml`, but need not be:
  leaving the key out has the same effect as setting it empty. Add it only to give every new faction
  the same starting arms.
- **The deploy jar could be the wrong jar.** shadowJar's renamed output shared a path with the plain
  `jar` task's on case-insensitive filesystems, so a build could leave an unshaded jar under the
  documented deploy name -- it loads, then dies on `NoClassDefFoundError`. `jar` now carries a `-thin`
  classifier.
- **`/f admin setleader` could not seat a head on a faction that already had members**, which is every
  faction predating V900 and exactly the ones the migration tells operators to fix with it. It also
  promotes in place rather than appending, since two member rows for one player make `getRole` return
  null.
- **A `de_DE` server would not start.** With `lang_en_US` as the only bundle there is no ROOT to fall
  back to, and the failing `getBundle` call was not inside the try. The shipped bundle is now
  `lang.properties`; servers with a customised `lang_en_US.properties` keep it and now inherit missing
  keys from the base instead of rendering `Missing translation for`.
- `createFaction` no longer refuses a founder who is the sole member of their faction -- it dissolves
  the emptied one, as `/f leave` and `transferMembers` already did -- and its rollback now carries the
  optimistic-lock version forward, without which it could never succeed.
- `renounceLiege` deletes the oath rather than every relationship row between the two factions, which
  had included `AT_WAR`: declaring war and then renouncing ended the war instantly.
- The war-conquest gate in `/f claim circle` compares costs rather than a floored allowance, so the
  flat rule is byte-for-byte upstream's again.
- The release workflow and `reload-plugin.sh` follow the renamed artifact instead of globbing for
  `*-all.jar`, which has matched nothing since the rename.


### Added
- Configurable moderator approval for faction declarations. When enabled, `/faction declarewar`, `/faction ally`, and `/faction vassalize` create a pending request that a moderator (permission `mf.approve`, default `op`) must approve before it takes effect. Gated independently by the `factions.warDeclarationRequiresApproval`, `factions.allyDeclarationRequiresApproval`, and `factions.vassalizeDeclarationRequiresApproval` config options (all default `false`). New `/faction approve [id]`, `/faction deny [id]`, and `/faction pendingactions` commands manage requests, and a reason can be attached with `-- <reason>`.
- `/faction power` now also reports a faction's claim count. When `factions.limitLand` is enabled it is shown as `claimed/capacity` (capacity equals the faction's current power); otherwise just the number of claimed chunks is shown.
- `/faction declinevassalization [faction]` command (permission `mf.declinevassalization`, default `true`): lets a faction decline a pending vassalization request sent to it, with notifications to both factions.
- DPC community API integration: opt-in sync of faction data to `https://dansplugins.com` via `POST /api/v1/factions`. Enabled with `/mf dpc optin` and configured under the `dpc-api.*` section of `config.yml`. Requires an API key from the DPC website.
- `/mf dpc` subcommand (permission `mf.dpc`, default `op`) with `optin`, `optout`, `reminder on|off`, `shareip on|off`, `discord <link>|clear` actions.
- bStats charts for DPC opt-in rate, login-reminder usage, server-IP sharing, and Discord-link presence.

### Fixed
- Faction flag placeholders no longer fail for any flag whose name carries a capital. `params` arrives lowercased and the registered flag name did not, so neither `%MedievalFactions_faction_flag_alliesCanInteractWithLand%` nor its `vassalageTreeCanInteractWithLand` counterpart returned anything, in any spelling. The faction ally and enemy branches beside it already lowercased.
- Faction snapshot for the DPC sync is now collected on the Bukkit main thread before being dispatched off-thread via `HttpClient.sendAsync`. Off-thread access to `factionService.factions` could otherwise produce inconsistent reads or `ConcurrentModificationException` under load.
- The DPC sync no longer POSTs an empty faction roster. A transient empty read (e.g. faction data not yet loaded at startup, or a reload mid-cycle) is skipped client-side rather than sent, so it can never depend on the provider's safety guards to avoid a faction wipe.

## [5.8.1] – 2026-04-25

### Fixed
- Holding a `wartimePlaceableBlocks` item (e.g. ladders or scaffolding) no longer bypasses interaction protection on enemy territory blocks such as chests and levers during wartime.

## [5.8.0] – 2026-04-25

### Added
- `factions.wartimePlaceableBlocks`: configurable list of block types that attackers can place in enemy territory during war.
- `factions.wartimeBreakableBlocks`: configurable list of block types that attackers can break in enemy territory during war.
- `factions.wartimeInteractableBlocks`: configurable list of block types that attackers can interact with in enemy territory during war.

## [5.7.2] – 2026-01-03

### Fixed
- `nonMembersCanInteractWithDoors` configuration option not functioning as intended.
- Ladder bypass exploit allowing unintended access to protected areas.

## [5.7.1] – 2026-01-02

### Fixed
- Ladder placement incorrectly blocked in enemy territory during wartime.

## [5.7.0] – 2026-01-01

### Added
- Leaderless faction support with operator management commands.
- World-based claim blocking configuration options.
- Automated JAR publishing to GitHub Packages on release.
- Comprehensive in-repository documentation.

### Fixed
- Double-chest hopper bypass and other protection edge cases.
- NullPointerExceptions affecting plugin stability.
- Concurrent gate save handling.

### Changed
- Test server updated to Minecraft 1.21.11.
- Improved faction flag commands with force permissions and refactoring.
- Removed outdated territory item pickup/drop restrictions.

## [5.6.1] – 2025-12-09

### Fixed
- `NoSuchElementException` during plugin initialization when player data is unavailable.
- `/mf bypass` not allowing players to attack entities in claimed chunks.
- Infinite recursion in the faction claim fill command (added recursion depth cap).
- Lock command not persisting across multiple blocks like the unlock command.
- Slimefun compatibility: added comprehensive event listeners to prevent bypassing faction protection.
- Entity protection in faction territories not respecting relationships.
- Dynmap integration causing lag on server and web interface.

## [5.6.0] – 2025-03-30

### Added
- Configurable gate block restrictions with an expanded default list.
- Config options to restrict block actions in unclaimed wilderness chunks.

### Fixed
- Ally placeholder issue.
- Gate blocks being destroyed by fire.

### Changed
- Improved GitHub issue templates for clarity and consistency.
- Enabled PlaceholderAPI testing.

## [5.5.0] – 2025-03-13

### Added
- Unit tests for Dynmap integration.

### Fixed
- Anvil duplication exploit involving falling blocks in gates.

### Changed
- Dynmap processing made more configurable (optional realm and faction info display).
- Dynmap now reflects faction disbandment.
- Test server updated to Minecraft 1.21.4.
- Simplified test server setup.

## [5.4.0] – 2025-03-02

### Added
- Ability for players to submit applications to join factions.
- Dockerfile with Dynmap support.

### Fixed
- Power insufficiency check failing when a faction attempted to conquer land.

## [5.3.0] – 2024-01-19

### Added
- Brazilian Portuguese (pt-BR) translation.
- Config option to only render territories upon startup.
- Config option for claim fill max chunks.
- Docker-based test server.

### Fixed
- Disabling neutrality preventing the plugin from enabling.

### Removed
- Old claim commands (Phase 3 deprecation).

## [5.2.0] – 2023-07-06

### Added
- `protectVillagerTrade` faction flag.
- `factions.maxMembers` config option.
- `players.minPower` config option.
- Toggle Dynmap integration config option.
- Toggle block destruction in wartime config option.
- Expanded territory title notifications.

### Removed
- Old claim commands (Phase 2 deprecation).
- Chat preview listener.

## [5.1.4] – 2023-05-24

### Added
- Unique name check to `set name` command.
- Permission check for `mf claim auto` command.

### Fixed
- Players stealing power upon killing a player even when the victim had no power to steal.
- Language resource bundles only included if they exist.
