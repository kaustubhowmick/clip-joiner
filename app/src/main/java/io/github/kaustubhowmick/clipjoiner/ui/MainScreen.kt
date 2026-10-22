package io.github.kaustubhowmick.clipjoiner.ui

import android.Manifest
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Build
import android.text.format.Formatter
import android.util.LruCache
import android.util.Size
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import io.github.kaustubhowmick.clipjoiner.media.ClipAction
import io.github.kaustubhowmick.clipjoiner.media.ClipInfo
import io.github.kaustubhowmick.clipjoiner.media.MergeAnalysis
import io.github.kaustubhowmick.clipjoiner.media.MergeProgress
import io.github.kaustubhowmick.clipjoiner.media.Orientation
import io.github.kaustubhowmick.clipjoiner.media.OutputStore
import io.github.kaustubhowmick.clipjoiner.media.SavedVideo
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import sh.calvin.reorderable.ReorderableItem
import sh.calvin.reorderable.rememberReorderableLazyListState

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainScreen(vm: MainViewModel = viewModel()) {
    val state by vm.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val analysis = state.analysis
    val working = state.phase is Phase.Working

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.PickMultipleVisualMedia()) { uris ->
        if (uris.isNotEmpty()) vm.addClips(uris)
    }
    val pickVideos = { picker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.VideoOnly)) }

    // Android 8–9 need the storage permission to save into Movies/.
    val storagePermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) vm.onMergeClicked()
        else Toast.makeText(context, "Storage permission is needed to save the video", Toast.LENGTH_LONG).show()
    }
    val merge = {
        if (Build.VERSION.SDK_INT < 29 &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.WRITE_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED
        ) {
            storagePermission.launch(Manifest.permission.WRITE_EXTERNAL_STORAGE)
        } else {
            vm.onMergeClicked()
        }
    }

    val view = LocalView.current
    DisposableEffect(working) {
        view.keepScreenOn = working
        onDispose { view.keepScreenOn = false }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Clip Joiner") },
                actions = {
                    if (state.clips.isNotEmpty() && !working) {
                        TextButton(onClick = vm::clearAll) { Text("Clear") }
                    }
                },
            )
        },
        bottomBar = {
            if (state.clips.isNotEmpty() || state.phase !is Phase.Idle) {
                BottomPanel(
                    state = state,
                    analysis = analysis,
                    onAdd = pickVideos,
                    onMerge = merge,
                    onCancel = vm::cancelMerge,
                    onDismiss = vm::dismissResult,
                    onOpen = { openVideo(context, it) },
                )
            }
        },
    ) { padding ->
        if (state.clips.isEmpty()) {
            EmptyState(Modifier.padding(padding), onPick = pickVideos)
        } else {
            ClipList(
                clips = state.clips,
                analysis = analysis,
                enabled = !working,
                contentPadding = padding,
                onRemove = vm::remove,
                onMove = vm::move,
            )
        }
    }

    if (state.showOrientationSheet && analysis != null) {
        OrientationSheet(analysis = analysis, onDismiss = vm::dismissSheet, onConfirm = vm::mergeAs)
    }
}

