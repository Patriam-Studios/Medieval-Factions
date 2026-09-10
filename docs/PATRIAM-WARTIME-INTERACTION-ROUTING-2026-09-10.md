# Wartime interaction policy dispatch

Baseline: `86cad80e484698d0a0d09a7dd6262619c7b1d9c6`. This is a selective local port of
the action dispatch reviewed at upstream `3b7a6924f95d4ff9bee1304784b57c38ae20595c`.

The interaction listener previously consulted `wartimeInteractableBlocks` before checking
the Bukkit action. A grant intended for right-click interaction could therefore allow a
left-click or physical event, and could bypass the separate placing/breaking list checks.
The listener now selects the matching policy first:

| Event | Policy consulted after normal claim/override/bypass denial |
| --- | --- |
| Left-click block | `wartimeBreakableBlocks` for the target block |
| Right-click an interactive block | `wartimeInteractableBlocks` for the target block |
| Right-click a noninteractive block with an item | `wartimePlaceableBlocks` for the held item |
| Physical event, or noninteractive empty-hand click | No wartime list grant |

The existing ladder exception remains first for an actual ladder right-click against a
solid, noninteractive target. Other events do not call the ladder policy with a false
placement predicate. If that exception refuses, the ordinary action-specific policy may
still permit the attempt.

Normal faction relationship grants, generic claim overrides, authoritative bypass and lock
access rules retain their precedence. Inventory holders are still classified as CONTAINER
before DOOR or INTERACT, and the override registry still refuses containers before asking
providers. An explicit operator wartime interaction grant remains a separate owner policy.
An allowed attempt leaves both prior Bukkit results untouched, including another plugin's
DENY. Denied attempts retain ordinary complete cancellation.

No configuration values, defaults, schema, persisted state or public API changed. The
existing three configuration lists retain their distinct documented purposes. There is no
new held-item allowance and no change to the existing edible-item exemption in this slice.

## Verification

Owner commands ran with JDK `25.0.4` and the checked-in Gradle wrapper from the repository:

```text
gradlew.bat --no-daemon test --tests com.dansplugins.factionsystem.listener.WartimeInteractionRoutingTest
gradlew.bat --no-daemon test --tests com.dansplugins.factionsystem.listener.WartimeInteractionRoutingTest --tests com.dansplugins.factionsystem.listener.PlayerInteractListenerTest --tests com.dansplugins.factionsystem.listener.PhysicalInteractionProtectionTest
gradlew.bat --no-daemon formatKotlin
gradlew.bat --no-daemon lintKotlin test
```

Fresh red: **24 executions, 18 intended assertion failures, no errors/skips**. Six failures
demonstrate an incorrectly allowed Bukkit result; twelve detect wrong or additional policy
consultations. Six compatibility controls already passed. The earlier fixture preparation
run needed the existing Bukkit registry shim and assertions against each event's actual
prior result; it is preserved separately and is not counted as defect reproduction.

Focused green: **75 tests passed**. Final lint and full owner gate: **874 tests passed**, no
failures, errors or skips, across 96 suites. The one existing wartime-interaction positive
test now marks its mocked target as interactive, matching that test's intended scenario.

Logs and XML are preserved outside `build/` under
`E:/Minecraft/Patriam/Backups/Patriam_v7_(Revival)/Logs/implementation-20260910/mf-wartime/`:
`owner-red.log`, `owner-red-xml/`, `owner-focused-green.log`,
`owner-focused-green-xml/`, `owner-full.log`, and `owner-full-xml/`.
`harness-first-event-fixture.log` records only the superseded fixture preparation run.

These tests exercise real Bukkit event result fields and owner policy routing with mocked
claim services; the override controls use the real hard-exclusion registry. They do not
prove a client packet, native crop trampling, completed block break/place or all wartime
relationship combinations. BlockBreak and BlockPlace retain their independent owner gates.
No artifact was installed, published or staged, and no server test was run for this slice.
