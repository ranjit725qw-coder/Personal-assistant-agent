package com.jarves.mh.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

private const val MAX_API_KEYS = 10

/**
 * Itemized API-key pool editor: one input box per key (Key 1, Key 2, …),
 * a per-row delete icon, and an "+ Add API Key" button that stays disabled
 * until every existing box is filled. Serializes back to one-key-per-line
 * text, which ApiKeyPool parses (keys auto-switch on 429 rate limit).
 */
@Composable
fun ApiKeyListEditor(
    value: String,
    onValueChange: (String) -> Unit,
    keyVisible: Boolean,
    onToggleVisibility: (() -> Unit)?,
    hasStoredSecret: Boolean = false,
    modifier: Modifier = Modifier,
) {
    val rows = remember(value) { if (value.isEmpty()) listOf("") else value.lines() }
    fun commit(newRows: List<String>) {
        onValueChange(if (newRows.isEmpty()) "" else newRows.joinToString("\n"))
    }
    val filledCount = rows.count { it.isNotBlank() }
    val hasBlank = rows.any { it.isBlank() }
    val canAdd = !hasBlank && rows.size < MAX_API_KEYS

    Column(modifier = modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(
            Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(
                if (filledCount > 0) "API keys ($filledCount)" else "API keys",
                fontSize = 12.sp,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (onToggleVisibility != null) {
                IconButton(onClick = onToggleVisibility, modifier = Modifier.size(32.dp)) {
                    Icon(
                        if (keyVisible) Icons.Default.VisibilityOff else Icons.Default.Visibility,
                        "Show or hide keys",
                        modifier = Modifier.size(20.dp),
                    )
                }
            }
        }
        rows.forEachIndexed { index, row ->
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(
                    value = row,
                    onValueChange = { newText -> commit(rows.toMutableList().also { it[index] = newText }) },
                    label = { Text("Key ${index + 1}") },
                    placeholder = {
                        if (index == 0 && hasStoredSecret && value.isBlank()) {
                            Text("Saved securely — leave blank, or paste new keys")
                        } else {
                            Text("Paste API key ${index + 1}")
                        }
                    },
                    singleLine = true,
                    visualTransformation = if (keyVisible) VisualTransformation.None else PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                    modifier = Modifier.weight(1f),
                )
                IconButton(
                    onClick = { commit(rows.toMutableList().also { it.removeAt(index) }) },
                    enabled = !(rows.size == 1 && row.isBlank()),
                ) {
                    Icon(Icons.Default.Delete, "Remove Key ${index + 1}")
                }
            }
        }
        val footer = when {
            rows.size >= MAX_API_KEYS -> "Maximum $MAX_API_KEYS keys"
            hasBlank -> "Fill in the empty key box before adding another"
            filledCount > 1 -> "$filledCount keys • switches automatically on rate limit"
            else -> null
        }
        footer?.let { Text(it, fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        OutlinedButton(onClick = { commit(rows + "") }, enabled = canAdd, modifier = Modifier.fillMaxWidth()) {
            Icon(Icons.Default.Add, null, Modifier.size(18.dp))
            Spacer(Modifier.width(7.dp))
            Text("Add API Key")
        }
    }
}
