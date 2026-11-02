package com.vaulttasks

import android.app.Activity
import android.appwidget.AppWidgetManager
import android.content.Intent
import android.os.Bundle
import android.widget.ArrayAdapter
import android.widget.ListView

/** Picks the saved filter a widget shows (W-02). Shown when a widget is added, reconfigured or has no filter. */
class WidgetConfigActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val widgetId = intent.getIntExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, AppWidgetManager.INVALID_APPWIDGET_ID)
        val result = Intent().putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, widgetId)
        setResult(RESULT_CANCELED, result)
        if (widgetId == AppWidgetManager.INVALID_APPWIDGET_ID) return finish()

        title = "Show tasks matching…"
        val filters = Store.filters(this)
        setContentView(ListView(this).apply {
            fitsSystemWindows = true
            adapter = ArrayAdapter(context, android.R.layout.simple_list_item_1, filters.map { it.name })
            setOnItemClickListener { _, _, position, _ ->
                Store.setWidgetFilter(context, widgetId, filters[position].id)
                RefreshJob.request(context)
                setResult(RESULT_OK, result)
                finish()
            }
        })
    }
}
