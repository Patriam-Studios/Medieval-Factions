# Patriam upstream reconciliation, 2026-09-07

Fetched Dans-Plugins/main through `3b7a6924`. The fork was 103 upstream commits behind before
this review. This release deliberately reconciles individual correctness fixes, preserving
Patriam's API, demesne curve, durable succession/war transactions, and config-schema contract.

Imported the current upstream command tests and fixes for command-time position snapshots in
claim check/circle/fill, unclaim, gate remove, map and duel accept. Gate removal now rejects
other worlds and computes squared distances using Long. Quit persistence runs asynchronously
after the immutable player snapshot and interaction-cache eviction. Sources include
`5d14aa35`, `1d8a585f`, `28b34725`, `48d97759`, `2093331a`, and their upstream test corrections.

Review also found and repaired three fork defects: player optimistic locking compared the
version column with itself; failed application saves cast Unit to Nothing and crashed; and a
failed restricted-gate deletion aborted the remaining gate review through the same bad cast.
Applications now stop cleanly without sending a success message when either save fails.

Validation: 752 tests pass on Gradle 9.7.1 / JDK 25, including a real H2 stale-player-write
regression and failed-application command tests. The wrapper also launches successfully on
JDK 21. The existing staged Java 25 modernization was completed at `e56265b0` with official
Gradle distribution and wrapper checksums.

Remaining upstream scope: the 6.0 release/version transition, JSON storage backend and
database migration commands, entity-interaction configuration changes, chat PlaceholderAPI
expansion, member force-move confirmation, and related CI/docs. These were fetched but not
merged wholesale: JSON repositories would need the same transactional/durable guarantees as
the fork repositories, and new configuration defaults require the next explicit config-schema
migration. This is not a claim that all upstream commits have been integrated.
