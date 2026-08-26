package com.dansplugins.factionsystem.api

import com.dansplugins.factionsystem.api.geometry.ChunkPos
import org.bukkit.Bukkit
import org.bukkit.Chunk
import org.bukkit.Location
import org.bukkit.World
import java.util.UUID

/**
 * The stable, in-JVM public API for MedievalFactions.
 *
 * This is the ONLY surface other plugins should bind to. Everything returned is either a Bukkit type
 * or an API-owned view/value type ([FactionView], [ClaimView], [FactionId], ...) — never an internal
 * `com.dansplugins.factionsystem.*` service or model. That decoupling is the whole point: when MF's
 * internals are refactored, only the API's adapter implementation changes, and dependent plugins keep
 * working without recompilation.
 *
 * Obtain an instance via Bukkit's ServicesManager (see [get]).
 *
 * ## Threading
 *
 * **Reads are safe from any thread. Writes block on the database and belong OFF the main thread.**
 *
 * Everything under "Reads" below is answered from `ConcurrentHashMap`s held in MF's services, so it
 * costs a map lookup and cannot stall anything. Everything under "Writes" performs synchronous JDBC
 * on **the thread that called it** — there is no internal dispatch that moves the write elsewhere.
 * MF's own command layer reflects this: it wraps service calls in `runTaskAsynchronously` at well
 * over a hundred sites, and this API is the same code underneath.
 *
 * ### What a main-thread write actually costs
 *
 * Enough to matter, and how much depends on the database:
 *
 * - **Saving one faction is not one statement.** The whole member, invite and application list is
 *   deleted and reinserted every time, with a read-back after each insert, inside one transaction —
 *   roughly `6 + 2 × (members + invites + applications)` statements. A forty-member faction is
 *   around eighty-six of them, whether you changed its name or its description.
 * - **Claiming one chunk is two** (an upsert and a read-back), and it is not transactional.
 * - **On the default embedded H2** those are in-process file writes and a main-thread call is merely
 *   wasteful. **On MySQL or MariaDB** — both fully supported — every statement is its own network
 *   round trip, because nothing is batched. The same call that was invisible on H2 becomes a
 *   multi-second freeze, and the failure arrives when an operator migrates the database rather than
 *   when anybody changed the code.
 *
 * So: do the write on an async task, and hop back with `runTask` for anything that touches the world
 * afterwards.
 *
 * ### Events do not follow the same rule as calls, and the exception matters
 *
 * Past-tense notification events in the `event` package are normally delivered on the **main
 * thread**, on the next tick, so their handlers may touch the world freely. Two ordering-critical
 * events are post-commit but fire inline with an honest asynchronous flag:
 * [event.FactionPeaceRequestedEvent] records each request before war-end processing, and
 * [event.FactionWarEndedCommittedEvent] reports the final committed war-row deletion before the
 * legacy main-thread [event.FactionWarEndedEvent] is queued.
 *
 * Cancellable pre-commit gates are also inline and may be asynchronous:
 * [event.FactionCreateEvent], [event.FactionClaimAttemptEvent] and [event.FactionWarStartEvent]. A
 * veto has to reach MF before it persists anything. MF's command chains perform these writes on
 * async tasks, so in ordinary play those events *are* async.
 *
 * Two consequences for anything handling a pre-commit gate. A handler must not assume it can touch
 * the world, and the proposed change does not exist yet. For faction creation specifically, the row
 * has not been written and the cache is not populated, so [getFaction] with the new id answers
 * `null` and any write keyed on it fails. React after the fact through a notification event; use a
 * gate only to allow or refuse.
 *
 * [ClaimOverrideProvider] and [SuccessionPolicy] are the mirror image — they are consumer callbacks
 * that MF invokes **inline on whatever thread MF is on**, so they must be fast and must not block.
 *
 * ### Calling a write off the main thread runs other plugins' listeners there too
 *
 * Bukkit invokes listeners inline on the calling thread; the asynchronous flag on an event describes
 * it, it does not move it. So an off-thread [claim] runs every third-party `FactionClaimEvent`
 * listener on that thread, and a listener written on the assumption it is on the main thread will
 * misbehave. This is unavoidable and is why MF marks its own events honestly rather than pretending.
 *
 * ### Concurrency between two writers
 *
 * Faction writes carry an optimistic lock, so two callers racing on one faction produce a *failure*
 * for the loser rather than a silent overwrite; treat a failed [ApiResult] as possibly meaning
 * "somebody else got there first" and re-read before retrying. Claim writes have no version column
 * and so no such protection: two callers racing on the same chunk are last-writer-wins.
 *
 * ### Views are snapshots of identity and live for territory
 *
 * A [FactionView] freezes name, members and ownership at the moment you looked it up, but reads
 * claim count, wars and hierarchy live on every access. Holding one across ticks therefore mixes
 * stale identity with fresh territory. Re-fetch rather than caching a view.
 */
