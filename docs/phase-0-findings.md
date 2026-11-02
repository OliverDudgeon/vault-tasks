# Phase 0 findings: candidate repositories

Checked 2 November 2026. Both repos were cloned into `upstream/` (gitignored) and built locally with Temurin 17.

| | Obsidian Widget (`f734b2b`, v4.2.2) | Todosian (`5c93fc3`, v2.2) |
|---|---|---|
| Builds | Yes (`assembleDebug`). Needs JDK 17: Gradle 8.13 fails on Android Studio's bundled JBR, which is **25.0.4**, not 21 as §9 says. | Yes (`assembleDebug`). All 107 unit tests pass. |
| Licence | **No LICENSE file in the repo.** The README just says "MIT". Without a licence text there's no clear grant, so we can't copy code until the author adds one. | Apache-2.0 `LICENSE` plus a `NOTICE` (copyright İsmail TİRYAKİ). Copying code is fine if we keep both. |
| SDK and stack | compileSdk/targetSdk 34, minSdk 26, AGP 8.13, Views, no tests | compileSdk 37.1, targetSdk 37, minSdk 30, AGP 9.4.1, Compose + Glance, coroutines |
| Vault scan | Reads one daily or pinned note. Not recursive. | `DocumentFile.listFiles()` on the top level only. Not recursive. Each file is a "category". |
| Task parser | One regex: `- [ ]`/`- [x]`, no metadata | `MarkdownParser.kt`: pure Kotlin, no Android imports. Parses 📅 ⏳ 🛫 ➕ ✅ 🔁 and priorities from the end of the line, like the Tasks plugin. Only handles `- [ ]`/`- [x]` (no `*`, `+`, `1.`, `X` or custom states), and there's no ❌. |
| Task identity | Line index only | A random UUID on every parse (not stable), plus line index and text |
| Write path | `toggleChecklistItem`: finds the line by index alone, with **no content check**. Rewrites the marker as `- `, uses `lines()`/`join("\n")` (loses CRLF), and opens the file with `"wt"`. | `updateLines`: re-reads the file under an in-process mutex. If the text at the index doesn't match, it **falls back to the first line with the same text**, which picks the wrong task when descriptions repeat. Opens the file with `"w"` (some providers don't truncate on `"w"`; it tries `channel.truncate(0)`). `readLines()` plus `newLine()` loses CRLF and the trailing newline. Toggling collapses runs of spaces anywhere in the task text. |
| Widget | `RemoteViewsService` list (`setRemoteAdapter(Intent)` is deprecated since API 35). Settings per widget instance in SharedPreferences. | Glance `LazyColumn`. Selection per widget instance (one file or "All lists"), a snapshot stored in Glance state, toggle via `ActionCallback`. |
| Recurrence | None | `RecurrenceEngine` with 32 tests. Completing a task inserts the next occurrence. |

## What carries over

- **Parser:** Todosian's `MarkdownParser.parseRemainder` is the only real candidate. It needs: more list markers and checkbox states, ❌, stable IDs, and changes so writes preserve the task text exactly.
- **Writer:** neither is safe enough for N-04/N-05/N-12. Both lose CRLF, and neither fails closed on ambiguous matches. A new writer is needed either way.
- **Scanner:** neither recurses. A new scanner is needed either way. Use `DocumentsContract` child queries (one query per directory) rather than `DocumentFile.listFiles()`, which costs extra queries per file.
- **Widget:** Todosian's Glance widget already has per-instance config and toggling. Obsidian Widget's is older and on the deprecated API.

## Decision

**Lean new app; don't fork either repo.** Obsidian Widget can't be used because it has no licence file. Forking Todosian would carry about 9.3k lines of UI built around categories that we'd mostly delete. In Phase 1, copy Todosian's `MarkdownParser` (and later `RecurrenceEngine`) under Apache-2.0, adding its `LICENSE` and `NOTICE`.

## Prototype (`app/`)

Toolchain: Gradle 9.8.2, AGP 9.5.0 (Kotlin built in), compileSdk/targetSdk 37, minSdk 31. No AndroidX dependencies. Build with `JAVA_HOME` set to Temurin 17 (Gradle 9.8.2 also needs a JDK 17+ toolchain).

