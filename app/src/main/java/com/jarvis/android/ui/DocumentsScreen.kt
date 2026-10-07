package com.jarvis.android.ui

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.unit.dp
import com.jarvis.android.docs.Block
import com.jarvis.android.docs.CheckItem
import com.jarvis.android.docs.DocDraft
import com.jarvis.android.docs.DocumentDrafts
import com.jarvis.android.docs.DocumentStore
import com.jarvis.android.docs.ExportedDocument
import com.jarvis.android.docs.exportDocument
import com.jarvis.android.docs.loadDocumentImage
import com.jarvis.android.docs.parseBlocks
import com.jarvis.android.docs.toMarkdown
import com.jarvis.android.i18n.tr
import com.jarvis.android.i18n.trf
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import java.text.DateFormat
import java.util.Date

/** The documents Jarvis and the user write: a list of drafts, and an editor of blocks (text, lists, tables, pictures…) that exports them. */
@Composable
fun DocumentsScreen(http: OkHttpClient, onBack: () -> Unit) {
    var openId by rememberSaveable { mutableStateOf<String?>(null) }
    val id = openId
    if (id == null) DraftList(onOpen = { openId = it }, onBack = onBack)
    else DocumentEditor(id, http, onClose = { openId = null })
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun DraftList(onOpen: (String) -> Unit, onBack: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val snackbar = remember { SnackbarHostState() }
    var drafts by remember { mutableStateOf<List<DocDraft>?>(null) }
    var reload by remember { mutableIntStateOf(0) }
    LaunchedEffect(reload) { drafts = withContext(Dispatchers.IO) { DocumentDrafts.list(context) } }
    val format = remember { DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT) }

    Scaffold(
        containerColor = Color.Transparent,
        contentColor = MaterialTheme.colorScheme.onBackground,
        snackbarHost = { SnackbarHost(snackbar) },
        topBar = {
            TopAppBar(
                colors = TopAppBarDefaults.topAppBarColors(containerColor = Color.Transparent),
                title = { Text(tr("Documents"), style = MaterialTheme.typography.titleLarge) },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, tr("Retour")) } },
            )
        },
        floatingActionButton = {
            ExtendedFloatingActionButton(
                onClick = {
                    scope.launch {
                        val draft = withContext(Dispatchers.IO) { DocumentDrafts.create(context, "", "") }
                        onOpen(draft.id)
                    }
                },
                icon = { Icon(Icons.Filled.Add, null) },
                text = { Text(tr("Nouveau document")) },
            )
        },
    ) { padding ->
        val list = drafts
        when {
            list == null -> Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
            list.isEmpty() -> Text(
                tr("Aucun document. Créez-en un ici, ou demandez à Jarvis : « fais-moi un rapport en PDF avec ma dernière photo »."),
                modifier = Modifier.padding(padding).padding(24.dp),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            else -> LazyColumn(Modifier.fillMaxSize().padding(padding), contentPadding = PaddingValues(16.dp, 8.dp, 16.dp, 96.dp)) {
                items(list, key = { it.id }) { draft ->
                    Card(Modifier.fillMaxWidth().padding(vertical = 4.dp).clickable { onOpen(draft.id) }) {
                        Row(Modifier.padding(start = 16.dp, top = 8.dp, bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Filled.Description, null, tint = MaterialTheme.colorScheme.primary)
                            Column(Modifier.weight(1f).padding(horizontal = 12.dp)) {
                                Text(draft.title.ifBlank { tr("Sans titre") }, style = MaterialTheme.typography.titleMedium)
                                Text(format.format(Date(draft.updated)), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                            IconButton(onClick = {
                                scope.launch {
                                    withContext(Dispatchers.IO) { DocumentDrafts.delete(context, draft.id) }
                                    reload++
                                    val result = snackbar.showSnackbar(trf("« {0} » supprimé", draft.title.ifBlank { tr("Sans titre") }), tr("Annuler"), duration = SnackbarDuration.Short)
                                    withContext(Dispatchers.IO) {
                                        if (result == SnackbarResult.ActionPerformed) DocumentDrafts.save(context, draft) else DocumentDrafts.forgetUnusedPictures(context)
                                    }
                                    reload++
                                }
                            }) { Icon(Icons.Filled.Delete, tr("Supprimer")) }
                        }
                    }
                }
            }
        }
    }
}

/** A block in the editor, with a stable key for the list. */
private class Item(val key: Long, val block: Block)

private val EXPORTS = listOf("pdf" to "PDF", "docx" to "Word (.docx)", "pptx" to "PowerPoint (.pptx)", "html" to "Page web (.html)", "md" to "Markdown (.md)", "txt" to "Texte (.txt)")

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun DocumentEditor(id: String, http: OkHttpClient, onClose: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var draft by remember(id) { mutableStateOf<DocDraft?>(null) }
    var title by remember(id) { mutableStateOf("") }
    val items = remember(id) { mutableStateListOf<Item>() }
    var nextKey by remember(id) { mutableLongStateOf(0L) }
    var exporting by remember { mutableStateOf(false) }
    var exported by remember { mutableStateOf<ExportedDocument?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var exportMenu by remember { mutableStateOf(false) }
    var addMenu by remember { mutableStateOf(false) }

    LaunchedEffect(id) {
        val loaded = withContext(Dispatchers.IO) { DocumentDrafts.load(context, id) }
        if (loaded == null) { onClose(); return@LaunchedEffect }
        title = loaded.title
        items.clear()
        parseBlocks(loaded.markdown).forEach { items += Item(nextKey++, it) }
        draft = loaded
    }

    fun current(): DocDraft? = draft?.copy(title = title.trim(), markdown = toMarkdown(items.map { it.block }))
    suspend fun saveNow() {
        val d = current() ?: return
        withContext(Dispatchers.IO) { DocumentDrafts.save(context, d) }
    }
    // kept as the user types, a moment after the last change
    LaunchedEffect(id, draft != null) {
        if (draft == null) return@LaunchedEffect
        snapshotFlow { title to items.map { it.block } }.drop(1).collectLatest {
            delay(700)
            saveNow()
        }
    }
    val close: () -> Unit = {
        scope.launch {
            val d = current()
            // a new document left empty is not kept
            if (d != null && d.title.isBlank() && d.markdown.isBlank()) withContext(Dispatchers.IO) { DocumentDrafts.delete(context, d.id) } else saveNow()
            onClose()
        }
    }
    BackHandler(onBack = close)

    fun add(block: Block) { items += Item(nextKey++, block) }
    val pickImage = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri != null) scope.launch {
            val path = withContext(Dispatchers.IO) {
                runCatching { context.contentResolver.openInputStream(uri)?.use { it.readBytes() }?.let { DocumentDrafts.keepPicture(context, it) } }.getOrNull()
            }
            if (path != null) add(Block.Image(path)) else error = tr("Cette image n’a pas pu être lue.")
        }
    }

    Scaffold(
        containerColor = Color.Transparent,
        contentColor = MaterialTheme.colorScheme.onBackground,
        topBar = {
            TopAppBar(
                colors = TopAppBarDefaults.topAppBarColors(containerColor = Color.Transparent),
                title = { Text(title.ifBlank { tr("Document") }, style = MaterialTheme.typography.titleLarge, maxLines = 1) },
                navigationIcon = { IconButton(onClick = close) { Icon(Icons.AutoMirrored.Filled.ArrowBack, tr("Retour")) } },
                actions = {
                    Box {
                        if (exporting) CircularProgressIndicator(Modifier.size(24.dp).padding(2.dp), strokeWidth = 2.dp)
                        else IconButton(onClick = { exportMenu = true }) { Icon(Icons.Filled.Share, tr("Exporter")) }
                        DropdownMenu(expanded = exportMenu, onDismissRequest = { exportMenu = false }) {
                            EXPORTS.forEach { (type, label) ->
                                DropdownMenuItem(text = { Text(tr(label)) }, onClick = {
                                    exportMenu = false
                                    val d = current() ?: return@DropdownMenuItem
                                    exporting = true
                                    scope.launch {
                                        try {
                                            saveNow()
                                            exported = exportDocument(context, http, type, d.title, d.markdown, d.title.ifBlank { "document" })
                                        } catch (e: CancellationException) {
                                            throw e
                                        } catch (e: Exception) {
                                            error = trf("L’export a échoué : {0}", e.message ?: e.javaClass.simpleName)
                                        } finally {
                                            exporting = false
                                        }
                                    }
                                })
                            }
                        }
                    }
                },
            )
        },
    ) { padding ->
        if (draft == null) {
            Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
            return@Scaffold
        }
        LazyColumn(Modifier.fillMaxSize().padding(padding).imePadding(), contentPadding = PaddingValues(16.dp, 4.dp, 16.dp, 48.dp)) {
            item {
                OutlinedTextField(
                    value = title, onValueChange = { title = it }, label = { Text(tr("Titre du document")) },
                    singleLine = true, modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp),
                )
            }
            itemsIndexed(items, key = { _, item -> item.key }) { index, item ->
                BlockCard(
                    block = item.block,
                    http = http,
                    onChange = { items[index] = Item(item.key, it) },
                    onUp = if (index > 0) ({ items.add(index - 1, items.removeAt(index)) }) else null,
                    onDown = if (index < items.size - 1) ({ items.add(index + 1, items.removeAt(index)) }) else null,
                    onDelete = { items.removeAt(index) },
                    onReplaceImage = { pickImage.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) },
                )
            }
            item {
                Box(Modifier.fillMaxWidth().padding(top = 8.dp), contentAlignment = Alignment.Center) {
                    Button(onClick = { addMenu = true }) {
                        Icon(Icons.Filled.Add, null)
                        Spacer(Modifier.width(8.dp))
                        Text(tr("Ajouter"))
                    }
                    DropdownMenu(expanded = addMenu, onDismissRequest = { addMenu = false }) {
                        val choices: List<Pair<String, () -> Unit>> = listOf(
                            tr("Titre de section") to { add(Block.Heading(1, "")) },
                            tr("Paragraphe") to { add(Block.Paragraph("")) },
                            tr("Image de la galerie") to { pickImage.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) },
                            tr("Liste à puces") to { add(Block.Bullets(listOf(""), numbered = false)) },
                            tr("Liste numérotée") to { add(Block.Bullets(listOf(""), numbered = true)) },
                            tr("Cases à cocher") to { add(Block.Checklist(listOf(CheckItem("", false)))) },
                            tr("Tableau") to { add(Block.Table(listOf(listOf("", ""), listOf("", "")))) },
                            tr("Citation") to { add(Block.Quote("")) },
                            tr("Code") to { add(Block.Code("")) },
                            tr("Séparateur") to { add(Block.Divider) },
                            tr("Saut de page") to { add(Block.PageBreak) },
                        )
                        choices.forEach { (label, action) -> DropdownMenuItem(text = { Text(label) }, onClick = { addMenu = false; action() }) }
                    }
                }
            }
        }
    }

    exported?.let { done ->
        AlertDialog(
            onDismissRequest = { exported = null },
            title = { Text(tr("Document exporté")) },
            text = {
                Text(
                    trf("{0} est enregistré dans Documents/Jarvis.", done.file.name) +
                        (if (done.missingImages > 0) "\n" + trf("{0} image(s) introuvable(s) : leur place est marquée.", done.missingImages) else "")
                )
            },
            confirmButton = { TextButton(onClick = { openFile(context, done); exported = null }) { Text(tr("Ouvrir")) } },
            dismissButton = { TextButton(onClick = { shareFile(context, done); exported = null }) { Text(tr("Partager")) } },
        )
    }
    error?.let { message ->
        AlertDialog(
            onDismissRequest = { error = null },
            text = { Text(message) },
            confirmButton = { TextButton(onClick = { error = null }) { Text(tr("OK")) } },
        )
    }
}

