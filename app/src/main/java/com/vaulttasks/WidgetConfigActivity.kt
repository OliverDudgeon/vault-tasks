package com.vaulttasks

import android.app.Activity
import android.appwidget.AppWidgetManager
import android.content.Intent
import android.os.Bundle
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.CheckBox
import android.widget.LinearLayout
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.ScrollView
import android.widget.Spinner
import android.widget.TextView

/**
 * Picks the saved filter a widget shows (W-02) and how it looks (W-07). Shown when a widget is added,
 * reconfigured or has no filter.
 */
class WidgetConfigActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val widgetId = intent.getIntExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, AppWidgetManager.INVALID_APPWIDGET_ID)
        val result = Intent().putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, widgetId)
        setResult(RESULT_CANCELED, result)
        if (widgetId == AppWidgetManager.INVALID_APPWIDGET_ID) return finish()

        title = "Widget"
        val filters = Store.filters(this)
        val current = Store.widgetFilter(this, widgetId)
        val look = Store.look(this, widgetId)
        val choice = RadioGroup(this).apply {
            filters.forEachIndexed { i, f -> addView(RadioButton(context).apply { id = i + 1; text = f.name }) }
            check(filters.indexOfFirst { it.id == current?.id }.coerceAtLeast(0) + 1)
        }
        fun box(text: String, checked: Boolean) = CheckBox(this).apply { this.text = text; isChecked = checked }
        val dates = box("Show priority and dates", look.dates)
        val path = box("Show the note's path", look.path)
        val compact = box("Compact rows", look.compact)
        val theme = Spinner(this).apply {
            adapter = ArrayAdapter(context, android.R.layout.simple_spinner_dropdown_item, listOf("Follow the system theme", "Light", "Dark"))
            setSelection(look.theme.ordinal)
        }
        val done = Button(this).apply {
            text = "Done"
            setOnClickListener {
                filters.getOrNull(choice.checkedRadioButtonId - 1)?.let { Store.setWidgetFilter(context, widgetId, it.id) }
                Store.setLook(context, widgetId, Look(dates.isChecked, path.isChecked, compact.isChecked, Theme.entries[theme.selectedItemPosition]))
                RefreshJob.request(context)
                setResult(RESULT_OK, result)
                finish()
            }
        }
        setContentView(ScrollView(this).apply {
            fitsSystemWindows = true
            addView(LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(32, 32, 32, 32)
                addView(TextView(context).apply { text = "Show tasks matching…"; textSize = 18f })
                addView(choice)
                addView(TextView(context).apply { text = "\nAppearance"; textSize = 18f })
                listOf(dates, path, compact, theme, done).forEach(::addView)
            })
        })
    }
}