interface MedievalFactionsApi {

    // --- Reads ---
    //
    // In-memory, and safe from any thread. See the threading note on the interface.

    fun getFaction(id: FactionId): FactionView?

    /**
     * Look a faction up by its display name.
     *
     * Named distinctly from [getFaction] rather than overloading it: [FactionId] wraps a [String], so
     * an overload taking a bare `String` is trivially selected by accident when a caller has an id in
     * hand — a mistake that compiles cleanly and then silently returns null for every real faction.
     */
    fun getFactionByName(name: String): FactionView?

    fun getFactionByPlayer(playerId: UUID): FactionView?

    /**
     * Every faction on the server.
     *
     * For consumers that must render or audit all of them rather than answer a question about one:
     * a web map's initial draw, a dashboard, a report. Every other lookup here is by id, name,
     * player or chunk, and none of them compose into "all of them".
     *
     * O(factions), off the in-memory faction index, with no database on the path. The list is a
     * snapshot and does not track factions created or disbanded afterwards; consumers that must stay
     * current should listen for the lifecycle events rather than re-reading this on a timer.
     */
    fun getFactions(): List<FactionView>

    /**
     * Whether the war between [faction] and [otherFaction] is fully established in both directions.
     *
     * MedievalFactions represents one war as two mirrored `AT_WAR` relationship rows. The broader
     * [FactionView.isAtWarWith] read deliberately answers true when either row exists, which is right
     * for gameplay but cannot distinguish a completed declaration from the partial state left when
     * only the first write commits. Consumers that spend one-shot authorization after a declaration
     * must use this read and spend only when it returns true.
     *
     * Returns false for the same faction, unknown factions, no war, and either one-direction-only
     * state. It reads only the concurrent in-memory relationship index, performs no database or
     * Bukkit-world work, and is safe to call from any thread, including an asynchronous war event.
     */
    fun isWarPairEstablished(faction: FactionId, otherFaction: FactionId): Boolean

    /** The faction that owns the given chunk, or null if it is unclaimed. */
    fun getFactionAt(chunk: Chunk): FactionView?

    /** The claim covering the given chunk, or null if it is unclaimed. */
    fun getClaimAt(chunk: Chunk): ClaimView?

    /**
     * Whether the chunk at the given chunk coordinates is claimed, **without loading the chunk**.
     *
     * [getFactionAt] and [getClaimAt] take a [Chunk], and obtaining one from a [Location] goes through
     * `Location.getChunk()`, which loads (and if necessary generates) the chunk. That is fine for the
     * occasional lookup but ruinous for callers that test many block positions per tick — territory
     * protection in a disaster/explosion plugin being the motivating case.
     *
     * This overload answers the only question such callers actually have, straight off the in-memory
     * claim index, so it costs one map lookup and never touches world state.
     */
    fun isClaimed(world: World, chunkX: Int, chunkZ: Int): Boolean

    /**
     * The claim covering the given chunk coordinates, **without loading the chunk**.
     *
     * The positional counterpart to [getClaimAt] for the same reason [isClaimed] exists: the
     * [Chunk]-typed overload forces a caller holding a [org.bukkit.Location] through
     * `Location.getChunk()`, which loads and if necessary generates the chunk.
     *
     * [isClaimed] answers only "is this claimed at all". Callers that need to know *whose* land it
     * is — to detect a change of owner, or to ask that faction for consent — had no cheap way to
     * find out. This closes that gap: it reads the same in-memory claim index, so it costs one map
     * lookup and never touches world state.
     */
    fun getClaimAt(world: World, chunkX: Int, chunkZ: Int): ClaimView?

    /**
     * Every chunk [faction] holds, grouped by the world it is in.
     *
     * The inverse of [getClaimAt]: that answers "who owns this chunk", this answers "what does this
     * faction own". Rendering a territory outline needs the second question and there was previously
     * no way to ask it short of walking every claim on the server.
     *
     * **Grouped by world because [ChunkPos] deliberately carries none.** A faction may hold land in
     * several worlds, and a boundary traced across a mixed set would be nonsense. Each value is
     * therefore exactly what [com.dansplugins.factionsystem.api.geometry.ChunkRingBuilder.buildPolygons]
     * takes, so the common caller is one lookup and one call per world with nothing to reshape.
     *
     * O(chunks owned by this faction), off the in-memory per-faction claim index. It never walks the
     * global claim set and never touches world state, so it is safe on the server thread. Worlds with
     * no claims are absent rather than mapped to an empty set, and an unknown faction gives an empty
     * map.
     *
     * The returned collections are snapshots and do not track later claims or unclaims. Re-read after
     * any of the claim lifecycle events rather than holding one and mutating it.
     */
    fun getClaimedChunks(faction: FactionId): Map<UUID, Set<ChunkPos>>

