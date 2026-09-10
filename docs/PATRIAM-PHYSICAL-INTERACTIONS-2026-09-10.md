# Physical interaction task and notice bounds

Baseline: `9c006b48c101c61517beaa1197948f6d9f15c6f5`. This selective safety port follows
the physical-interaction fixes reviewed at upstream
`3b7a6924f95d4ff9bee1304784b57c38ae20595c`; it preserves Patriam's claim override,
lock-accessor, faction-role bypass and separate wartime policies.

## Behavior

Repeated `Action.PHYSICAL` events still recheck and enforce all existing protections. They
no longer send territory, wilderness or bypass notices, or queue lock-owner name lookups
whose only purpose was to send those notices. Deliberate clicks retain their notices.
Wilderness prevention and alert choices retain their behavior for deliberate interactions.

When the player record is missing, the block-interaction listener denies every event while
at most one save for that actor is pending in this listener. It captures player identity and
initial configuration on the server thread before scheduling persistence. Completion,
service refusal, runtime failure and scheduler rejection all release the pending marker;
refusal never becomes an interaction grant. A save-refusal notice is scheduled back onto
the server thread and is sent only if the player is still online.

No operator option, configuration schema, database schema or public API changed. This
does not modify wartime action routing or separate the held-item result from block denial.

## Verification

Owner commands ran from `G:/Git/Medieval-Factions` with JDK `25.0.4` and the checked-in
Gradle wrapper. No install or publication task was used.

```text
gradlew.bat --no-daemon test --tests com.dansplugins.factionsystem.listener.PhysicalInteractionProtectionTest
gradlew.bat --no-daemon test --tests com.dansplugins.factionsystem.listener.PhysicalInteractionProtectionTest --tests com.dansplugins.factionsystem.listener.PlayerInteractListenerTest
gradlew.bat --no-daemon formatKotlin
gradlew.bat --no-daemon lintKotlin test
```

- Fresh red: 16 executions, 11 intended failures, no errors or skips. The failures expose
  repeated messages/lookup tasks, duplicate registration, off-thread failure notices and
  uncontained scheduler/save refusals; five valid enforcement controls passed.
- The first green pass had all 16 new cases passing; one existing test still expected a
  physical-event notice. Its cancellation assertion was retained and its notice assertion
  now requires silence.
- Final owner gate: **850 tests, no failures, errors or skips**, including 18 new physical
  cases; Kotlin lint passed. The two additional cases prove marker release after success
  and after failure-notice scheduling is itself rejected.

Raw red and full XML reports and logs are preserved outside `build/` under
`E:/Minecraft/Patriam/Backups/Patriam_v7_(Revival)/Logs/implementation-20260910/mf-physical/`.
`harness-first-player-id.log` records a corrected test compile typo; it is not defect
reproduction evidence. `owner-first-green-legacy-notice.log` records the obsolete notice
expectation before correction. `owner-red.log` and `owner-full.log` are the final red and
green gates respectively.

The tests construct real Bukkit interaction events and explicitly drain captured scheduler
callbacks. They prove event outcomes, owner lookup/task counts, unchanged deliberate-click
notices and immutable pre-dispatch identity capture. They do not simulate native pressure
plates, tripwires or client packets. Pending registration is bounded within this listener,
not globally across every MF entry point. Existing deliberate-click lock-owner callback
threading and command-selection paths are unchanged. Server acceptance remains separate.
