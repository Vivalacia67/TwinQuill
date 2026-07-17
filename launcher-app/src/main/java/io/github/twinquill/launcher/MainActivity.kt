/*
 * SPDX-License-Identifier: GPL-2.0-or-later
 * Copyright (C) 2026 TwinQuill contributors
 */
package io.github.twinquill.launcher

import android.app.Activity
import android.content.Intent
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.livedata.observeAsState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.twinquill.engine.api.EngineResult
import io.github.twinquill.engine.api.EngineType
import io.github.twinquill.launcher.data.GameEntity
import io.github.twinquill.launcher.data.GameRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class MainActivity : ComponentActivity() {
    private lateinit var repository: GameRepository

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        repository = GameRepository(this)
        setContent {
            TwinQuillTheme {
                LauncherScreen(repository)
            }
        }
    }

    override fun onStart() {
        super.onStart()
        repository.refreshPermissions()
    }

    override fun onDestroy() {
        repository.close()
        super.onDestroy()
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun LauncherScreen(repository: GameRepository) {
    val context = LocalContext.current
    val gamesLiveData = remember(repository) { repository.observeGames() }
    val games by gamesLiveData.observeAsState(emptyList())
    val snackbar = remember { SnackbarHostState() }
    var message by remember { mutableStateOf<String?>(null) }
    var coverTarget by remember { mutableStateOf<GameEntity?>(null) }
    var deleteTarget by remember { mutableStateOf<GameEntity?>(null) }
    var engineTarget by remember { mutableStateOf<GameEntity?>(null) }

    LaunchedEffect(message) {
        message?.let {
            snackbar.showSnackbar(it)
            message = null
        }
    }

    val engineLauncher =
        rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            val code =
                result.data?.getIntExtra(
                    io.github.twinquill.engine.api.EngineContract.EXTRA_RESULT,
                    if (result.resultCode == Activity.RESULT_OK) 0 else 13,
                ) ?: if (result.resultCode == Activity.RESULT_OK) 0 else 13
            message = "引擎返回：${EngineResult.fromCode(code).name}"
        }
    val treeLauncher =
        rememberLauncherForActivityResult(OpenGameTreeContract()) { grant ->
            if (grant != null) {
                repository.addGame(grant.uri, grant.flags) { _, error ->
                    message = error?.localizedMessage ?: "游戏已添加"
                }
            }
        }
    val coverLauncher =
        rememberLauncherForActivityResult(OpenCoverContract()) { grant ->
            val target = coverTarget
            coverTarget = null
            if (grant != null && target != null) {
                repository.setCover(target.id, grant.uri, grant.flags) { _, error ->
                    message = error?.localizedMessage ?: "封面已更新"
                }
            }
        }

    fun launch(game: GameEntity, engine: EngineType) {
        if (!game.permissionValid) {
            message = "目录授权已失效，请删除后重新添加游戏。"
            return
        }
        if (engine == EngineType.AUTO) {
            engineTarget = game
            return
        }
        try {
            repository.markPlayed(game.id)
            engineLauncher.launch(EngineRouter.createIntent(context, game, engine))
        } catch (exception: RuntimeException) {
            message = exception.localizedMessage
        }
    }

    Scaffold(
        topBar = { TopAppBar(title = { Text(stringResource(R.string.launcher_title)) }) },
        snackbarHost = { SnackbarHost(snackbar) },
        floatingActionButton = {
            ExtendedFloatingActionButton(
                onClick = { treeLauncher.launch(Unit) },
            ) {
                Text("+  ${stringResource(R.string.add_game)}")
            }
        },
    ) { padding ->
        if (games.isEmpty()) {
            EmptyLibrary(Modifier.padding(padding))
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxSize().padding(padding),
                contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 96.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                items(games, key = { it.id }) { game ->
                    GameCard(
                        game = game,
                        onLaunch = { launch(game, game.effectiveEngine()) },
                        onEngine = { repository.setOverride(game.id, it, null) },
                        onCover = {
                            coverTarget = game
                            coverLauncher.launch(Unit)
                        },
                        onDelete = { deleteTarget = game },
                    )
                }
            }
        }
    }

    engineTarget?.let { game ->
        EngineChoiceDialog(
            game = game,
            onDismiss = { engineTarget = null },
            onSelected = {
                engineTarget = null
                launch(game, it)
            },
        )
    }
    deleteTarget?.let { game ->
        AlertDialog(
            onDismissRequest = { deleteTarget = null },
            title = { Text(stringResource(R.string.delete_game)) },
            text = { Text("从列表删除“${game.name}”？游戏文件和存档不会被删除。") },
            confirmButton = {
                Button(
                    onClick = {
                        deleteTarget = null
                        repository.deleteGame(game) { _, error ->
                            message = error?.localizedMessage ?: "已从列表删除"
                        }
                    },
                ) { Text(stringResource(R.string.delete_game)) }
            },
            dismissButton = {
                TextButton(onClick = { deleteTarget = null }) {
                    Text(stringResource(R.string.cancel))
                }
            },
        )
    }
}