    /**
     * The chunks [faction] holds in one world, or an empty set if it holds none there.
     *
     * The overload to prefer when the caller is already per-world, which a web map marker set always
     * is: it skips grouping the faction's other worlds only to discard them.
     */
    fun getClaimedChunks(faction: FactionId, worldId: UUID): Set<ChunkPos>

    /**
     * How many chunks [faction] holds, across every world.
     *
     * O(1), off the same index. Prefer it to `getClaimedChunks(faction).values.sumOf { it.size }`,
     * which materialises every chunk to count them.
     */
    fun getClaimCount(faction: FactionId): Int

    /**
     * The power level of the given player, or `0.0` if MedievalFactions has no record of them.
     *
     * Power is MF's per-player score that, summed across members, bounds how much land a group may
     * hold. Exposed because consumers that model sub-groups of a faction (settlements, fiefs) need the
     * same currency to size their own land allowances consistently with MF's.
     *
     * O(1): MF holds players in an in-memory map keyed by id, so this costs one lookup and never
     * touches the database. Returning `0.0` rather than null for an unknown player matches how MF's
     * own callers treat a missing record, and keeps summing over a member list allocation-free.
     */
    fun getPower(playerId: UUID): Double

    /**
     * The value of one of the faction's flags, or null if no flag of that name is registered and null
     * if there is no such faction.
     *
     * Flags are MedievalFactions' per-faction settings: whether allies may build on the land, the
     * territory colour, and so on. They were readable only through MF's internal `plugin.flags` and
     * `MfFaction.flags`, so a consumer that wanted one had to bind to both, and the whole point of
     * this interface is that it does not have to.
     *
     * ## Why a name and not an enum, and why a string and not a type
     *
     * The name is already public. It is what a player types into `/f flag set`, what `/f flag list`
     * prints, what `factions.defaults.flags.*` keys on in config.yml, and what
     * `%MedievalFactions_faction_flag_<name>%` resolves. Nothing internal crosses the seam by naming
     * one, and unlike [FactionPermission] the set is not fixed at compile time: it is a list MF builds
     * at enable and another plugin may add to. Matching is case-insensitive, as MF's own lookup is.
     *
     * The value comes back as the string MF stores and shows for the same reason: MF's flags are
     * typed, boolean or string, but every one of them is set from a string and printed as one, so
     * handing back that string is the one answer that cannot disagree with what a player sees. A
     * boolean flag answers `"true"` or `"false"`.
     *
     * ## What null does and does not mean
     *
     * Null means there is nothing to read: no such flag, or no such faction. It never means "the
     * faction has not set this one". A faction whose stored flags carry no entry for a flag answers
     * that flag's default, which is what MF's own reads do, so an unset flag is indistinguishable from
     * one explicitly set to the default value. For a flag whose default is the empty string, an empty
     * answer is the honest one and is not the same as null.
     *
     * ## The value is not yours
     *
     * Anything a consumer writes here is visible to `/f flag list` and can be changed by a member
     * holding the flag's own permission, or by an operator holding `mf.force.flag` for any faction.
     * Treat it as a published field rather than as private storage, and re-read it rather than caching
     * what you last wrote.
     *
     * In-memory, and safe from any thread.
     */
    fun getFlag(faction: FactionId, flag: String): String?

    // --- Durable war-end delivery ---

    /**
     * Every war end this named consumer has not acknowledged yet, oldest first.
     *
     * MF inserts each [WarEndNotice] in the same database transaction that removes the final war
     * row (or disbands a faction and cascades its rows). This closes the crash window inherent in a
     * Bukkit event: if a server stops after the commit but before a listener saves its reaction, the
     * notice remains here after restart.
     *
     * The delivery contract is at-least-once. Persist [WarEndNotice.id] atomically with the
     * consumer's reaction, then call [acknowledgeWarEnd]. A crash before the acknowledgement returns
     * the same id again, so applying an already-recorded id must be a no-op. Acknowledgements are
     * scoped by [consumerId]; one plugin cannot hide a notice from another.
     *
     * For [WarEndReason.VOLUNTARY_PEACE], [WarEndNotice.actingFaction] is the final requester and
     * [WarEndNotice.endedAt] is that request's commit time. That is the durable equivalent of a
     * `FactionPeaceRequestedEvent` with `PeaceOutcome.PEACE_MADE`, allowing a consumer to combine it
     * with an earlier stored request when deciding whether peace was mutual.
     * [WarEndNotice.wasFullyEstablished] is producer-side proof that this exact war generation
     * reached both directional rows. Consumers may use it to distinguish a rapid complete war from
     * cleanup of a one-row interrupted declaration; they must not infer that distinction from event
     * timing.
     *
     * This performs blocking JDBC and belongs off the main thread. [consumerId] must be 1-128 ASCII
     * letters, digits, dots, colons, underscores or hyphens (a plugin name or namespaced key is a
     * good choice). Database and validation failures are returned as a failed [ApiOutcome].
     */
    fun getUnacknowledgedWarEnds(consumerId: String): ApiOutcome<List<WarEndNotice>>

