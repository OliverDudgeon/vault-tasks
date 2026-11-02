# Phase 3: advanced Tasks compatibility

Covers T-09 (recurrence), alternative statuses, richer queries (Q-09, Q-10), widget customisation (W-07) and inline Markdown display (part of T-07). It also parses the T-06 dates, because the queries need them. Phase 2 hasn't been done as a whole. Only the parts that Phase 3 depends on are included.

Behaviour follows the obsidian-tasks source on `main` (2 November 2026): `Recurrence.ts`, `Occurrence.ts`, `Task.ts` (`handleNewStatus`, `createNextOccurrence`), `OnCompletion.ts`, `DateField.ts`, `DateParser.ts` and `DateRange.ts`. The monthly and yearly cases in `tests/Task/Recurrence.test.ts` are repeated in `CompleteTaskTest`.

## How it fits together

- `Recurrence.kt`: `nextOccurrence` handles a subset of the rrule text that the plugin accepts:
  - `every [N] day|week|month|year[s]` and `every weekday`
  - `every [N] week[s] on <days>` and `every <days>`
  - `every [N] month[s] on the <Nth>|last`
  - any of these with `when done`

  The next date comes after the reference date (due, then scheduled, then start), or after today for `when done`. The other dates keep their distance from it. When a month is too short, monthly and yearly rules move to its last day, as the plugin does. A rule outside the subset, or a malformed date on a recurring task, still gives `Unsupported`. In that case the plugin would silently drop the recurrence.
- `Tasks.kt`: completing a recurring task writes the next occurrence on the line above, which is the plugin's default. The new line keeps the original text and order. It drops ✅, ❌ and ➕ (`setCreatedDate` defaults to off), 🆔, ⛔ and the block id, as `createNextOccurrence` does. `🏁 delete` removes the completed line. Fields are peeled at their positions in the line, so the dates can be rewritten in place. Parsing now also reads 🛫, ➕, ✅ and ❌, the 🔁 rule text and tags. It no longer misses fields when a block id has trailing whitespace.
- Statuses: `Task.type` gives the plugin's default types. `/` is in progress, `-` is cancelled and `x`/`X` is done. Unknown symbols are to-do, as in the plugin. The widget shows ☐ ◐ ☑ ☒. To-do and in-progress tasks can be completed. A custom symbol is refused (`Unsupported`), because the vault may define it as done or cancelled and completing it would overwrite that.
- `Query.kt`: a subset of the Tasks query language, which goes in a filter's new *Advanced* box. Every line must match, on top of the guided choices. The subset is listed on `parseQuery`. It includes boolean `AND`/`OR`/`XOR`/`NOT` with nesting, using the plugin's precedence (NOT, then XOR, then AND, then OR), every date field with ranges and relative dates, priority, status type, recurrence, tags, description, path, filename, folder and root, plus `sort by` and `limit`. Layout lines (`hide`, `show`, `group by`, …) are skipped. Any other line is an error. The editor shows the error as it's typed, and so does the widget, which never falls back to showing everything. The editor also shows a live count of matching tasks (Q-10).
- `InlineMarkdown.kt`: bold, italic, bold italic, strikethrough, highlight and code. Links show their text and wikilinks show as Obsidian shows them (`[[Note#Plans]]` → "Note > Plans", aliases win). Done and cancelled tasks are struck through. The note is never changed.
- Widget look (W-07), per widget, in its configure screen: show priority and dates, show note path, compact rows, and a system/light/dark theme. The theme uses day/night colours, so the widget follows dark mode. Before this it was always light.

## Emulator results (Pixel 10 Pro XL, API 37, 2 November 2026)

| Check | Result |
|---|---|
| Inline Markdown | Pass. Bold, italic, strikethrough, highlight, code, `[[…\|alias]]`, `[[Plan#Budget]]` and `[site](…)` all render in the widget. |
| Recurring completion (AT-08 now) | Pass. Completing `Water plants 🔁 every 3 days when done ⏳ 2026-11-01 📅 2026-11-02 ^water` wrote `- [ ] … ⏳ 2026-11-04 📅 2026-11-05` above the completed line. The block id stayed only on the completed line. |
| Status glyphs | Pass. `[/]` shows ◐ and `[-]` is hidden by "open tasks". |
| Widget look | Pass. Dark + compact applied to one widget only. |
| Editor | Pass. `heading includes x` shows the error. `tags include #research` shows "Matches 1 of 12 tasks". |

## Not done

- **Custom statuses** from the plugin's settings (`.obsidian/plugins/obsidian-tasks-plugin/data.json`) aren't read, so a custom symbol is filtered as to-do but can't be completed from the widget. The same goes for its `recurrenceOnNextLine`, `removeScheduledDateOnRecurrence`, `setCreatedDate` and global filter settings, which use the defaults.
- **Recurrence rules** outside the subset (`on the 2nd Tuesday`, `every year on 5 March`, `until`, `for N times`) fail closed. They can be completed in Obsidian.
- **Queries:** `heading` filters (no heading context is kept, per the Phase 1 decision), `filter by function`, `sort by urgency`, chrono's natural-language dates ("next friday"), and `[]`/`{}`/`""` boolean delimiters.
- **Q-10** shows a live count, not a list of the matching tasks. **W-09:** the newer filters (tags, scheduled and start dates, status type) are reachable only through the query box, not the guided controls.
- **Links** are styled but can't be tapped, because a widget list row takes one tap target per view.
