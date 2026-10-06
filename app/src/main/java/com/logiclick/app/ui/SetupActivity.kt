package com.logiclick.app.ui

import android.app.Activity
import android.graphics.BitmapFactory
import android.os.Bundle
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.Toast
import com.logiclick.app.data.ConfigStore
import com.logiclick.app.model.WorkflowConfig

class SetupActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val capturePath = intent.getStringExtra(EXTRA_CAPTURE) ?: return finish()
        val bitmap = BitmapFactory.decodeFile(capturePath) ?: return finish()
        val store = ConfigStore(this)
        val existing = store.workflow

        val name = EditText(this).apply { hint = "Workflow name"; setText(existing?.name ?: "enter-number") }
        val packageName = EditText(this).apply {
            hint = "Allowed package (for example com.example.app)"
            setText(existing?.targetPackage ?: intent.getStringExtra(EXTRA_PACKAGE).orEmpty())
        }
        val regex = EditText(this).apply { hint = "OCR condition (regular expression)"; setText(existing?.expectedTextRegex ?: ".+") }
        val canvas = SetupOverlayView(this).apply {
            screenshot = bitmap
            ocrRegion = existing?.ocrRegion
            inputPoint = existing?.inputPoint
            submitPoint = existing?.submitPoint
        }
        val ocr = Button(this).apply { text = "1. Draw OCR region"; setOnClickListener { canvas.mode = SetupOverlayView.Mode.OCR_REGION } }
        val input = Button(this).apply { text = "2. Tap number field"; setOnClickListener { canvas.mode = SetupOverlayView.Mode.INPUT_POINT } }
        val submit = Button(this).apply { text = "3. Tap action button"; setOnClickListener { canvas.mode = SetupOverlayView.Mode.SUBMIT_POINT } }
        val save = Button(this).apply {
            text = "Save workflow"
            setOnClickListener {
                val config = runCatching {
                    WorkflowConfig(
                        name = name.text.toString().trim(),
                        targetPackage = packageName.text.toString().trim(),
                        ocrRegion = requireNotNull(canvas.ocrRegion),
                        inputPoint = requireNotNull(canvas.inputPoint),
                        submitPoint = requireNotNull(canvas.submitPoint),
                        expectedTextRegex = regex.text.toString().ifBlank { ".+" }
                    )
                }.getOrElse {
                    Toast.makeText(this@SetupActivity, "Set all three locations", Toast.LENGTH_LONG).show()
                    return@setOnClickListener
                }
                config.validate()?.let {
                    Toast.makeText(this@SetupActivity, it, Toast.LENGTH_LONG).show()
                    return@setOnClickListener
                }
                store.workflow = config
                setResult(RESULT_OK)
                finish()
            }
        }

        setContentView(LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(16, 16, 16, 16)
            addView(name)
            addView(packageName)
            addView(regex)
            addView(LinearLayout(this@SetupActivity).apply {
                orientation = LinearLayout.HORIZONTAL
                addView(ocr, LinearLayout.LayoutParams(0, -2, 1f))
                addView(input, LinearLayout.LayoutParams(0, -2, 1f))
                addView(submit, LinearLayout.LayoutParams(0, -2, 1f))
            })
            addView(canvas, LinearLayout.LayoutParams(-1, 0, 1f))
            addView(save)
        })
    }

    companion object {
        const val EXTRA_CAPTURE = "capture"
        const val EXTRA_PACKAGE = "package"
    }
}