    /**
     * Mark one durable war-end notice delivered to [consumerId].
     *
     * Repeating an acknowledgement succeeds without adding another row. An unknown notice id
     * fails, preventing a typo from masquerading as durable delivery. This performs blocking JDBC
     * and belongs off the main thread; call it only after the consumer's own state is committed.
     */
    fun acknowledgeWarEnd(consumerId: String, noticeId: UUID): ApiResult

    // --- Mutations ---
    //
    // BLOCKING JDBC on the calling thread. Call these off the main thread. See the threading
    // note on the interface for what one costs and why the database backend changes the answer.

    fun setHome(faction: FactionId, location: Location): ApiResult

    /**
     * Set one of the faction's flags, as `/f flag set` does.
     *
     * The write half of [getFlag], and see that method for why a flag is named rather than enumerated
     * and why its value is a string. [value] is coerced and validated by the flag's own rules, exactly
     * as the command coerces and validates what a player types, so a boolean flag refuses anything but
     * `"true"` or `"false"` and a string flag refuses whatever its own validator refuses. The failure
     * message is the one the player would have been shown.
     *
     * Fails, changing nothing, if the faction does not exist, if no flag of that name is registered,
     * if [value] cannot be coerced to the flag's type, or if the flag's validation refuses it.
     *
     * **It checks no faction permission.** This is a plugin acting, not a player, so it is the
     * equivalent of an operator's `mf.force.flag` rather than of a member's `/f flag set`. Deciding who
     * may ask is the caller's job, and [FactionRoleView.hasPermission] is how to ask it: gate on the
     * permission that guards the flag in question, which for the arms is
     * [FactionPermission.SET_COAT_OF_ARMS].
     *
     * **`factions.allowNeutrality` is still honoured**, so setting `neutral` to true on a server that
     * forbids neutrality fails. That is the server owner's setting rather than the faction's, and this
     * API does not offer a way around one; the same reasoning as `factions.allowLeaderlessFactions` in
     * [setPrimaryOwner].
     *
     * **Setting a flag costs a whole faction save.** MF has no per-field write, so this is the full
     * save described in the threading note above, member list and all: changing a flag on a
     * forty-member faction is around eighty-six statements. Reconcile on a change, never on a timer.
     *
     * Succeeds without writing anything when the flag already holds that value, so a caller mirroring
     * its own state onto MF need not compare first. Same shape as [setPrimaryOwner], and it exists for
     * the same reason: a reconciler that had to read before writing would either duplicate this
     * comparison or pay the save every pass.
     *
     * No event is fired. MF does not publish one for a flag change, and inventing one here would
     * announce to third-party listeners something that MF's own `/f flag set` does not.
     */
    fun setFlag(faction: FactionId, flag: String, value: String): ApiResult

    fun claim(faction: FactionId, chunk: Chunk): ApiResult

    /**
     * Claim a chunk by coordinates, **without loading it**.
     *
     * The write-side counterpart to [isClaimed] and the positional [getClaimAt], and it exists for
     * the same reason they do. The [Chunk]-typed overload forces a caller holding only coordinates
     * through `World.getChunkAt`, which **loads and if necessary generates** the chunk -- and doing
     * that off the main thread, which every write here is supposed to be on, is a synchronous load
     * from the wrong thread. A caller moving a large holding's land would generate all of it.
     *
     * MF stores claims by `(worldId, x, z)` internally and never needs the [Chunk] object, so this
     * is the shape the write always wanted.
     *
     * The UUID is durable world identity. Reassigning an existing persisted claim does not resolve
     * it through Bukkit, so cross-faction recovery can move unloaded-world land from its database
     * worker. Creating a genuinely new claim still applies the ordinary blocked-world validation.
     * Callers are trusted plugin code and must supply an id obtained from real server/world data.
     * Everything else about it matches [claim], including the events fired and the fact that it is
     * not transactional.
     */
    fun claim(faction: FactionId, worldId: UUID, chunkX: Int, chunkZ: Int): ApiResult

