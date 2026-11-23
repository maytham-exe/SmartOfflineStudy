package com.smartstudy.offline

import android.app.AlertDialog
import android.content.Context
import android.widget.ArrayAdapter

object AlertDialogBuilder {
    fun show(
        context: Context,
        question: String,
        choices: List<String>,
        correct: String,
        done: (String) -> Unit
    ) {
        AlertDialog.Builder(context)
            .setTitle("اختبار سريع")
            .setMessage(question)
            .setAdapter(ArrayAdapter(context, android.R.layout.simple_list_item_1, choices)) { _, which ->
                done(choices[which])
            }
            .setNegativeButton("إغلاق", null)
            .show()
    }

    fun showFilterPicker(
        context: Context,
        options: Array<String>,
        onSelect: (Int, String) -> Unit
    ) {
        AlertDialog.Builder(context)
            .setTitle("فلترة المحفوظات حسب النوع")
            .setItems(options) { _, which -> onSelect(which, options[which]) }
            .setNegativeButton("إلغاء", null)
            .show()
    }
}
