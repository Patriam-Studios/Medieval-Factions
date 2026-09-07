# Disposable faction command fixtures

`DisposableFactionFixtureService` is registered with Bukkit's ServicesManager. It is exported
in the existing stable API artifact; consumers must not access MF repositories or files directly.

The harness creates inert Citizens player bodies, records their UUIDs, then calls
`beginFixture(tag, actorIds)` before its first MF command. Tags match `PT[0-9a-f]{10}`. Faction
names may append up to 20 ASCII letters, digits, underscores or hyphens. A receipt supports up
to 64 actors, and the same UUID cannot be reserved under a second receipt.

Begin refuses preexisting actor records, existing faction references or an existing tag
namespace. MF persists the actor allowlist and baseline faction IDs under its own
`disposable-fixtures/` directory before returning success. These are runtime ownership records,
not operator configuration. The harness must retain the exact tag and UUIDs in its recovery log.

`cleanupFixture(tag, actorIds)` first durably closes the receipt, waits up to ten seconds for
admitted identity writes, and fences later actor/namespace writes. It only disbands new matching
factions whose primary owner, members, invitations, applications and heir are fixture actors.
External faction relationships or actor references refuse cleanup. Normal disband performs MF's
existing transactional cascade and events. Remaining orphan actor records are removed as one
database transaction after checking memberships, owner/heir references, locks, gate contexts and
duels. Only actor-owned interaction state and chat history are removed with an orphan record.

Failure may occur after some faction disbands. Retry the exact receipt; no unrelated state is
restored. Closing is permanent and survives restart, including an interrupted cleanup. Normal
player/faction saves, bulk power publication, member transfers, queued chat persistence and duel
creation share the fence so accepted late work cannot recreate a cleaned actor. New runs use new
tags and actor UUIDs. Cleanup from inside a save callback is refused instead of deadlocking.

`inspectFixture` returns remaining faction IDs and actor UUIDs plus `complete`. Complete requires
a closing receipt and no remaining MF state for the namespace/actors. It does not prove that
Fiefs, the government add-on, Ledger, or any other subscriber has finished its own cleanup; each
owner must provide that proof separately, after its queued lifecycle callbacks drain.

Validation on 2026-09-07: 764 MF tests pass, including 12 fixture regressions using the real H2
schema and explicit concurrent-save barriers. No configuration schema changed.