    /**
     * Re-own one persisted claim only if [expectedOwner] still holds it.
     *
     * This is the compare-and-transfer counterpart to positional [claim]. It never creates land:
     * wilderness or a claim since acquired by another faction is a failure, leaving that current
     * state untouched. Use it when applying a frozen conquest or secession snapshot, where an
     * unconditional positional claim could otherwise recreate an unclaimed chunk or steal a third
     * faction's newer acquisition.
     *
     * The lookup, comparison, repository write, and cache publication are one serialised claim
     * mutation. Like every mutation in this API it blocks on JDBC and belongs off the main thread.
     */
    fun transferClaim(
        expectedOwner: FactionId,
        to: FactionId,
        worldId: UUID,
        chunkX: Int,
        chunkZ: Int
    ): ApiResult

    fun unclaim(chunk: Chunk): ApiResult

    /** Ends any war between the two factions by removing the war relationship in both directions. */
    fun forcePeace(faction: FactionId, otherFaction: FactionId): ApiResult

    /**
     * Lay [faction]'s own half of its war with [otherFaction] down, exactly as `/f makepeace` does for
     * the faction that runs it, and report whether that ended the war or only offered to.
     *
     * The one-sided counterpart to [forcePeace], and it exists because that method is two-sided and
     * cannot be made to be anything else. A war is two mirrored `AT_WAR` rows, one owned by each
     * faction; [forcePeace] deletes both, which is the right call for a plugin ruling on a war and the
     * wrong one for a plugin that has merely watched **one** side agree to stop. This deletes only
     * [faction]'s rows, so the other side's consent is still required and is still theirs to withhold.
     * Peace in MF is already a handshake -- what was missing here was the half of it.
     *
     * ## The two successes are different events and the caller must tell them apart
     *
     * [PeaceOutcome.PEACE_REQUESTED] means the rows are gone and [otherFaction] is still at war with
     * [faction]. [PeaceOutcome.PEACE_MADE] means that was the last row and the war is over. Both are
     * successes, both are ordinary, and announcing one as the other is a lie a consumer will tell its
     * whole server, so the answer is an enum in an [ApiOutcome] rather than a boolean.
     *
     * Note that a `PEACE_REQUESTED` call leaves both factions still reporting each other in
     * [FactionView.factionsAtWarWith] and still answering true to [FactionView.isAtWarWith]: MF reads a
     * war from a row in either direction, so a half-laid-down war is a war. Do not use those reads to
     * confirm this call worked.
     *
     * ## Failures
     *
     * Fails, changing nothing, if the two are the same faction, if either does not exist, or if
     * [faction] holds no `AT_WAR` rows against [otherFaction]. That last case splits in two, and the
     * split is reported in the failure message because `/f makepeace` reports it too:
     *
     * - Neither side holds a row: they are not at war, and there was nothing to lay down.
     * - Only [otherFaction] holds rows: [faction] has already laid its half down, which the command
     *   calls peace having already been requested. **This is a failure rather than an idempotent
     *   success**, matching the command, so a caller laying both halves down in turn must carry on to
     *   the second call rather than abort on the first. It has not lost anything: the state it wanted
     *   is the state that already holds.
     *
     * ## Events, and what is fired before what
     *
     * Every success fires [event.FactionPeaceRequestedEvent] inline after every caller-owned war row
     * has been removed. It records the voluntary act, so it fires for both outcomes. Because this
     * write normally runs off-thread, that event may be asynchronous; its flag is honest.
     *
     * On [PeaceOutcome.PEACE_MADE], [event.FactionWarEndedCommittedEvent] fires inline and
     * [event.FactionWarEndedEvent] is queued for the main thread only after the live relationship
     * re-read proves neither direction remains. The inline peace-request event is observed before
     * both, which lets a consumer attribute the second request before concluding the war. A failed
     * delete emits none of these stable events and remains retryable.
     *
     * **This sends no chat.** The command's four notifications to the two factions belong to the
     * command, not to the write, so a consumer that wants its realms told must tell them.
     *
     * MF's internal relationship-delete event is cancellable, so another plugin can veto the write;
     * that arrives here as an ordinary failure. A faction normally holds exactly one `AT_WAR` row per
     * enemy, but every row it holds is deleted and the first failure stops the run, so corrupt data can
     * leave a partial lay-down behind -- which is what the command does with it too.
     */
    fun layDownArms(faction: FactionId, otherFaction: FactionId): ApiOutcome<PeaceOutcome>

