package com.vaulttasks

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.content.SharedPreferences
import android.net.Uri
import android.os.Bundle
import android.text.Editable
import android.text.InputType
import android.text.TextWatcher
import android.view.View
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Spinner
import android.widget.TextView
import java.time.LocalDate

/** Chooses the vault, shows the last scan, and edits the saved filters that widgets show. */
class MainActivity : Activity() {
    private lateinit var status: TextView
    private lateinit var filterList: LinearLayout
    private val onStatus = SharedPreferences.OnSharedPreferenceChangeListener { _, key -> if (key == Store.STATUS) showStatus() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        status = TextView(this).apply { setTextIsSelectable(true) }
        filterList = column()
        setContentView(ScrollView(this).apply {
            fitsSystemWindows = true
            addView(column().apply {
                setPadding(32, 32, 32, 32)
                addView(button("Choose vault") { startActivityForResult(Intent(Intent.ACTION_OPEN_DOCUMENT_TREE), 0) })
                addView(status)
                addView(TextView(context).apply { text = "\nFilters"; textSize = 18f })
                addView(filterList)
                addView(button("Add filter") { edit(null) })
            })
        })
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

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        val tree = data?.data ?: return
        Vault.choose(this, tree)
        Store.setStatus(this, "Scanning the vault…")
    }

    private fun showStatus() {
        val tree = Vault.tree(this)
        val backups = Vault.interruptedWrites(this).joinToString("") {
            "\n\nA task completion was interrupted. The note's original text is saved as ${it.path}"
        }
        status.text = if (tree == null) "No vault chosen." else "Vault: ${Uri.decode(tree.lastPathSegment)}\n${Store.status(this) ?: ""}$backups"
    }

    private fun showFilters() {
        filterList.removeAllViews()
        for (filter in Store.filters(this)) filterList.addView(button(filter.name) { edit(filter) })
    }

    /** The filter editor (Q-03 to Q-10). Date choices are spelled out, per the spec's date semantics. */
    private fun edit(existing: Filter?) {
        val f = existing ?: Filter("New filter")
        val due = f.due
        val priority = f.priority
        val levels = Priority.entries.reversed()
        val name = EditText(this).apply { setText(f.name); hint = "Name" }
        val statusChoice = spinner(f.status.ordinal, "Open tasks", "Done or cancelled tasks", "Tasks of any status")
        val dueKind = spinner(
            when {
                due == null -> 0
                due == Due.OVERDUE -> 1
                due == Due.TODAY -> 2
                due == Due.ON_OR_BEFORE_TODAY -> 3
                due.from == 0 -> 4
                else -> 5
            },
            "Any due date, or none", "Overdue (before today)", "Due today", "Due today or overdue",
            "Due from today to N days ahead", "Due within N days ahead, or overdue",
        )
        val days = EditText(this).apply {
            inputType = InputType.TYPE_CLASS_NUMBER
            hint = "N days"
            setText("${due?.to?.takeIf { it > 0 } ?: 7}")
        }
        val kind = when {
            priority == null -> 0
            priority.start == priority.endInclusive -> 1
            priority.endInclusive == Priority.HIGHEST -> 2
            else -> 3
        }
        val priorityKind = spinner(kind, "Any priority", "Priority exactly", "Priority at least", "Priority at most")
        val level = spinner(
            levels.indexOf((if (kind == 3) priority?.endInclusive else priority?.start) ?: Priority.HIGH),
            *levels.map { "${it.name.lowercase().replaceFirstChar(Char::uppercase)} ${it.emoji}" }.toTypedArray(),
        )
        val anyOf = spinner(if (f.anyOf) 1 else 0, "Match the due date AND the priority", "Match the due date OR the priority")
        val sort = spinner(f.sort.ordinal, "Sort by due date", "Sort by priority")
        val query = EditText(this).apply {
            setText(f.query)
            hint = "Advanced: Tasks query lines, all of which must match, e.g.\ntags include #work\n(due today) OR (priority is high)"
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
            minLines = 2
        }
        val preview = TextView(this).apply { setPadding(0, 16, 0, 16) }
        fun build(): Filter {
            val n = days.text.toString().toIntOrNull() ?: 7
            val p = levels[level.selectedItemPosition]
            return f.copy(
                name = name.text.toString().ifBlank { "Untitled filter" },
                status = Status.entries[statusChoice.selectedItemPosition],
                due = listOf(null, Due.OVERDUE, Due.TODAY, Due.ON_OR_BEFORE_TODAY, Due.next(n), Due.next(n, includeOverdue = true))[dueKind.selectedItemPosition],
                priority = listOf(null, p..p, p..Priority.HIGHEST, Priority.LOWEST..p)[priorityKind.selectedItemPosition],
                anyOf = anyOf.selectedItemPosition == 1,
                sort = Sort.entries[sort.selectedItemPosition],
                query = query.text.toString().trim(),
            )
        }
        // Live match count against the last scan (Q-10), which also shows query errors as they're typed.
        val notes = Store.index(this)
        val tasks = notes.values.flatMap { it.tasks }
        fun showPreview() {
            preview.text = runCatching { build().select(tasks, LocalDate.now()) { notes[it.file]?.path ?: it.file } }
                .fold({ "Matches ${it.size} of ${tasks.size} tasks" }, { it.message })
        }
        val onChange = object : TextWatcher, AdapterView.OnItemSelectedListener {
            override fun afterTextChanged(s: Editable?) = showPreview()
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) = showPreview()
            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }
        listOf(days, query).forEach { it.addTextChangedListener(onChange) }
        listOf(statusChoice, dueKind, priorityKind, level, anyOf, sort).forEach { it.onItemSelectedListener = onChange }
        showPreview()

        val dialog = AlertDialog.Builder(this)
            .setTitle(if (existing == null) "New filter" else "Edit filter")
            .setView(ScrollView(this).apply {
                addView(column().apply {
                    setPadding(48, 16, 48, 0)
                    listOf(name, statusChoice, dueKind, days, priorityKind, level, anyOf, sort, query, preview).forEach(::addView)
                })
            })
            .setPositiveButton("Save") { _, _ -> save(existing, build()) }
            .setNegativeButton("Cancel", null)
        if (existing != null) dialog.setNeutralButton("Delete") { _, _ -> save(existing, null) }
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

    private fun column() = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }

    private fun button(text: String, onClick: () -> Unit) = Button(this).apply {
        this.text = text
        isAllCaps = false
        setOnClickListener { onClick() }
    }

    private fun spinner(selected: Int, vararg options: String) = Spinner(this).apply {
        adapter = ArrayAdapter(context, android.R.layout.simple_spinner_dropdown_item, options)
        setSelection(selected)
    }
}
