# Consistent entity interaction protection

The plain entity interaction listener previously protected villagers only. Armor stands,
item frames, animals and inventory-bearing entities could bypass claim protection through
that event path. The separate precise-hit listener also ignored an explicitly disabled
villager trade flag and classified entity inventories as generic interactions, allowing a
claim override intended for ordinary interactions to expose storage.

Both registered listeners now share one guard. It applies the existing wilderness
prevention/alert settings and authoritative claim permission checks on every event.
Villagers retain their separate `protectVillagerTrade` flag. Territory bypass requires
both the cached MF bypass state and `mf.bypass`; it does not exempt wilderness prevention.
A claimed chunk whose owning faction cannot be resolved remains denied before any
permission or claim-override admission. Global claim-service semantics are unchanged.

Every `InventoryHolder` entity is classified as `ClaimAction.CONTAINER`, reaching MF's
existing hard exclusion before any provider is consulted. Non-inventory entities still
support scoped `INTERACT` overrides, including the registry's existing failure containment.
Earlier cancellation is never cleared.

Cancellation is independent of notices. The paired plain/precise events produce one notice
for the same player, entity and reason within 250 ms, measured with monotonic time.
Suppressed attempts do not extend that window. A changed target or denial reason can notify
immediately, and quit removes only the departing player's notice entry. Unknown players
remain denied while one shared registration is pending; identity/configuration is captured
before asynchronous persistence, and any failure notice returns to the server thread.
Completion or scheduler refusal releases pending registration without permitting the denied
event to continue.

This is a selective adaptation of upstream entity-protection work reviewed at
`3b7a6924f95d4ff9bee1304784b57c38ae20595c`. It retains Patriam's override exclusions,
fixture/ownership/title contracts and existing operator settings. No upstream entity-policy
option, database migration, configuration default or schema change is introduced.

## Verification

Against unchanged production at `4a0edf9b0ddbfb9f5f1d7a9b66d0f049bdefd8f7`, the fresh
listener matrix ran 36 executions with 14 expected assertion failures and no runtime errors.
After the repair, the expanded focused matrix passed 43 executions. A final service-refusal
case brings the new owner coverage to 44 executions; the full `lintKotlin test` gate passes
832 tests with zero failures, errors or skips on Java 25.

```powershell
$env:JAVA_HOME = 'C:/Program Files/Java/jdk-25.0.4'
.\gradlew.bat --no-daemon test --tests com.dansplugins.factionsystem.listener.EntityInteractionProtectionTest
.\gradlew.bat --no-daemon lintKotlin test
```

The tests dispatch real Bukkit event objects through both listener methods and use the real
claim override registry, with mocked world/player/service boundaries. They prove listener
decisions, exact action/position routing, notice timing and asynchronous registration
ordering. They do not claim native client packet, villager UI, entity inventory or live
Paper acceptance. The parameterized-test dependency is test-only and uses the already
resolved JUnit platform's version alignment.

Red/green logs and exact JUnit XML are preserved outside the build directory under
`Logs/implementation-20260910/mf-entity/` in the Patriam coordination workspace. Earlier
test-harness setup failures are named separately and are not counted as the fresh red
reproduction. Canonical downstream build, artifact staging and any server acceptance
remain separate release gates. Physical interaction spam, wartime action dispatch and
block/item result separation remain separate follow-up work.
