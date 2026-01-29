package com.synapse.ui.components

import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Alarm
import androidx.compose.material.icons.filled.Event
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.unit.dp
import com.synapse.model.IntentData
import com.synapse.model.IntentType
import com.synapse.api.TranscribedNote
import kotlinx.coroutines.delay

@Composable
fun IntentConfirmationDialog(
    noteText: String,
    suggestedType: IntentType,
    onConfirm: (IntentType) -> Unit,
    onDismiss: () -> Unit
) {
    var dismissed by remember { mutableStateOf(false) }
    if (dismissed) return

    AlertDialog(
        onDismissRequest = {
            dismissed = true
            onDismiss()
        },
        title = {
            Text(
                when (suggestedType) {
                    IntentType.TASK -> "Is this a task?"
                    IntentType.QUESTION -> "Is this a question?"
                    IntentType.REMINDER -> "Is this a reminder?"
                    else -> "Classify this note"
                }
            )
        },
        text = {
            Text(
                noteText.take(100) + if (noteText.length > 100) "..." else ""
            )
        },
        confirmButton = {
            TextButton(onClick = {
                dismissed = true
                onConfirm(suggestedType)
            }) {
                Text(
                    when (suggestedType) {
                        IntentType.TASK -> "Yes, it's a task"
                        IntentType.QUESTION -> "Yes, it's a question"
                        IntentType.REMINDER -> "Yes, it's a reminder"
                        else -> "Confirm"
                    }
                )
            }
        },
        dismissButton = {
            TextButton(onClick = {
                dismissed = true
                onConfirm(IntentType.NOTE)
            }) {
                Text("No, just a note")
            }
        }
    )

    LaunchedEffect(Unit) {
        delay(5000)
        if (!dismissed) {
            dismissed = true
            onConfirm(IntentType.NOTE)
        }
    }
}

@Composable
fun QuestionAnswerDialog(
    question: String,
    answer: String,
    onSaveBoth: () -> Unit,
    onSaveQuestionOnly: () -> Unit,
    onDiscard: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDiscard,
        title = { Text("Question Detected") },
        text = {
            Column {
                Text("Your question:", style = MaterialTheme.typography.labelMedium)
                Text(question)

                Spacer(modifier = Modifier.height(16.dp))

                Text("Answer:", style = MaterialTheme.typography.labelMedium)
                Text(answer, fontStyle = FontStyle.Italic)
            }
        },
        confirmButton = {
            TextButton(onClick = onSaveBoth) {
                Text("Save both")
            }
        },
        dismissButton = {
            Row {
                TextButton(onClick = onSaveQuestionOnly) {
                    Text("Question only")
                }
                TextButton(onClick = onDiscard) {
                    Text("Discard")
                }
            }
        }
    )
}

@Composable
fun ReminderDialog(
    reminderText: String,
    timeText: String?,
    onCreateAlarm: () -> Unit,
    onCreateCalendarEvent: () -> Unit,
    onSaveAsNote: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onSaveAsNote,
        title = { Text("Reminder Detected") },
        text = {
            Column {
                Text(reminderText)
                timeText?.let {
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        "Time: $it",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        },
        confirmButton = {
            Column {
                TextButton(onClick = onCreateAlarm) {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Icon(Icons.Default.Alarm, null, modifier = Modifier.size(18.dp))
                        Text("Create Alarm")
                    }
                }
                TextButton(onClick = onCreateCalendarEvent) {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Icon(Icons.Default.Event, null, modifier = Modifier.size(18.dp))
                        Text("Add to Calendar")
                    }
                }
            }
        },
        dismissButton = {
            TextButton(onClick = onSaveAsNote) {
                Text("Just save as note")
            }
        }
    )
}