    /**
     * Record [playerId] as the head of [faction], as though the previous head had handed it on.
     *
     * The write that makes an external government possible. Until this existed,
     * [FactionView.primaryOwnerId] was readable and movable only by MF itself — the founder at
     * creation, `/f transfer`, `/f admin setleader`, and succession — so a plugin that had decided
     * who should rule had no way to say so, and would have had to reach past this API into MF's
     * internal `MfFactionService` with an internal `MfFaction` to do it.
     *
     * Fails, changing nothing, if the faction does not exist or if [playerId] is not one of its
     * members. The membership requirement is not a convenience check: the head is the identity of a
     * House, several things key off it, and a head who is not in the faction would be reconciled
     * away by succession on the very next save anyway. Admit the player first if that is what you
     * mean.
     *
     * Succeeds silently when [playerId] is already the head, so a caller reconciling its own state
     * against MF's need not compare first.
     *
     * Fires [com.dansplugins.factionsystem.api.event.FactionPrimaryOwnerChangedEvent] on success,
     * like any other change of head, so a caller must not assume it is the only observer.
     *
     * There is deliberately no counterpart that clears the seat. Whether a faction may exist without
     * a head is `factions.allowLeaderlessFactions`, which is the server owner's setting, and this
     * API does not offer a way around it.
     */
    fun setPrimaryOwner(faction: FactionId, playerId: UUID): ApiResult

    /**
     * Replace or clear one exact primary-owner tenure.
     *
     * The write occurs only while both [expectedOwner] and [expectedTerm] still match the persisted
     * faction snapshot. [replacement] must be a faction member when non-null. A null replacement
     * deliberately clears only the office; members, roles, relationships, and claims are untouched.
     * This narrow nullable path exists for an externally certified death, where retaining a dead
     * character in the seat is not a valid fallback even on a server that ordinarily forbids
     * leaderless factions.
     *
     * A successful [PrimaryOwnerReplaceOutcome.MISMATCH] is a rejected comparison, not an I/O
     * failure. Re-read before deciding whether another tenure superseded the deferred work.
     */
    fun replacePrimaryOwnerIf(
        faction: FactionId,
        expectedOwner: UUID,
        expectedTerm: UUID,
        replacement: UUID?
    ): ApiOutcome<PrimaryOwnerReplaceOutcome>

    // --- Founding, dissolution, and moving people and land between factions ---
    //
    // The four calls below exist so that a plugin modelling a political event -- a secession, a
    // rebellion, a fief being raised into a realm of its own -- can carry it out without reaching
    // into MF's internal services. Each is a BLOCKING write like everything else in this section,
    // and each is heavier than the ones above it; read the individual notes.

    /**
     * Found a faction with [founderId] as its head, and return its new id.
     *
     * The same thing `/f create` does, minus the chat: the founder is admitted with the top default
     * role and recorded as [FactionView.primaryOwnerId], and the faction starts with no land, no
     * relationships and MF's default flags.
     *
     * Fails, creating nothing, if a faction of that name already exists, if the name is longer than
     * `factions.maxNameLength`, or if the name is blank.
     *
     * **A founder who is already in a faction is MOVED, not refused**, and the distinction is
     * load-bearing rather than a convenience. The motivating consumer is a secession -- somebody
     * taking part of a realm out of it -- and such a founder is by construction still a member of
     * the realm they are leaving at the moment the new one is founded. Refusing them meant this
     * could only be called for a player who already belonged nowhere, which is a player who does not
     * need a faction founded around them.
     *
     * **If the creation then fails, they are put back.** The creation fails routinely rather than
     * exceptionally: [event.FactionCreateEvent] is cancellable, and a server running a
     * founding-permit plugin vetoes it for anybody without a permit. Note that restoring membership
     * does not undo what other plugins did in response to the departure -- a fief moved on by a
     * consumer watching [event.FactionMemberLeftEvent] stays moved -- so a caller that cannot
     * tolerate that should establish the founder may create one before calling.
     *
     * They are removed from the old faction **before** the new one is written, because MF resolves a
     * player found in two factions to *neither* -- the lookup is a `singleOrNull` over every faction
     * -- so the window between the two writes must leave them factionless rather than doubly seated.
     * The departure runs MF's ordinary machinery: succession reseats the old faction if the founder
     * was its head, and [event.FactionMemberLeftEvent] is delivered, so a consumer holding sub-group
     * state (a fief, a settlement) reacts to it as it would to any other departure.
     *
     * A player record is created for [founderId] if MF has never seen them, exactly as `/f create`
     * does for a first-time founder.
     *
     * [event.FactionCreateEvent] is fired, and it is cancellable, so this can fail because another
     * plugin refused. Read the events note on this interface before writing a handler for it -- the
     * faction does not exist yet inside that event.
     */
    fun createFaction(name: String, founderId: UUID): ApiOutcome<FactionId>

