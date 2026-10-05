package com.lecturenotes.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import kotlinx.coroutines.flow.MutableStateFlow

object Routes {
    const val HOME = "home"
    const val RECORD = "record?subject={subject}"
    const val MODELS = "models"
    const val SETTINGS = "settings"
    const val NOTES_AI = "notes-ai"
    const val SUBJECT = "subject/{id}"
    fun subject(id: Long) = "subject/$id"
    const val RECORDING = "recording/{id}?t={t}"
    fun record(subjectId: Long?) = "record?subject=${subjectId ?: -1}"
    fun recording(id: Long, atMs: Long? = null) = "recording/$id?t=${atMs ?: -1}"
}

@Composable
fun AppNav(openRecording: MutableStateFlow<Long?>) {
    val nav = rememberNavController()
    val pending by openRecording.collectAsState()
    LaunchedEffect(pending) {
        pending?.let {
            nav.navigate(Routes.recording(it))
            openRecording.value = null
        }
    }

    NavHost(nav, startDestination = Routes.HOME) {
        composable(Routes.HOME) {
            HomeScreen(
                onOpen = { id, atMs -> nav.navigate(Routes.recording(id, atMs)) },
                onRecord = { nav.navigate(Routes.record(it)) },
                onModels = { nav.navigate(Routes.MODELS) },
                onSettings = { nav.navigate(Routes.SETTINGS) },
                onEditSubject = { nav.navigate(Routes.subject(it)) },
            )
        }
        composable(Routes.SUBJECT, arguments = listOf(navArgument("id") { type = NavType.LongType })) { entry ->
            SubjectScreen(subjectId = entry.arguments!!.getLong("id"), onBack = { nav.popBackStack() })
        }
        composable(Routes.SETTINGS) {
            SettingsScreen(
                onBack = { nav.popBackStack() },
                onSpeechModels = { nav.navigate(Routes.MODELS) },
                onNotesAi = { nav.navigate(Routes.NOTES_AI) },
            )
        }
        composable(Routes.NOTES_AI) {
            NotesAiScreen(onBack = { nav.popBackStack() })
        }
        composable(
            Routes.RECORD,
            arguments = listOf(navArgument("subject") { type = NavType.LongType; defaultValue = -1L }),
        ) { entry ->
            RecordScreen(
                subjectId = entry.arguments!!.getLong("subject").takeIf { it > 0 },
                onBack = { nav.popBackStack() },
                onFinished = { id ->
                    nav.popBackStack()
                    nav.navigate(Routes.recording(id))
                },
            )
        }
        composable(Routes.MODELS) {
            ModelsScreen(onBack = { nav.popBackStack() })
        }
        composable(
            Routes.RECORDING,
            arguments = listOf(
                navArgument("id") { type = NavType.LongType },
                navArgument("t") { type = NavType.LongType; defaultValue = -1L },
            ),
        ) { entry ->
            RecordingScreen(
                recordingId = entry.arguments!!.getLong("id"),
                startAtMs = entry.arguments!!.getLong("t").takeIf { it >= 0 },
                onBack = { nav.popBackStack() },
                onModels = { nav.navigate(Routes.MODELS) },
                onNotesAi = { nav.navigate(Routes.NOTES_AI) },
                onEditSubject = { nav.navigate(Routes.subject(it)) },
            )
        }
    }
}
