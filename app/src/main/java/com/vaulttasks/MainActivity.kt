package com.vaulttasks

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import kotlin.concurrent.thread

/** Chooses the vault and dumps the scan, so AT-01 can be checked by eye. */
class MainActivity : Activity() {
    private lateinit var output: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        output = TextView(this).apply { setTextIsSelectable(true) }
        val choose = Button(this).apply {
            text = "Choose vault"
            setOnClickListener { startActivityForResult(Intent(Intent.ACTION_OPEN_DOCUMENT_TREE), 0) }
        }
        setContentView(ScrollView(this).apply {
            fitsSystemWindows = true
            addView(LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                addView(choose)
                addView(output)
            })
        })
    }

    override fun onResume() {
        super.onResume()
        showScan()
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        val tree = data?.data ?: return
        Vault.choose(this, tree)
        thread { TasksWidget.refreshAll(this) }
    }

    private fun showScan() {
        val tree = Vault.tree(this) ?: return output.setText("No vault chosen.")
        output.text = "Scanning the vault…"
        thread {
            val text = try {
                Vault.scan(this, tree).joinToString("\n") { (path, task) -> "$path:${task.line + 1}  ${task.text}" }
                    .ifEmpty { "No tasks found." }
            } catch (e: Exception) {
                "Can't read vault: $e"
            }
            runOnUiThread { output.text = "$tree\n\n$text" }
        }
    }
}
