package com.vaulttasks

import android.appwidget.AppWidgetManager
import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.RadioGroup
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.ForegroundColorSpan
import android.text.style.RelativeSizeSpan
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.button.MaterialButtonToggleGroup
import com.google.android.material.color.MaterialColors
import com.google.android.material.materialswitch.MaterialSwitch
import com.google.android.material.radiobutton.MaterialRadioButton

/**
 * Picks the saved filter a widget shows (W-02) and how it looks (W-07). Shown when a widget is added,
 * reconfigured or has no filter.
 */
class WidgetConfigActivity : AppCompatActivity() {
    private var widgetId = AppWidgetManager.INVALID_APPWIDGET_ID
    private var filters = emptyList<Filter>()
    private val themes = listOf(R.id.theme_system, R.id.theme_light, R.id.theme_dark)
    private val groups = listOf(R.id.group_none, R.id.group_note, R.id.group_folder, R.id.group_agenda)
    private val groupText = listOf(
        "None" to "One list, in the filter's sort order.",
        "Note" to "Tasks under the note they're in, notes A to Z.",
        "Folder" to "Tasks under their note's folder, folders A to Z.",
        "Agenda" to "Only tasks with a due date, under the day they're due.",
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        widgetId = intent.getIntExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, AppWidgetManager.INVALID_APPWIDGET_ID)
        val result = Intent().putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, widgetId)
        setResult(RESULT_CANCELED, result)
        if (widgetId == AppWidgetManager.INVALID_APPWIDGET_ID) return finish()

        setContentView(R.layout.activity_widget_config)
        findViewById<MaterialToolbar>(R.id.toolbar).setNavigationOnClickListener { finish() }
        findViewById<View>(R.id.edit_filters).setOnClickListener { startActivity(Intent(this, MainActivity::class.java)) }
        val look = Store.look(this, widgetId)
        val dates = findViewById<MaterialSwitch>(R.id.show_dates).apply { isChecked = look.dates }
        val path = findViewById<MaterialSwitch>(R.id.show_path).apply { isChecked = look.path }
        val compact = findViewById<MaterialSwitch>(R.id.compact).apply { isChecked = look.compact }
        val theme = findViewById<MaterialButtonToggleGroup>(R.id.theme).apply { check(themes[look.theme.ordinal]) }
        val hint = MaterialColors.getColor(window.decorView, com.google.android.material.R.attr.colorOnSurfaceVariant)
        groups.zip(groupText).forEach { (id, text) ->
            findViewById<MaterialRadioButton>(id).text = SpannableStringBuilder(text.first).append("\n")
                .append(text.second, ForegroundColorSpan(hint), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                .apply { setSpan(RelativeSizeSpan(0.85f), length - text.second.length, length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE) }
        }
        val group = findViewById<RadioGroup>(R.id.group).apply { check(groups[look.group.ordinal]) }
        findViewById<View>(R.id.done).setOnClickListener {
            val choice = findViewById<RadioGroup>(R.id.filters).checkedRadioButtonId
            TasksWidget.applySettings(
                this, widgetId, filters.getOrNull(choice - 1)?.id,
                Look(
                    dates.isChecked, path.isChecked, compact.isChecked, Theme.entries[themes.indexOf(theme.checkedButtonId)],
                    GroupBy.entries[groups.indexOf(group.checkedRadioButtonId)],
                ),
            )
            setResult(RESULT_OK, result)
            finish()
        }
    }

    // Filters can be edited from here, so the list is rebuilt on return, keeping the choice.
    override fun onResume() {
        super.onResume()
        val group = findViewById<RadioGroup>(R.id.filters)
        val chosen = filters.getOrNull(group.checkedRadioButtonId - 1)?.id ?: Store.widgetFilter(this, widgetId)?.id
        filters = Store.filters(this)
        group.removeAllViews()
        filters.forEachIndexed { i, f ->
            group.addView(MaterialRadioButton(this).apply { id = i + 1; text = f.name; minHeight = (48 * resources.displayMetrics.density).toInt() })
        }
        group.check(filters.indexOfFirst { it.id == chosen }.coerceAtLeast(0) + 1)
    }
}