private fun openFile(context: Context, done: ExportedDocument) {
    val uri = DocumentStore.uriFor(context, done.file)
    val view = Intent(Intent.ACTION_VIEW).setDataAndType(uri, DocumentStore.viewMimeFor(done.file.extension)).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    runCatching { context.startActivity(Intent.createChooser(view, tr("Ouvrir avec"))) }
}

private fun shareFile(context: Context, done: ExportedDocument) {
    val uri = DocumentStore.uriFor(context, done.file)
    val send = Intent(Intent.ACTION_SEND).setType(DocumentStore.mimeFor(done.file.extension)).putExtra(Intent.EXTRA_STREAM, uri)
        .putExtra(Intent.EXTRA_SUBJECT, done.file.nameWithoutExtension).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    runCatching { context.startActivity(Intent.createChooser(send, tr("Partager"))) }
}

private fun blockLabel(block: Block): String = when (block) {
    is Block.Heading -> tr("Titre de section")
    is Block.Paragraph -> tr("Paragraphe")
    is Block.Bullets -> if (block.numbered) tr("Liste numérotée") else tr("Liste à puces")
    is Block.Checklist -> tr("Cases à cocher")
    is Block.Table -> tr("Tableau")
    is Block.Image -> tr("Image")
    is Block.Quote -> tr("Citation")
    is Block.Code -> tr("Code")
    Block.Divider -> tr("Séparateur")
    Block.PageBreak -> tr("Saut de page")
}

