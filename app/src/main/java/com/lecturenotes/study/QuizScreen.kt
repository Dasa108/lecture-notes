package com.lecturenotes.study

import android.app.Application
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.lecturenotes.app
import com.lecturenotes.data.QuizAttempt
import com.lecturenotes.notes.QuizQuestion
import com.lecturenotes.notes.StudyNotes
import com.lecturenotes.notes.formatTimestamp
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import org.json.JSONObject

data class QuizUiState(
    val loading: Boolean = true,
    val title: String = "",
    val questions: List<QuizQuestion> = emptyList(),
    val index: Int = 0,
    /** The option picked for a multiple-choice question, or "shown" once a short answer is revealed. */
    val picked: String? = null,
    /** Whether the current answer counted as correct (self-graded for short answers). */
    val gradedCorrect: Boolean? = null,
    val correct: Int = 0,
    val finished: Boolean = false,
    val previousBest: Int? = null,
)

class QuizViewModel(application: Application, private val recordingId: Long) : AndroidViewModel(application) {
    private val db = application.app.db
    private val _state = MutableStateFlow(QuizUiState())
    val state = _state.asStateFlow()

    init {
        viewModelScope.launch { start() }
    }

    fun restart() = viewModelScope.launch { start() }

    private suspend fun start() {
        val notes = db.notes().getFor(recordingId)?.json
            ?.let { runCatching { StudyNotes.parse(JSONObject(it), Long.MAX_VALUE / 2, "") }.getOrNull() }
        val rec = db.recordings().get(recordingId)
        _state.value = QuizUiState(
            loading = false,
            title = rec?.title ?: "Quiz",
            // Shuffle question order each attempt so the student can't learn positions.
            questions = notes?.quiz.orEmpty().shuffled(),
        )
    }

    fun pick(option: String) = _state.update { s ->
        if (s.picked != null) return@update s
        val q = s.questions[s.index]
        val right = option.trim().equals(q.answer.trim(), ignoreCase = true)
        s.copy(picked = option, gradedCorrect = right, correct = s.correct + if (right) 1 else 0)
    }

    fun reveal() = _state.update { it.copy(picked = "shown") }

    /** For short-answer questions the student grades themselves after seeing the answer. */
    fun selfGrade(right: Boolean) = _state.update { s ->
        if (s.gradedCorrect != null) s else s.copy(gradedCorrect = right, correct = s.correct + if (right) 1 else 0)
    }

    fun next() = viewModelScope.launch {
        val s = _state.value
        if (s.index + 1 < s.questions.size) {
            _state.value = s.copy(index = s.index + 1, picked = null, gradedCorrect = null)
        } else {
            val best = db.quizAttempts().bestFor(recordingId)
            db.quizAttempts().insert(QuizAttempt(recordingId = recordingId, correct = s.correct, total = s.questions.size))
            _state.value = s.copy(finished = true, previousBest = best)
        }
    }
}

private val Correct = Color(0xFF2E7D32)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun QuizScreen(recordingId: Long, onBack: () -> Unit, onOpenLecture: (Long, Long?) -> Unit) {
    val application = LocalContext.current.applicationContext as Application
    val vm: QuizViewModel = viewModel(
        key = "quiz-$recordingId",
        factory = viewModelFactory { initializer { QuizViewModel(application, recordingId) } },
    )
    val s by vm.state.collectAsState()

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text("Quiz")
                        Text(s.title, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
                    }
                },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") } },
            )
        },
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding).padding(16.dp)) {
            when {
                s.loading -> Unit
                s.questions.isEmpty() -> Centered("This lecture's notes have no quiz yet.", "Back", onBack)
                s.finished -> Result(s, onRetry = { vm.restart() }, onBack = onBack)
                else -> Question(s, vm, onOpenLecture, recordingId)
            }
        }
    }
}