    /**
     * Dissolve a faction, as `/f disband` does.
     *
     * **Its land is destroyed, not released to anybody.** Every claim it holds returns to wilderness,
     * and if you meant those chunks to end up somewhere else you must call [transferAllClaims] first;
     * afterwards is too late. The same goes for its members, who are left factionless.
     *
     * **This fires no per-chunk event**, neither MF's own nor [event.ClaimOwnerChangedEvent], because
     * a realm-sized faction can put thousands of chunks through it at once and scheduling an event
     * for each would stall a tick. A consumer tracking tenancy must treat
     * [event.FactionDisbandedEvent] as invalidating every chunk it believed that faction held.
     */
    fun disbandFaction(faction: FactionId): ApiResult

    /**
     * Move members from one faction to another, giving them the destination's default role.
     *
     * Both factions must exist. Duplicates and an empty collection are accepted and are no-ops.
     *
     * **Moving every member dissolves [from] rather than emptying it.** An empty faction cannot be
     * saved at all when `factions.allowLeaderlessFactions` is off, because succession runs on every
     * save and finds no successor -- so without this the ordinary case of a group returning home in
     * one call simply failed. This is what `/f leave` already does when the last member walks out.
     *
     * **Fails if none of the named players are members**, rather than reporting a vacuous success.
     * A caller that acts on the result -- disbanding the source, say -- must be able to tell
     * "everybody moved" from "nobody was there to move".
     *
     * **Ids that are no longer members of [from] are skipped, not refused.** This was all-or-nothing
     * and that was the wrong shape for every real caller: the motivating one moves a group recorded
     * minutes or days earlier, and any single member leaving in the meantime turned a routine move
     * into a failure that stranded everybody else -- or, for a caller that treated the move as
     * housekeeping and carried on, left the whole remaining group factionless. A caller that
     * genuinely needs all-or-nothing should compare the count it asked for against the membership it
     * finds afterwards.
     *
     * The skip rule applies to a partial move. **When the move consumes the whole faction, the named
     * ids must exactly equal its live roster.** That exact roster is checked again inside the atomic
     * mutation, so a durable settlement cannot dissolve a faction after one of its frozen members
     * has independently left.
     *
     * **A partial move remains two writes, and a failure between them leaves the players
     * factionless.** They are removed from [from] first and admitted to [to] second, deliberately in
     * that order: the other order would put them in two factions at once during the window, and a
     * player in two factions reads as being in *none* everywhere in MF. Moving the entire exact
     * roster is different: destination admission and source deletion commit in one database
     * transaction, and a cancelled disband or failed transaction changes neither faction.
     *
     * **Roles do not survive the move**, including the top one. A faction's roles belong to that
     * faction, and there is no general mapping between two factions' role sets; carrying a role
     * across by name would let a member arrive holding whatever authority the destination happens to
     * have given that name. If the arriving player is meant to lead, say so with [setPrimaryOwner]
     * and grant the role explicitly.
     *
     * Moving a faction's recorded head out of it does NOT leave the faction headless: MF's succession
     * runs inside the very save that removes them, so a new head is already seated and
     * [event.FactionPrimaryOwnerChangedEvent] already delivered by the time this returns. A caller
     * that wants the seat to end up somewhere specific should say so with [setPrimaryOwner]
     * afterwards rather than assume it is vacant. If you are moving everybody, disband instead.
     */
    fun transferMembers(from: FactionId, to: FactionId, playerIds: Collection<UUID>): ApiResult

    /**
     * Hand every chunk [from] holds to [to], and return how many moved.
     *
     * The land half of a conquest or a secession. Each chunk is re-owned individually, so
     * [event.ClaimOwnerChangedEvent] fires once per chunk with both owners, MF's own
     * `FactionClaimEvent` fires for each and **may cancel it**, and a partial transfer is therefore
     * a real outcome: the count returned is what actually moved, and it can be less than the count
     * [from] had. It is never a failure to move zero chunks from a faction that held none.
     *
     * **This is the most expensive call in this interface.** It costs two statements per chunk and
     * schedules a Bukkit task per chunk, so a faction holding a thousand chunks costs two thousand
     * statements -- on MySQL, two thousand network round trips. That is acceptable for a rebellion
     * resolving once, and it is not acceptable on a timer. There is no bulk shortcut, because the
     * per-chunk events are the entire reason a consumer can track tenancy at all.
     *
     * Stops at the first hard failure rather than continuing, so the count is a prefix of the work
     * and not a sample of it.
     */
    fun transferAllClaims(from: FactionId, to: FactionId): ApiOutcome<Int>

