package com.jarvis.android.filemanager

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import android.webkit.MimeTypeMap
import androidx.documentfile.provider.DocumentFile

/** A [FileTree] on a folder the user granted through the system folder picker. Nothing outside it is reachable. */
internal class DocumentTree(private val context: Context, treeUri: Uri) : FileTree {
    private val root: DocumentFile? = DocumentFile.fromTreeUri(context, treeUri)
    private val resolver get() = context.contentResolver

    private fun find(path: List<String>): DocumentFile? {
        var current = root ?: return null
        for (segment in path) current = current.findFile(segment) ?: return null
        return current
    }

    private fun node(file: DocumentFile) = FileNode(file.name.orEmpty(), file.isDirectory, file.length(), file.lastModified())

    override fun list(dir: List<String>): List<FileNode>? =
        find(dir)?.takeIf { it.isDirectory }?.listFiles()?.map(::node)

    override fun stat(path: List<String>): FileNode? = find(path)?.let(::node)

    override fun read(path: List<String>, maxBytes: Int): ByteArray? = try {
        find(path)?.takeIf { it.isFile }?.let { f -> resolver.openInputStream(f.uri)?.use { it.readNBytes(maxBytes) } }
    } catch (_: Exception) {
        null
    }

    private fun mimeFor(name: String): String =
        MimeTypeMap.getSingleton().getMimeTypeFromExtension(name.substringAfterLast('.', "").lowercase()) ?: "application/octet-stream"

    override fun write(path: List<String>, bytes: ByteArray): Boolean = try {
        val existing = find(path)
        val file = existing ?: find(path.dropLast(1))?.createFile(mimeFor(path.last()), path.last())
        file != null && resolver.openOutputStream(file.uri, "wt")?.use { it.write(bytes); true } == true
    } catch (_: Exception) {
        false
    }

    override fun mkdir(path: List<String>): Boolean = try {
        if (find(path)?.isDirectory == true) true
        else find(path.dropLast(1))?.createDirectory(path.last()) != null
    } catch (_: Exception) {
        false
    }

    override fun delete(path: List<String>): Boolean = try {
        path.isNotEmpty() && find(path)?.delete() == true
    } catch (_: Exception) {
        false
    }

    override fun rename(path: List<String>, newName: String): Boolean = try {
        path.isNotEmpty() && find(path)?.renameTo(newName) == true
    } catch (_: Exception) {
        false
    }

    override fun move(path: List<String>, destDir: List<String>): Boolean = try {
        val file = find(path)
        val from = find(path.dropLast(1))
        val to = find(destDir)
        if (file == null || from == null || to == null || !to.isDirectory) false
        else if (DocumentsContract.moveDocument(resolver, file.uri, from.uri, to.uri) != null) true
        else copy(path, destDir) && delete(path)
    } catch (_: Exception) {
        // The provider cannot move: fall back to copy then delete.
        copy(path, destDir) && delete(path)
    }

    override fun copy(path: List<String>, destDir: List<String>): Boolean = try {
        val file = find(path)
        val to = find(destDir)
        if (file == null || to == null || !to.isDirectory) false else copyInto(file, to, 0)
    } catch (_: Exception) {
        false
    }

    private fun copyInto(file: DocumentFile, targetDir: DocumentFile, depth: Int): Boolean {
        val name = file.name ?: return false
        if (depth > MAX_DEPTH) return false
        if (file.isDirectory) {
            val created = targetDir.createDirectory(name) ?: return false
            return file.listFiles().all { copyInto(it, created, depth + 1) }
        }
        val out = targetDir.createFile(mimeFor(name), name) ?: return false
        return resolver.openInputStream(file.uri)?.use { input ->
            resolver.openOutputStream(out.uri, "wt")?.use { output -> input.copyTo(output); true }
        } == true
    }
}
