# Title hierarchy and committed membership notifications

Two persisted relationship shapes could give PatriamMFAddon the wrong title hierarchy:

- Multiple reciprocal VASSAL rows pointing to one crowned child counted as multiple crowned
  vassals. One logical branch could therefore satisfy the two-branch Emperor threshold.
- An unreciprocated LIEGE row before a valid reciprocal row hid the valid liege. An otherwise
  imperial faction could incorrectly appear sovereign.

MF now returns distinct reciprocal vassal IDs and ignores unreciprocated liege candidates when
finding the first valid liege. It does not rewrite stored relationships or choose a new policy for
contradictory, distinct valid lieges. No title thresholds, government forms, fief rules, operator
configuration, or database schemas change.

Ten owner regressions exercise the real relationship repository/service and public
FactionViewAdapter hierarchy: one crowned branch with 1/2/5 copies of each relationship row; two
distinct crowned branches with 1/2 copies; stale-only, stale-before-valid, and stale-after-valid
lieges; and removal of either last reciprocal child row. Deleting redundant rows preserves the
logical branch, while deleting the last valid half immediately reduces the crowned count.
These cover the relationship integrity subset of `mfaddon.title-boundaries`; the complete launch
family also includes government, regency, fief-holder, and primary-owner transitions.

## Additive stable events

`com.dansplugins.factionsystem.api.event` now includes:

```kotlin
class FactionCreatedEvent(val faction: FactionId) : Event()
class FactionMemberJoinedEvent(val faction: FactionId, val playerId: UUID) : Event()
```

Both have the normal Bukkit HandlerList and static getHandlerList. Java consumers use getFaction()
and, for Joined, getPlayerId(). They are noncancellable main-thread notifications scheduled for the
next tick after the repository acknowledges the commit and MF publishes the faction cache.
Consumers must reread current state: the event records history, and another mutation may have
committed before it is delivered. The original cancellable, possibly asynchronous FactionCreateEvent
remains unchanged and is still the creation veto.

Creation emits one Created event, including when the initial roster is empty. Initial members do
not also emit Joined. On an existing faction, Joined is derived from the distinct parsed UUIDs in
the persisted roster that were absent from the previous roster. Ordinary saves and atomic
whole-roster transfers use the same publication point. Existing members, duplicate rows, UUID
spelling differences, no-op saves, role edits and renames do not manufacture extra joins. Malformed
imported player IDs use the existing notification policy of skipping unparseable UUIDs.

Cancelled saves, optimistic conflicts and uncertain repository acknowledgements do not emit
committed membership notifications. Scheduler refusal is logged and cannot turn an already saved
mutation into a failed result. This is an in-process notification contract, not a durable outbox
or replay mechanism across shutdown.

Fourteen new lifecycle test executions cover deferred delivery and cache visibility, the original
stable create veto, save failures and retry, uncertain acknowledgements, duplicate/canonical UUIDs,
unchanged rosters, stale snapshots, scheduler refusal and historical delivery after a subsequent
leave. The existing atomic cancellation/failure/retry test additionally verifies that a member
already present in the destination is not announced as arriving again.

## Validation

With JAVA_HOME set to `C:\Program Files\Java\jdk-25.0.4`, from the MF repository:

```powershell
.\gradlew.bat --no-daemon --offline clean test --tests 'com.dansplugins.factionsystem.relationship.MfFactionHierarchyTest' --tests 'com.dansplugins.factionsystem.api.impl.FactionViewAdapterTest' --tests 'com.dansplugins.factionsystem.faction.MfFactionMutationLifecycleTest'
.\gradlew.bat --no-daemon --offline lintKotlin test --tests 'com.dansplugins.factionsystem.relationship.MfFactionHierarchyTest' --tests 'com.dansplugins.factionsystem.api.impl.FactionViewAdapterTest' --tests 'com.dansplugins.factionsystem.faction.MfFactionMutationLifecycleTest'
.\gradlew.bat --no-daemon --offline clean lintKotlin test
```

The initial fresh main compile succeeded; test compilation first caught an invalid value-class
vararg in a new helper. After that helper correction, the red run reported 53 tests and 11 intended
failures: four hierarchy cases and seven missing-notification cases. Applying the fixes made all
53 pass with lint. Three additional UUID/uncertain-acknowledgement/historical-delivery tests were
then included in the fresh full gate: **788 tests, 93 suites, zero failures/errors/skips**, with both
main and test Kotlin lint passing.

Read-only review then hardened the cancelled-create test: gate observations are captured inside
the callback and asserted outside the save, so an assertion cannot be mistaken for the expected
ServiceFailure. The final `lintKotlin test` rerun again passed all 788 tests with zero skips.

Logs and copied JUnit XML are retained outside Gradle's build directory under the coordination
workspace's `Logs/implementation-20260910/titles/`. The preliminary existing-class reproduction is
corroborating evidence; the fresh red/green runs above are the source-owned proof.

These are owner tests using simulated Bukkit scheduling and service operations, not live Paper
or client acceptance tests. Release still requires the canonical `tools/build-all.sh Medieval-Factions`
dependency flow, which also rebuilds downstream consumers. This owner slice does not publish,
install, stage or deploy artifacts.