@Composable
private fun BlockCard(
    block: Block, http: OkHttpClient, onChange: (Block) -> Unit, onUp: (() -> Unit)?, onDown: (() -> Unit)?, onDelete: () -> Unit, onReplaceImage: () -> Unit,
) {
    Card(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
        Column(Modifier.padding(start = 12.dp, end = 4.dp, bottom = 10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(blockLabel(block), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary, modifier = Modifier.weight(1f))
                IconButton(onClick = { onUp?.invoke() }, enabled = onUp != null) { Icon(Icons.Filled.KeyboardArrowUp, tr("Monter")) }
                IconButton(onClick = { onDown?.invoke() }, enabled = onDown != null) { Icon(Icons.Filled.KeyboardArrowDown, tr("Descendre")) }
                IconButton(onClick = onDelete) { Icon(Icons.Filled.Delete, tr("Supprimer")) }
            }
            Column(Modifier.padding(end = 8.dp)) {
                when (block) {
                    is Block.Heading -> {
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            (1..3).forEach { level ->
                                FilterChip(selected = block.level == level, onClick = { onChange(block.copy(level = level)) }, label = { Text(trf("Niveau {0}", level)) })
                            }
                        }
                        OutlinedTextField(block.text, { onChange(block.copy(text = it.replace('\n', ' '))) }, Modifier.fillMaxWidth(), textStyle = MaterialTheme.typography.titleMedium)
                    }
                    is Block.Paragraph -> OutlinedTextField(block.text, { onChange(block.copy(text = it)) }, Modifier.fillMaxWidth(), minLines = 3)
                    is Block.Quote -> OutlinedTextField(
                        block.text, { onChange(block.copy(text = it)) }, Modifier.fillMaxWidth(), minLines = 2,
                        textStyle = MaterialTheme.typography.bodyLarge.copy(fontStyle = FontStyle.Italic),
                    )
                    is Block.Code -> OutlinedTextField(
                        block.text, { onChange(block.copy(text = it)) }, Modifier.fillMaxWidth(), minLines = 3,
                        textStyle = MaterialTheme.typography.bodyMedium.copy(fontFamily = FontFamily.Monospace),
                    )
                    is Block.Bullets -> {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(tr("Numérotée"), Modifier.weight(1f))
                            Switch(block.numbered, { onChange(block.copy(numbered = it)) })
                        }
                        OutlinedTextField(
                            block.items.joinToString("\n"), { onChange(block.copy(items = it.split('\n'))) }, Modifier.fillMaxWidth(), minLines = 2,
                            supportingText = { Text(tr("Un élément par ligne")) },
                        )
                    }
                    is Block.Checklist -> {
                        block.items.forEachIndexed { i, check ->
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Checkbox(check.done, { done -> onChange(block.copy(items = block.items.toMutableList().also { it[i] = check.copy(done = done) })) })
                                OutlinedTextField(
                                    check.text, { text -> onChange(block.copy(items = block.items.toMutableList().also { it[i] = check.copy(text = text.replace('\n', ' ')) })) },
                                    Modifier.weight(1f), singleLine = true,
                                )
                                IconButton(onClick = { onChange(block.copy(items = block.items.toMutableList().also { it.removeAt(i) })) }, enabled = block.items.size > 1) {
                                    Icon(Icons.Filled.Delete, tr("Supprimer"))
                                }
                            }
                        }
                        TextButton(onClick = { onChange(block.copy(items = block.items + CheckItem("", false))) }) { Text(tr("+ Ajouter une case")) }
                    }
                    is Block.Table -> TableEditor(block, onChange)
                    is Block.Image -> ImageEditor(block, http, onChange, onReplaceImage)
                    Block.Divider -> HorizontalDivider(Modifier.padding(vertical = 8.dp))
                    Block.PageBreak -> Text(tr("La suite commence sur une nouvelle page (diapositive en PowerPoint)."), style = MaterialTheme.typography.bodySmall)
                }
            }
        }
    }
}