@Composable
private fun EmptyLibrary(modifier: Modifier = Modifier) {
    Column(
        modifier = modifier.fillMaxSize().padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Text("TQ", style = MaterialTheme.typography.displayMedium, fontWeight = FontWeight.Black)
        Spacer(Modifier.height(20.dp))
        Text(stringResource(R.string.empty_title), style = MaterialTheme.typography.titleLarge)
        Spacer(Modifier.height(8.dp))
        Text(
            stringResource(R.string.empty_message),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun GameCard(
    game: GameEntity,
    onLaunch: () -> Unit,
    onEngine: (EngineType) -> Unit,
    onCover: () -> Unit,
    onDelete: () -> Unit,
) {
    var engineMenu by remember { mutableStateOf(false) }
    Card(
        modifier = Modifier.fillMaxWidth().clickable(onClick = onLaunch),
        colors =
            CardDefaults.cardColors(
                containerColor =
                    if (game.permissionValid) {
                        MaterialTheme.colorScheme.surfaceVariant
                    } else {
                        MaterialTheme.colorScheme.errorContainer
                    },
            ),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(12.dp),
            horizontalArrangement = Arrangement.spacedBy(14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            GameCover(game)
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    game.name,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    if (game.permissionValid) detectionLabel(game) else "授权失效",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box {
                        TextButton(onClick = { engineMenu = true }) {
                            Text(engineLabel(game.effectiveEngine()))
                        }
                        DropdownMenu(
                            expanded = engineMenu,
                            onDismissRequest = { engineMenu = false },
                        ) {
                            EngineType.entries.forEach { engine ->
                                DropdownMenuItem(
                                    text = { Text(engineLabel(engine)) },
                                    onClick = {
                                        engineMenu = false
                                        onEngine(engine)
                                    },
                                )
                            }
                        }
                    }
                    TextButton(onClick = onCover) { Text(stringResource(R.string.change_cover)) }
                    TextButton(onClick = onDelete) { Text(stringResource(R.string.delete_game)) }
                }
            }
        }
    }
}

@Composable
private fun GameCover(game: GameEntity) {
    val context = LocalContext.current
    val image by
        produceState<android.graphics.Bitmap?>(initialValue = null, game.coverUri) {
            value =
                withContext(Dispatchers.IO) {
                    game.coverUri?.let { cover ->
                        try {
                            context.contentResolver.openInputStream(Uri.parse(cover))?.use {
                                BitmapFactory.decodeStream(it)
                            }
                        } catch (_: Exception) {
                            null
                        }
                    }
                }
        }
    val bitmap = image
    if (bitmap == null) {
        Box(
            modifier =
                Modifier.size(width = 72.dp, height = 96.dp)
                    .background(
                        MaterialTheme.colorScheme.primaryContainer,
                        RoundedCornerShape(10.dp),
                    ),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                game.name.take(1).uppercase(),
                style = MaterialTheme.typography.headlineMedium,
                fontWeight = FontWeight.Bold,
            )
        }
    } else {
        Image(
            bitmap = bitmap.asImageBitmap(),
            contentDescription = "${game.name} 封面",
            modifier = Modifier.size(width = 72.dp, height = 96.dp),
            contentScale = ContentScale.Crop,
        )
    }
}

@Composable
private fun EngineChoiceDialog(
    game: GameEntity,
    onDismiss: () -> Unit,
    onSelected: (EngineType) -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.choose_engine)) },
        text = {
            Column {
                Text(game.detectionConflict ?: stringResource(R.string.unknown_engine))
                Spacer(Modifier.height(12.dp))
                Button(onClick = { onSelected(EngineType.ONS) }, modifier = Modifier.fillMaxWidth()) {
                    Text(stringResource(R.string.engine_ons))
                }
                Spacer(Modifier.height(8.dp))
                Button(
                    onClick = { onSelected(EngineType.KRKR) },
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(stringResource(R.string.engine_krkr))
                }
            }
        },
        confirmButton = {},
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) }
        },
    )
}

private fun engineLabel(engine: EngineType): String =
    when (engine) {
        EngineType.AUTO -> "自动"
        EngineType.ONS -> "ONS"
        EngineType.KRKR -> "Kirikiri"
    }

private fun detectionLabel(game: GameEntity): String =
    if (game.detectionConflict != null) {
        "需要手动选择引擎"
    } else {
        "检测：${engineLabel(EngineType.fromStorage(game.detectedEngine))}"
    }