@Composable
private fun EmptyState(modifier: Modifier, onPick: () -> Unit) {
    Column(
        modifier = modifier.fillMaxSize().padding(32.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Icon(AppIcons.Movie, contentDescription = null, modifier = Modifier.size(72.dp), tint = MaterialTheme.colorScheme.primary)
        Spacer(Modifier.height(16.dp))
        Text("Join clips into one video", style = MaterialTheme.typography.headlineSmall, textAlign = TextAlign.Center)
        Spacer(Modifier.height(8.dp))
        Text(
            "Matching clips are joined in seconds without re-encoding, so nothing loses quality or grows in size.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(32.dp))
        Button(onClick = onPick, modifier = Modifier.fillMaxWidth().height(64.dp)) {
            Icon(AppIcons.Add, contentDescription = null)
            Spacer(Modifier.width(8.dp))
            Text("Select videos", style = MaterialTheme.typography.titleMedium)
        }
    }
}

@Composable
private fun ClipList(
    clips: List<ClipItem>,
    analysis: MergeAnalysis?,
    enabled: Boolean,
    contentPadding: PaddingValues,
    onRemove: (Long) -> Unit,
    onMove: (Int, Int) -> Unit,
) {
    // Which clips would be converted with the default (or recommended) choice.
    val converting: Set<Int> = remember(analysis) {
        if (analysis == null || analysis.allCompatible) emptySet()
        else {
            val o = if (analysis.needsOrientationChoice) analysis.majorityOrientation else analysis.clips.first().video.orientation
            o?.let { analysis.planFor(it).steps.withIndex().filter { s -> s.value.action != ClipAction.COPY }.map { s -> s.index }.toSet() }
                ?: emptySet()
        }
    }
    val listState = rememberLazyListState()
    val reorderState = rememberReorderableLazyListState(listState) { from, to -> onMove(from.index, to.index) }
    LazyColumn(
        state = listState,
        contentPadding = PaddingValues(
            start = 16.dp, end = 16.dp,
            top = contentPadding.calculateTopPadding() + 8.dp,
            bottom = contentPadding.calculateBottomPadding() + 8.dp,
        ),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        items(clips, key = { it.key }) { clip ->
            ReorderableItem(reorderState, key = clip.key, enabled = enabled) { dragging ->
                val index = clips.indexOf(clip)
                Card(
                    elevation = CardDefaults.cardElevation(defaultElevation = if (dragging) 8.dp else 0.dp),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
                ) {
                    Row(Modifier.padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
                        Thumbnail(clip.uri, Modifier.size(width = 72.dp, height = 72.dp))
                        Spacer(Modifier.width(12.dp))
                        Column(Modifier.weight(1f)) {
                            Text(
                                clip.info?.name ?: clip.uri.lastPathSegment ?: "Video",
                                style = MaterialTheme.typography.bodyLarge,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                            Spacer(Modifier.height(4.dp))
                            when {
                                clip.error != null -> Text(clip.error, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                                clip.info == null -> Text("Reading…", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                else -> ClipDetails(clip.info, converting = index in converting)
                            }
                        }
                        IconButton(onClick = { onRemove(clip.key) }, enabled = enabled) {
                            Icon(AppIcons.Close, contentDescription = "Remove")
                        }
                        IconButton(onClick = {}, enabled = enabled, modifier = Modifier.draggableHandle(enabled = enabled)) {
                            Icon(AppIcons.DragHandle, contentDescription = "Drag to reorder")
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ClipDetails(info: ClipInfo, converting: Boolean) {
    Text(
        "${formatDuration(info.durationUs)} · ${info.video.displayWidth}×${info.video.displayHeight} · ${codecName(info.video.mime)}" +
            if (info.audio == null) " · no audio" else "",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
    )
    Spacer(Modifier.height(4.dp))
    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        Badge(if (info.video.orientation == Orientation.PORTRAIT) "Portrait" else "Landscape", MaterialTheme.colorScheme.secondaryContainer, MaterialTheme.colorScheme.onSecondaryContainer)
        if (converting) Badge("Will convert", MaterialTheme.colorScheme.tertiaryContainer, MaterialTheme.colorScheme.onTertiaryContainer)
    }
}

@Composable
private fun Badge(text: String, container: androidx.compose.ui.graphics.Color, content: androidx.compose.ui.graphics.Color) {
    Surface(color = container, contentColor = content, shape = RoundedCornerShape(6.dp)) {
        Text(text, style = MaterialTheme.typography.labelSmall, modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp))
    }
}

@Composable
private fun BottomPanel(
    state: UiState,
    analysis: MergeAnalysis?,
    onAdd: () -> Unit,
    onMerge: () -> Unit,
    onCancel: () -> Unit,
    onDismiss: () -> Unit,
    onOpen: (SavedVideo) -> Unit,
) {
    val context = LocalContext.current
    Surface(tonalElevation = 3.dp, shadowElevation = 8.dp) {
        Column(Modifier.fillMaxWidth().navigationBarsPadding().padding(16.dp)) {
            when (val phase = state.phase) {
                is Phase.Working -> WorkingPanel(phase.progress, onCancel)
                is Phase.Done -> {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(AppIcons.Check, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                        Spacer(Modifier.width(8.dp))
                        Text("Saved to Movies/${OutputStore.FOLDER}", style = MaterialTheme.typography.titleMedium)
                    }
                    Spacer(Modifier.height(4.dp))
                    Text(
                        "${phase.saved.displayName} · ${Formatter.formatShortFileSize(context, phase.saved.sizeBytes)}",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(12.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        OutlinedButton(onClick = onDismiss, modifier = Modifier.weight(1f)) { Text("Done") }
                        Button(onClick = { onOpen(phase.saved) }, modifier = Modifier.weight(1f)) {
                            Icon(AppIcons.Play, contentDescription = null)
                            Spacer(Modifier.width(6.dp))
                            Text("Open")
                        }
                    }
                }
                is Phase.Failed -> {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(AppIcons.Warning, contentDescription = null, tint = MaterialTheme.colorScheme.error)
                        Spacer(Modifier.width(8.dp))
                        Text("Couldn't merge: ${phase.message}", style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
                        TextButton(onClick = onDismiss) { Text("OK") }
                    }
                }
                Phase.Idle -> {
                    Text(
                        summary(context, state, analysis),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(12.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        OutlinedButton(onClick = onAdd, modifier = Modifier.height(52.dp)) {
                            Icon(AppIcons.Add, contentDescription = null)
                            Spacer(Modifier.width(6.dp))
                            Text("Add")
                        }
                        Button(onClick = onMerge, enabled = state.canMerge, modifier = Modifier.weight(1f).height(52.dp)) {
                            Text("Merge", style = MaterialTheme.typography.titleMedium)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun WorkingPanel(progress: MergeProgress?, onCancel: () -> Unit) {
    val (label, fraction) = when (progress) {
        is MergeProgress.Converting -> "Converting clip ${progress.index} of ${progress.count}…" to progress.fraction
        is MergeProgress.Joining -> "Joining…" to progress.fraction
        null -> "Preparing…" to null
    }
    Row(verticalAlignment = Alignment.CenterVertically) {
        if (fraction == null) {
            CircularProgressIndicator(Modifier.size(28.dp), strokeWidth = 3.dp)
            Spacer(Modifier.width(16.dp))
            Text(label, style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
        } else {
            Column(Modifier.weight(1f)) {
                Text("$label ${(fraction * 100).toInt()}%", style = MaterialTheme.typography.titleMedium)
                Spacer(Modifier.height(8.dp))
                LinearProgressIndicator(progress = { fraction }, modifier = Modifier.fillMaxWidth())
            }
        }
        Spacer(Modifier.width(8.dp))
        TextButton(onClick = onCancel) { Text("Cancel") }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun OrientationSheet(analysis: MergeAnalysis, onDismiss: () -> Unit, onConfirm: (Orientation) -> Unit) {
    var choice by remember(analysis) { mutableStateOf(analysis.majorityOrientation) }
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(Modifier.padding(horizontal = 24.dp).padding(bottom = 24.dp)) {
            Text("Some clips don't match", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.height(4.dp))
            Text("Merge as Portrait or Landscape?", style = MaterialTheme.typography.bodyLarge)
            if (analysis.majorityOrientation == null) {
                Spacer(Modifier.height(4.dp))
                Text(
                    "Your clips are split evenly, so pick one.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Spacer(Modifier.height(16.dp))
            for (o in listOf(Orientation.PORTRAIT, Orientation.LANDSCAPE)) {
                val plan = remember(analysis, o) { analysis.planFor(o) }
                val selected = choice == o
                val shape = RoundedCornerShape(12.dp)
                Row(
                    Modifier
                        .fillMaxWidth()
                        .padding(vertical = 4.dp)
                        .clip(shape)
                        .border(
                            width = if (selected) 2.dp else 1.dp,
                            color = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant,
                            shape = shape,
                        )
                        .background(if (selected) MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.4f) else MaterialTheme.colorScheme.surface)
                        .clickable { choice = o }
                        .padding(12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    RadioButton(selected = selected, onClick = { choice = o })
                    Spacer(Modifier.width(8.dp))
                    Column {
                        Text(if (o == Orientation.PORTRAIT) "Portrait" else "Landscape", style = MaterialTheme.typography.titleMedium)
                        if (o == analysis.majorityOrientation) {
                            Text("Recommended (most of your clips)", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
                        }
                        val n = plan.convertCount
                        Text(
                            "${plan.targetVideo.displayWidth}×${plan.targetVideo.displayHeight} · " +
                                if (n == 1) "1 clip converted with black bars" else "$n clips converted with black bars",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
            Spacer(Modifier.height(16.dp))
            Button(
                onClick = { choice?.let(onConfirm) },
                enabled = choice != null,
                modifier = Modifier.fillMaxWidth().height(52.dp),
            ) { Text("Merge") }
        }
    }
}

@Composable
private fun Thumbnail(uri: Uri, modifier: Modifier) {
    val context = LocalContext.current
    val image by produceState<ImageBitmap?>(ThumbCache.get(uri), uri) {
        if (value == null) value = withContext(Dispatchers.IO) { ThumbCache.load(context, uri) }
    }
    Box(
        modifier.clip(RoundedCornerShape(8.dp)).background(MaterialTheme.colorScheme.surfaceVariant),
        contentAlignment = Alignment.Center,
    ) {
        val img = image
        if (img != null) {
            Image(img, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
        } else {
            Icon(AppIcons.Movie, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

private object ThumbCache {
    private val cache = LruCache<Uri, ImageBitmap>(64)

    fun get(uri: Uri): ImageBitmap? = cache.get(uri)

    fun load(context: Context, uri: Uri): ImageBitmap? {
        val thumb: Bitmap? = try {
            if (Build.VERSION.SDK_INT >= 29) context.contentResolver.loadThumbnail(uri, Size(256, 256), null) else null
        } catch (_: Exception) {
            null
        }
        val bitmap = thumb ?: frameAt(context, uri)
        return bitmap?.asImageBitmap()?.also { cache.put(uri, it) }
    }

    private fun frameAt(context: Context, uri: Uri): Bitmap? {
        val r = MediaMetadataRetriever()
        return try {
            r.setDataSource(context, uri)
            val frame = r.getFrameAtTime(0) ?: return null
            val scale = 256f / maxOf(frame.width, frame.height)
            if (scale < 1f) Bitmap.createScaledBitmap(frame, (frame.width * scale).toInt(), (frame.height * scale).toInt(), true) else frame
        } catch (_: Exception) {
            null
        } finally {
            r.release()
        }
    }
}

private fun summary(context: Context, state: UiState, analysis: MergeAnalysis?): String {
    val n = state.clips.size
    val head = if (n == 1) "1 clip" else "$n clips"
    if (state.clips.any { it.error != null }) return "$head · remove the clips that can't be read"
    if (analysis == null) return "$head · reading…"
    val duration = formatDuration(analysis.clips.sumOf { it.durationUs })
    val size = "~" + Formatter.formatShortFileSize(context, analysis.clips.sumOf { it.sizeBytes })
    val status = when {
        n < 2 -> "add another clip to merge"
        analysis.allCompatible -> "all compatible ✓"
        analysis.needsOrientationChoice && analysis.majorityOrientation == null -> "mixed orientations, you'll choose"
        else -> {
            val o = analysis.majorityOrientation ?: analysis.clips.first().video.orientation
            val c = analysis.planFor(o).convertCount
            if (c == 1) "1 clip needs converting" else "$c clips need converting"
        }
    }
    return "$head · $duration · $size · $status"
}

private fun formatDuration(us: Long): String {
    val total = (us + 500_000) / 1_000_000
    val h = total / 3600
    val m = (total % 3600) / 60
    val s = total % 60
    return if (h > 0) "%d:%02d:%02d".format(h, m, s) else "%d:%02d".format(m, s)
}

private fun codecName(mime: String): String = when (mime) {
    "video/avc" -> "H.264"
    "video/hevc" -> "H.265"
    "video/av01" -> "AV1"
    "video/x-vnd.on2.vp9" -> "VP9"
    "video/x-vnd.on2.vp8" -> "VP8"
    "video/mp4v-es" -> "MPEG-4"
    "video/3gpp" -> "H.263"
    else -> mime.substringAfter('/').uppercase()
}

private fun openVideo(context: Context, saved: SavedVideo) {
    val intent = Intent(Intent.ACTION_VIEW)
        .setDataAndType(saved.uri, "video/mp4")
        .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    try {
        context.startActivity(intent)
    } catch (_: ActivityNotFoundException) {
        Toast.makeText(context, "No app can play this video", Toast.LENGTH_SHORT).show()
    }
}