@Composable
private fun TableEditor(block: Block.Table, onChange: (Block) -> Unit) {
    val columns = block.rows.maxOfOrNull { it.size }?.coerceAtLeast(1) ?: 1
    val rows = block.rows.map { row -> List(columns) { row.getOrElse(it) { "" } } }
    Column(Modifier.horizontalScroll(rememberScrollState())) {
        rows.forEachIndexed { r, row ->
            Row {
                row.forEachIndexed { c, cell ->
                    OutlinedTextField(
                        cell,
                        { text -> onChange(Block.Table(rows.mapIndexed { rr, line -> if (rr == r) line.toMutableList().also { it[c] = text.replace('\n', ' ') } else line })) },
                        Modifier.width(140.dp).padding(2.dp), singleLine = true,
                        placeholder = if (r == 0) ({ Text(tr("En-tête")) }) else null,
                    )
                }
            }
        }
    }
    Row(Modifier.horizontalScroll(rememberScrollState())) {
        TextButton(onClick = { onChange(Block.Table(rows + listOf(List(columns) { "" }))) }) { Text(tr("+ Ligne")) }
        TextButton(onClick = { onChange(Block.Table(rows.map { it + "" })) }) { Text(tr("+ Colonne")) }
        TextButton(onClick = { onChange(Block.Table(rows.dropLast(1))) }, enabled = rows.size > 1) { Text(tr("− Ligne")) }
        TextButton(onClick = { onChange(Block.Table(rows.map { it.dropLast(1) })) }, enabled = columns > 1) { Text(tr("− Colonne")) }
    }
}

