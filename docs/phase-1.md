# Phase 1: minimum viable product

Covers V-01–V-05, T-01–T-05, Q-01–Q-07 and W-01–W-05. Also includes the Phase 0 priorities: the incremental index (V-08) and background scanning (N-06).

## Changes from the Phase 0 plan

- **Parser not copied from Todosian.** `Task` in `Tasks.kt` peels metadata fields off the end of the line in any order. It uses the field regexes from the official plugin's `DefaultTaskSerializer`, including tags mixed in among the fields. That takes about 30 lines, so there's no Apache-2.0 code to attribute.
- **JobScheduler, not WorkManager.** `RefreshJob` is a platform `JobService` fed by `JobScheduler.enqueue`, so there's no AndroidX dependency. A refresh requested during a scan queues behind it instead of cancelling it.

## How it fits together

- `Tasks.kt`: parses tasks (status, description, 📅, ⏳, priority, 🔁) and completes them safely. A task's `noteHash` fingerprints the note at scan time. While the note is unchanged, the scanned line number is trusted, so one of two identical tasks can now be completed. After a change, the task must be found by unique exact text, as in Phase 0.
- `Filter.kt`: saved filters. Status, plus a due range in days relative to today, plus a priority range, combined with AND or OR. Sorting is by due date or priority, with ties broken by note and then line. Filters are saved as one tab-separated line each.
- `Index.kt`: `refreshIndex` re-reads only notes whose `COLUMN_LAST_MODIFIED` changed. If the provider gives no timestamp (0), every note is re-read on every refresh.
- `Vault.kt`: SAF. Before writing, it copies the note into app storage, then reads the note back to check the write. If the write throws, it restores the copy. If the note only reads back different, that may be a sync edit, so it leaves the note alone and keeps the copy. Any copy left behind is shown in the app.
- `Store.kt`: filters (with four presets), the filter each widget shows, the index cache (Java serialization, rebuilt on any read failure) and the scan status.
- `TasksWidget.kt`, `WidgetConfigActivity.kt`, `MainActivity.kt`: per-widget filters, ☐ to complete a task, and empty states that say what's wrong and open the screen that fixes it. The filter editor spells out every date choice.

## Emulator results (Pixel 10 Pro XL, API 37, §7 vault, 2 November 2026)

| Test | Result |
|---|---|
| AT-01 | Pass. 2 notes, 5 tasks; `.obsidian/` skipped. |
| AT-02 | Pass. The "Due today or overdue" widget shows Review preprint and Weekly review. |
| AT-03, AT-04 | Pass in unit tests (`FilterTest`). The "Due within 7 days or high priority" preset uses the AT-04-style OR. |
| AT-05 | Pass. Widget B ("High priority") shows Review preprint and Order supplies next to widget A. |
| AT-06 | Pass. Completing Review preprint from widget A changed only that line. Both widgets refreshed within about 50 ms. |
| AT-07 | Pass. With the due date changed externally, the result was `Conflict` and the note's md5 was unchanged. |
| AT-08 | Pass. Weekly review returned `Unsupported`; nothing was written. |
| AT-09 | Pass. With the vault moved away, the widget showed "Can't read the vault (…). Open Vault Tasks to choose it again." |
| AT-10 | Pass. A task added in `Areas/Garden/Plan.md` appeared after a refresh. |

Also checked: renaming a filter updates the title of the widget showing it, and a widget whose filter is missing offers "Tap to choose one". Completing the second of two identical tasks in an unchanged CRLF note changed only that line and kept its trailing spaces.

## Not done yet

- None from Phase 1.

## Decisions since

- **Midnight rollover (V-07):** every refresh sets a non-waking alarm for the next local midnight with a 10-minute window, so no exact-alarm permission is needed. The alarm triggers a refresh. On the emulator it fired at 00:00:18 and set the next one. If the phone is asleep at midnight, it fires when the screen comes on.
- **Heading context (V-03):** not wanted. Widgets show the note's path only.
- **Licence (N-02):** GPL-3.0-or-later. The only shipped dependency is the Kotlin standard library (Apache-2.0, compatible with GPLv3 but not GPLv2). The parser's field regexes follow obsidian-tasks (MIT), credited in `Tasks.kt`.

## S26+ timing (real vault, 2 November 2026)

1,258 notes, 371 tasks. Cold scan (index deleted): listing 1,231 ms, reading 16,145 ms. Warm scans: listing about 1.3 s, reading about 90 ms. The incremental index cuts a refresh from about 17 s to about 1.4 s. Listing is now most of a refresh.