- `Tasks.kt` is pure Kotlin with unit tests at the three agreed seams:
  - `parseTasks`: tasks plus their source line. Handles `-`/`*`/`+`/`1.`/`1)` lists, blockquotes and nesting, and skips tasks inside code fences.
  - `completeTask`: finds the task at the only line in the note that exactly matches its original text, so the cached line number is never trusted on its own (N-12). Returns `Conflict` when no line or more than one line matches, and `Unsupported` for 🔁 tasks or tasks that aren't open. Changes only the checkbox and adds `✅ date` (before any `^block-id`). Keeps CRLF and the trailing newline. **Limitation:** a task whose text appears twice in the same note can't be completed from the widget. Phase 1 could tell duplicates apart by their neighbouring lines.
  - `completeInNote`: re-reads the note just before writing and refuses if it changed.
- `Vault.kt` handles SAF: a persisted tree grant and a recursive walk with one `DocumentsContract` query per folder (dot-folders such as `.obsidian` and `.trash` are skipped, an early slice of V-06; a note that can't be read is skipped and logged rather than failing the whole scan). It reads strict UTF-8 and writes with `"wt"`.
- `TasksWidget.kt` is a RemoteViews widget using `RemoteCollectionItems` (API 31+), so there's no `RemoteViewsService` and no Glance. It lists every open task; tapping a row completes it. The refresh action lives in the header. Completion goes through a non-exported receiver, so other apps can't trigger writes.

### Emulator results (Pixel 10 Pro XL, API 37, §7 vault pushed to `Documents/Vault`)

After the code-review fixes, AT-01 and AT-06 were rerun on the emulator and still pass.

| Test | Result |
|---|---|
| AT-01 | Pass. All 5 tasks found across `Projects/` and `Personal/Household/`, each with its path and line. A task in `.obsidian/` was skipped. |
| AT-06 | Pass. Completing Review preprint changed only that line (`- [x] … ✅ 2026-11-02`), and the widget refreshed. |
| AT-07 | Pass, both cases. (a) The task line was edited externally (due date changed in a CRLF note) → `Conflict` and the file stayed byte-identical. (b) Lines were inserted above the task → the task was found at its new line and completed. CRLF was preserved on every line. |
| AT-08 | Pass. Tapping Weekly review (🔁) → `Unsupported` with a toast, and nothing was written. |
| AT-09 | Pass (approximated by moving the vault folder away) → "Can't read vault (…). Open Vault Tasks to choose it again." |

**Widget technology:** RemoteViews with `RemoteCollectionItems` was enough, and it needs no service or Compose. I recommend staying on it rather than adopting Glance.

**Remaining write risks:**
- SAF has no compare-and-swap. A sync write that lands between the second read and our write can still be overwritten, but that window is milliseconds. On the S26+ we need to test whether Obsidian Sync ever hits it.
- `"wt"` truncates the note before writing. If the write fails partway (provider error, process killed), the note can be left empty or cut short. Writing to a sibling temp file and then renaming isn't possible through every provider. Phase 1 should at least keep a copy of the original in app storage until the write is confirmed.

## Still to do on the Galaxy S26+ (needs you)

1. In Obsidian, open the vault switcher and note where the vault lives. If it's under `Documents/` (or another shared-storage folder), SAF can reach it. If it's in Obsidian's app-private storage, SAF can't, and we need to rethink storage (§10).
2. Build and install: `JAVA_HOME=$(/usr/libexec/java_home -v 17) ./gradlew :app:installDebug` (Samsung Auto Blocker may need turning off first).
3. Open Vault Tasks → **Choose vault** → choose the vault folder. Check that the scan list contains tasks from nested notes, and that nothing is listed from `.obsidian`.
4. Add the Vault Tasks widget to the Samsung home screen. Check that it renders and scrolls.
5. Tap a non-recurring task. In Obsidian, check that only that line changed, and that it syncs to desktop cleanly.
6. Conflict: edit a task on desktop and let it sync to the phone without refreshing the widget. Tap that task → expect a "Note changed…" toast and the desktop edit kept.
7. Concurrency: tap a task while Obsidian Sync is actively pulling a change to the same note. Check for lost edits or sync-conflict files.