@Composable
private fun ColumnScope.Question(s: QuizUiState, vm: QuizViewModel, onOpenLecture: (Long, Long?) -> Unit, recordingId: Long) {
    val q = s.questions[s.index]
    LinearProgressIndicator(progress = { s.index.toFloat() / s.questions.size }, modifier = Modifier.fillMaxWidth())
    Text(
        "Question ${s.index + 1} of ${s.questions.size} · ${s.correct} correct so far",
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(top = 6.dp, bottom = 16.dp),
    )
    Column(Modifier.weight(1f).verticalScroll(rememberScrollState())) {
        Text(q.question, style = MaterialTheme.typography.titleLarge)
        Spacer(Modifier.height(16.dp))
        if (q.isMultipleChoice) {
            q.options.forEach { opt ->
                val isAnswer = opt.trim().equals(q.answer.trim(), ignoreCase = true)
                val border = when {
                    s.picked == null -> ButtonDefaults.outlinedButtonBorder
                    isAnswer -> BorderStroke(2.dp, Correct)
                    opt == s.picked -> BorderStroke(2.dp, MaterialTheme.colorScheme.error)
                    else -> ButtonDefaults.outlinedButtonBorder
                }
                OutlinedButton(
                    onClick = { vm.pick(opt) },
                    border = border,
                    modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp).padding(vertical = 3.dp),
                ) { Text(opt, modifier = Modifier.fillMaxWidth()) }
            }
        } else if (s.picked == null) {
            FilledTonalButton(onClick = vm::reveal) { Text("Think of your answer, then tap to reveal") }
        }

        if (s.picked != null) {
            Spacer(Modifier.height(12.dp))
            if (q.isMultipleChoice) {
                Text(
                    if (s.gradedCorrect == true) "Correct!" else "Not quite. Answer: ${q.answer}",
                    color = if (s.gradedCorrect == true) Correct else MaterialTheme.colorScheme.error,
                    fontWeight = FontWeight.SemiBold,
                )
            } else {
                Text("Answer: ${q.answer}", fontWeight = FontWeight.SemiBold)
            }
            if (q.explanation.isNotBlank()) Text(q.explanation, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(top = 4.dp))
            q.atMs?.let { TextButton(onClick = { onOpenLecture(recordingId, it) }) { Text("Hear it in the lecture (${formatTimestamp(it)})") } }
        }
    }

    if (s.picked != null) {
        if (!q.isMultipleChoice && s.gradedCorrect == null) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                OutlinedButton(onClick = { vm.selfGrade(false) }, modifier = Modifier.weight(1f).heightIn(min = 52.dp)) { Text("I missed it") }
                Button(onClick = { vm.selfGrade(true) }, modifier = Modifier.weight(1f).heightIn(min = 52.dp)) { Text("I got it") }
            }
        } else {
            Button(onClick = { vm.next() }, modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp)) {
                Text(if (s.index + 1 < s.questions.size) "Next question" else "See score")
            }
        }
    }
}

@Composable
private fun Result(s: QuizUiState, onRetry: () -> Unit, onBack: () -> Unit) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text("${s.correct} / ${s.questions.size}", style = MaterialTheme.typography.displayMedium)
            val pct = s.correct * 100 / s.questions.size.coerceAtLeast(1)
            Text(
                when {
                    pct >= 90 -> "Excellent."
                    pct >= 70 -> "Good. Review the ones you missed."
                    pct >= 50 -> "Getting there. Re-read the notes and try again."
                    else -> "Worth another pass through the notes."
                },
                style = MaterialTheme.typography.bodyLarge,
            )
            s.previousBest?.let {
                Text(
                    "Previous best: $it / ${s.questions.size}",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 4.dp),
                )
            }
            Spacer(Modifier.height(20.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = onRetry) { Text("Try again") }
                Button(onClick = onBack) { Text("Done") }
            }
        }
    }
}

@Composable
private fun Centered(text: String, button: String, onClick: () -> Unit) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(text, style = MaterialTheme.typography.bodyLarge)
            Spacer(Modifier.height(12.dp))
            Button(onClick = onClick) { Text(button) }
        }
    }
}