@Composable
private fun ColumnScope.ImageEditor(block: Block.Image, http: OkHttpClient, onChange: (Block) -> Unit, onAddImage: () -> Unit) {
    val context = LocalContext.current
    val preview by produceState<Bitmap?>(null, block.source) {
        value = withContext(Dispatchers.IO) { runCatching { thumbnail(context, block.source, http) }.getOrNull() }
    }
    val picture = preview
    if (picture != null) {
        Image(
            picture.asImageBitmap(), block.caption, contentScale = ContentScale.Fit,
            modifier = Modifier.fillMaxWidth(block.widthPercent / 100f).heightIn(max = 260.dp).align(Alignment.CenterHorizontally),
        )
    } else {
        Text(trf("Image : {0}", block.source.substringAfterLast('/')), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
    OutlinedTextField(block.caption, { onChange(block.copy(caption = it.replace('\n', ' '))) }, Modifier.fillMaxWidth(), label = { Text(tr("Légende")) }, singleLine = true)
    Text(trf("Largeur : {0} %", block.widthPercent), style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 6.dp))
    Slider(
        value = block.widthPercent.toFloat(), onValueChange = { onChange(block.copy(widthPercent = (Math.round(it / 10f) * 10).coerceIn(10, 100))) },
        valueRange = 10f..100f, steps = 8,
    )
    TextButton(onClick = onAddImage) { Text(tr("Ajouter une autre image")) }
}

/** A small copy of a document picture for the editor. */
private fun thumbnail(context: Context, source: String, http: OkHttpClient): Bitmap? {
    val image = loadDocumentImage(context, source, http) ?: return null
    val options = BitmapFactory.Options().apply { inSampleSize = if (maxOf(image.width, image.height) > 1200) 2 else 1 }
    return BitmapFactory.decodeByteArray(image.jpeg, 0, image.jpeg.size, options)
}
