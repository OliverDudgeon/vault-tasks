package com.vaulttasks

import android.content.SharedPreferences
import android.net.Uri
import android.os.Bundle
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.core.widget.doAfterTextChanged
import com.google.android.material.button.MaterialButtonToggleGroup
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.textfield.MaterialAutoCompleteTextView
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout
import java.time.LocalDate

/** Chooses the vault, shows the last scan, and edits the saved filters that widgets show. */
class MainActivity : AppCompatActivity() {
    private val onStatus = SharedPreferences.OnSharedPreferenceChangeListener { _, key -> if (key == Store.STATUS) showStatus() }
    private val chooseVault = registerForActivityResult(ActivityResultContracts.OpenDocumentTree()) { tree ->
        if (tree != null) {
            Vault.choose(this, tree)
            Store.setStatus(this, "Scanning the vault…")
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        findViewById<View>(R.id.choose_vault).setOnClickListener { chooseVault.launch(null) }
        findViewById<View>(R.id.add_filter).setOnClickListener { edit(null) }
    }

    override fun onResume() {
        super.onResume()
        Store.prefs(this).registerOnSharedPreferenceChangeListener(onStatus)
        showStatus()
        showFilters()
        RefreshJob.request(this)
    }

    override fun onPause() {
        super.onPause()
        Store.prefs(this).unregisterOnSharedPreferenceChangeListener(onStatus)
    }

    private fun showStatus() {
        val tree = Vault.tree(this)
        findViewById<TextView>(R.id.vault_name).text = tree?.let { Uri.decode(it.lastPathSegment).substringAfterLast(':').substringAfterLast('/') }
            ?: "None chosen"
        findViewById<TextView>(R.id.status).text = if (tree == null) "Choose your Obsidian vault folder to show its tasks." else Store.status(this).orEmpty()
        findViewById<TextView>(R.id.choose_vault).text = if (tree == null) "Choose vault" else "Change vault"
        val backups = Vault.interruptedWrites(this)
        findViewById<View>(R.id.backups).visibility = if (backups.isEmpty()) View.GONE else View.VISIBLE
        findViewById<TextView>(R.id.backups_text).text = backups.joinToString("\n\n") {
            "A task completion was interrupted. The note's original text is saved as ${it.path}"
        }
    }

    private fun showFilters() {
        val list = findViewById<LinearLayout>(R.id.filters)
        list.removeAllViews()
        for (filter in Store.filters(this)) {
            list.addView(layoutInflater.inflate(R.layout.item_filter, list, false).apply {
                findViewById<TextView>(R.id.name).text = filter.name
                findViewById<TextView>(R.id.summary).text = summary(filter)
                setOnClickListener { edit(filter) }
            })
        }
    }

    /** The filter editor (Q-03 to Q-10). Date choices are spelled out, per the spec's date semantics. */
    private fun edit(existing: Filter?) {
        val f = existing ?: Filter("New filter")
        val due = f.due
        val priority = f.priority
        val levels = Priority.entries.reversed()
        val view = layoutInflater.inflate(R.layout.dialog_filter, null)
        val name = view.findViewById<TextInputEditText>(R.id.name).apply { setText(f.name) }
        val status = Choice(view, R.id.status, listOf("Open tasks", "Done or cancelled tasks", "Tasks of any status"), f.status.ordinal)
        val dueKind = Choice(
            view, R.id.due,
            listOf(
                "Any due date, or none", "Overdue (before today)", "Due today", "Due today or overdue",
                "Due from today to N days ahead", "Due within N days ahead, or overdue",
            ),
            when {
                due == null -> 0
                due == Due.OVERDUE -> 1
                due == Due.TODAY -> 2
                due == Due.ON_OR_BEFORE_TODAY -> 3
                due.from == 0 -> 4
                else -> 5
            },
        )
        val days = view.findViewById<TextInputEditText>(R.id.days).apply { setText("${due?.to?.takeIf { it > 0 } ?: 7}") }
        val kind = when {
            priority == null -> 0
            priority.start == priority.endInclusive -> 1
            priority.endInclusive == Priority.HIGHEST -> 2
            else -> 3
        }
        val priorityKind = Choice(view, R.id.priority_kind, listOf("Any priority", "Priority exactly", "Priority at least", "Priority at most"), kind)
        val level = Choice(
            view, R.id.level,
            levels.map { "${it.name.lowercase().replaceFirstChar(Char::uppercase)} ${it.emoji}".trimEnd() },
            levels.indexOf((if (kind == 3) priority?.endInclusive else priority?.start) ?: Priority.HIGH),
        )
        val match = view.findViewById<MaterialButtonToggleGroup>(R.id.match).apply { check(if (f.anyOf) R.id.match_any else R.id.match_all) }
        val sort = view.findViewById<MaterialButtonToggleGroup>(R.id.sort).apply { check(if (f.sort == Sort.DUE) R.id.sort_due else R.id.sort_priority) }
        val query = view.findViewById<TextInputEditText>(R.id.query).apply { setText(f.query) }
        val queryLayout = view.findViewById<TextInputLayout>(R.id.query_layout)

        fun build(): Filter {
            val n = days.text.toString().toIntOrNull() ?: 7
            val p = levels[level.index]
            return f.copy(
                name = name.text.toString().ifBlank { "Untitled filter" },
                status = Status.entries[status.index],
                due = listOf(null, Due.OVERDUE, Due.TODAY, Due.ON_OR_BEFORE_TODAY, Due.next(n), Due.next(n, includeOverdue = true))[dueKind.index],
                priority = listOf(null, p..p, p..Priority.HIGHEST, Priority.LOWEST..p)[priorityKind.index],
                anyOf = match.checkedButtonId == R.id.match_any,
                sort = if (sort.checkedButtonId == R.id.sort_priority) Sort.PRIORITY else Sort.DUE,
                query = query.text.toString().trim(),
            )
        }
        // Live match count against the last scan (Q-10), which also shows query errors as they're typed.
        val notes = Store.index(this)
        val tasks = notes.values.flatMap { it.tasks }
        fun update() {
            view.findViewById<View>(R.id.days_layout).visibility = if (dueKind.index >= 4) View.VISIBLE else View.GONE
            view.findViewById<View>(R.id.level_layout).visibility = if (priorityKind.index > 0) View.VISIBLE else View.GONE
            val both = if (dueKind.index > 0 && priorityKind.index > 0) View.VISIBLE else View.GONE
            listOf(R.id.match, R.id.match_label).forEach { view.findViewById<View>(it).visibility = both }
            runCatching { build().select(tasks, LocalDate.now()) { notes[it.file]?.path ?: it.file } }.fold(
                { queryLayout.error = null; queryLayout.helperText = "Matches ${it.size} of ${tasks.size} tasks" },
                { queryLayout.error = it.message },
            )
        }
        listOf(status, dueKind, priorityKind, level).forEach { it.onChange(::update) }
        listOf(days, query).forEach { it.doAfterTextChanged { update() } }
        listOf(match, sort).forEach { it.addOnButtonCheckedListener { _, _, _ -> update() } }
        update()

        val dialog = MaterialAlertDialogBuilder(this)
            .setTitle(if (existing == null) "New filter" else "Edit filter")
            .setView(view)
            .setPositiveButton("Save") { _, _ -> save(existing, build()) }
            .setNegativeButton("Cancel", null)
        if (existing != null) dialog.setNeutralButton("Delete") { _, _ ->
            MaterialAlertDialogBuilder(this)
                .setTitle("Delete “${existing.name}”?")
                .setMessage("Widgets showing it will ask you to choose another filter.")
                .setPositiveButton("Delete") { _, _ -> save(existing, null) }
                .setNegativeButton("Cancel", null)
                .show()
        }
        dialog.show()
    }

    /** Replaces [old] with [new]; null [old] adds, null [new] deletes. Widgets keep pointing at the filter's id. */
    private fun save(old: Filter?, new: Filter?) {
        val filters = Store.filters(this).toMutableList()
        val i = filters.indexOfFirst { it.id == old?.id }
        when {
            new == null -> if (i >= 0) filters.removeAt(i)
            i >= 0 -> filters[i] = new
            else -> filters += new
        }
        Store.saveFilters(this, filters)
        showFilters()
        RefreshJob.request(this)
    }

    /** One line saying what a filter selects, for its card. */
    private fun summary(f: Filter): String {
        fun level(p: Priority) = p.name.lowercase()
        val due = f.due?.let { d ->
            when {
                d == Due.OVERDUE -> "overdue"
                d == Due.TODAY -> "due today"
                d == Due.ON_OR_BEFORE_TODAY -> "due today or overdue"
                d.from == null -> "due within ${d.to} days or overdue"
                else -> "due within ${d.to} days"
            }
        }
        val priority = f.priority?.let { p ->
            when {
                p.start == p.endInclusive -> "${level(p.start)} priority"
                p.endInclusive == Priority.HIGHEST -> "${level(p.start)} priority or higher"
                else -> "${level(p.endInclusive)} priority or lower"
            }
        }
        val status = listOf("Open", "Done or cancelled", "Any status")[f.status.ordinal]
        val conditions = listOfNotNull(due, priority).joinToString(if (f.anyOf) " or " else " and ")
        return listOfNotNull(status, conditions.ifEmpty { null }, "query".takeIf { f.query.isNotBlank() }).joinToString(" · ")
    }
}

/** An exposed dropdown menu over [options], starting at [selected]. */
private class Choice(view: View, id: Int, private val options: List<String>, selected: Int) {
    private val menu = view.findViewById<MaterialAutoCompleteTextView>(id).apply {
        setSimpleItems(options.toTypedArray())
        setText(options[selected], false)
    }
    val index get() = options.indexOf(menu.text.toString()).coerceAtLeast(0)

    fun onChange(action: () -> Unit) = menu.setOnItemClickListener { _, _, _, _ -> action() }
}