    /**
     * Break a faction's oath to its liege, leaving it sworn to nobody.
     *
     * The write behind a war of independence. [FactionHierarchyView] made vassalage readable, so a
     * plugin could see that a realm swore fealty and could derive a rank from it, but nothing could
     * move it -- `/f declareindependence` and `/f grantindependence` were the only routes and both
     * need the faction's own leader to type them. A government plugin that has just watched a vassal
     * win a war had no way to record the result.
     *
     * Removes every relationship row between the two, in both directions, which is what MF's own
     * command does. **It does not start a war**, deliberately: `/f declareindependence` couples the
     * two and then makes the war conditional on neutrality flags, so a caller that wanted the oath
     * broken got a war it may not have wanted and a caller that wanted the war got a silent no-op
     * when either side was neutral. These are two acts here, and a caller that wants both calls
     * [declareWar] as well.
     *
     * Fails, changing nothing, if the faction does not exist or swears to nobody.
     */
    fun renounceLiege(vassal: FactionId): ApiResult

    /**
     * Swear [vassal] to [liege], as `/f swearfealty` accepting a `/f vassalize` offer does, without
     * either faction having to agree.
     *
     * The counterpart of [renounceLiege], and needed for the same reason it is: a plugin that can
     * break an oath but not restore one can only ever destroy hierarchy. The motivating case is a war
     * of independence that the vassal <em>loses</em> -- the oath it broke has to go back, or losing
     * would cost a realm its whole position over an attempt, which is a far heavier penalty than
     * winning is a reward.
     *
     * Writes both rows: the vassal's liege row and the liege's vassal row. Fails, changing nothing,
     * if either faction does not exist, if they are the same faction, or if [vassal] already swears
     * to somebody -- a faction with two lieges is a state MF's own walk resolves by picking the
     * first, so it must not be creatable.
     *
     * **It does not check for a cycle**, and a caller that swears a liege to its own vassal will
     * produce one. MF's hierarchy walk is depth-bounded rather than cycle-safe, so the symptom is a
     * wrong depth rather than a hang, but it is still wrong. Callers move oaths they already know
     * the shape of; do not expose this to a command without checking.
     */
    fun swearFealty(vassal: FactionId, liege: FactionId): ApiResult

    /**
     * Put two factions at war, as `/f declarewar` does, without asking either of them.
     *
     * The mirror of [forcePeace], and the reason it exists: a plugin that can end a war it did not
     * start could not start one it intends to end. Fails if both mirrored rows already exist, if
     * either faction does not exist, or if they are the same faction. If an earlier attempt saved
     * only one direction, a retry writes exactly the missing mirror and repairs the war.
     *
     * [event.FactionWarStartedEvent] is a post-commit notification. The first successfully persisted
     * `AT_WAR` direction establishes the pair in the live relationship index; the bridge collapses
     * the mirror into one event and queues it for the next main-thread turn. A failed repository
     * write emits nothing.
     */
    fun declareWar(faction: FactionId, otherFaction: FactionId): ApiResult

    // --- Territory protection exceptions ---
    //
    // Registration is in-memory. The PROVIDER is called back inline on MF's own thread, from
    // block and entity listeners, so it must be fast and must never block.

    /**
     * Register a [ClaimOverrideProvider], letting this plugin grant narrow exceptions to territory
     * protection.
     *
     * Providers are **additive only** — they can turn one of MF's denials into a permission, never
     * the reverse. Read [ClaimOverrideProvider] before implementing one; in particular, refuse
     * [ClaimAction.CONTAINER] unless you genuinely intend to hand over every chest on the land.
     *
     * Registering the same instance twice is a no-op. Plugins should unregister on disable.
     */
    fun registerClaimOverrideProvider(provider: ClaimOverrideProvider)

    /** Remove a previously registered provider. Unknown providers are ignored. */
    fun unregisterClaimOverrideProvider(provider: ClaimOverrideProvider)

    // --- Succession ---

    /**
     * Register a [SuccessionPolicy], letting this plugin decide who inherits a faction whose head
     * has departed, in place of MedievalFactions' own order.
     *
     * Read [SuccessionPolicy] before implementing one; in particular it is consulted from inside a
     * save, so it must answer from memory and must not save anything itself.
     *
     * Registering the same instance twice is a no-op. Plugins should unregister on disable — a
     * policy left registered by a plugin that is no longer functioning will throw on the next
     * departure, and while that is contained, every faction it governs then falls back to MF's
     * order without anyone having decided that.
     */
    fun registerSuccessionPolicy(policy: SuccessionPolicy)

    /** Remove a previously registered policy. Unknown policies are ignored. */
    fun unregisterSuccessionPolicy(policy: SuccessionPolicy)

    companion object {
        /**
         * Convenience accessor: the registered API instance, or null if MedievalFactions is not
         * loaded. Equivalent to querying Bukkit's ServicesManager.
         */
        @JvmStatic
        fun get(): MedievalFactionsApi? =
            Bukkit.getServicesManager().getRegistration(MedievalFactionsApi::class.java)?.provider
    }
}
