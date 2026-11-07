package com.potato.ffpatcher

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import androidx.documentfile.provider.DocumentFile
import java.io.File
import java.util.zip.ZipInputStream

enum class Game(val label: String, val pkg: String) {
    FF("Free Fire", "com.dts.freefireth"),
    MAX("Free Fire MAX", "com.dts.freefiremax")
}

/**
 * Menyalin isi patch (.zip) ke folder data game lewat Storage Access Framework,
 * dengan backup otomatis file asli agar bisa dipulihkan.
 */
object PatchEngine {

    const val DEFAULT_TARGET = "files/contentcache/Optional/android/gameassetbundles"

    data class Result(val applied: Int, val backedUp: Int, val skipped: Int)

    private class Manifest(var target: String, val entries: LinkedHashMap<String, Char>)

    private fun backupDir(ctx: Context, pkg: String) = File(ctx.filesDir, "backup/$pkg")
    private fun manifestFile(ctx: Context, pkg: String) = File(backupDir(ctx, pkg), "manifest.txt")

    private fun readManifest(ctx: Context, pkg: String): Manifest? {
        val f = manifestFile(ctx, pkg)
        if (!f.exists()) return null
        val lines = f.readLines()
        if (lines.isEmpty()) return null
        val map = LinkedHashMap<String, Char>()
        for (l in lines.drop(1)) {
            if (l.length > 2 && l[1] == '|') map[l.substring(2)] = l[0]
        }
        return Manifest(lines[0].removePrefix("target="), map)
    }

    private fun writeManifest(ctx: Context, pkg: String, m: Manifest) {
        val f = manifestFile(ctx, pkg)
        f.parentFile?.mkdirs()
        f.writeText(buildString {
            append("target=").append(m.target).append('\n')
            m.entries.forEach { (rel, type) -> append(type).append('|').append(rel).append('\n') }
        })
    }

    fun patchedCount(ctx: Context, pkg: String): Int = readManifest(ctx, pkg)?.entries?.size ?: 0

    /** Mengembalikan folder paket game (mis. Android/data/com.dts.freefireth) dari tree yang dipilih. */
    fun resolveBase(ctx: Context, treeUri: Uri, pkg: String): DocumentFile? {
        val root = DocumentFile.fromTreeUri(ctx, treeUri) ?: return null
        val id = try {
            DocumentsContract.getTreeDocumentId(treeUri)
        } catch (e: Exception) {
            return null
        }
        return if (id.endsWith(pkg)) root else root.findFile(pkg)
    }

    private fun ensureDir(start: DocumentFile, path: String): DocumentFile {
        var cur = start
        for (seg in path.split('/').filter { it.isNotEmpty() }) {
            val next = cur.findFile(seg)
            cur = if (next != null && next.isDirectory) next
            else cur.createDirectory(seg) ?: error("Tidak bisa membuat folder '$seg'")
        }
        return cur
    }

    private fun findDir(start: DocumentFile, path: String): DocumentFile? {
        var cur = start
        for (seg in path.split('/').filter { it.isNotEmpty() }) {
            val next = cur.findFile(seg)
            if (next == null || !next.isDirectory) return null
            cur = next
        }
        return cur
    }

    private fun createFile(parent: DocumentFile, name: String): DocumentFile {
        val f = parent.createFile("application/octet-stream", name)
            ?: error("Tidak bisa membuat file '$name'")
        // Beberapa provider menambahkan ekstensi (.bin); paksa nama asli.
        if (f.name != name) f.renameTo(name)
        return f
    }

    /** Hindari zip-slip dan path absolut. */
    private fun safeRel(name: String): String? {
        val n = name.replace('\\', '/').trimStart('/')
        if (n.isEmpty()) return null
        if (n.split('/').any { it == ".." }) return null
        return n
    }

    fun apply(
        ctx: Context,
        pkg: String,
        treeUri: Uri,
        target: String,
        zipUri: Uri,
        log: (String) -> Unit
    ): Result {
        val base = resolveBase(ctx, treeUri, pkg) ?: error("Folder data game tidak valid")
        val tgt = target.trim().trim('/')
        require(tgt.isNotEmpty()) { "Folder tujuan kosong" }

        val old = readManifest(ctx, pkg)
        if (old != null && old.target != tgt) {
            error("Masih ada patch di '${old.target}'. Pulihkan dulu sebelum mengganti folder tujuan.")
        }
        val manifest = old ?: Manifest(tgt, LinkedHashMap())
        val root = ensureDir(base, tgt)
        val bdir = backupDir(ctx, pkg)

        var applied = 0
        var backed = 0
        var skipped = 0

        val raw = ctx.contentResolver.openInputStream(zipUri) ?: error("Tidak bisa membuka file patch")
        try {
            ZipInputStream(raw.buffered()).use { zis ->
                val buf = ByteArray(64 * 1024)
                while (true) {
                    val e = zis.nextEntry ?: break
                    if (e.isDirectory) continue
                    val rel = safeRel(e.name)
                    if (rel == null) {
                        skipped++
                        continue
                    }
                    val dirPart = rel.substringBeforeLast('/', "")
                    val fileName = rel.substringAfterLast('/')
                    val parent = if (dirPart.isEmpty()) root else ensureDir(root, dirPart)
                    var dest = parent.findFile(fileName)

                    if (!manifest.entries.containsKey(rel)) {
                        if (dest != null) {
                            val bf = File(bdir, rel)
                            bf.parentFile?.mkdirs()
                            val src = ctx.contentResolver.openInputStream(dest.uri)
                                ?: error("Tidak bisa membaca file asli '$rel' untuk backup")
                            src.use { i -> bf.outputStream().use { o -> i.copyTo(o) } }
                            manifest.entries[rel] = 'B'
                            backed++
                        } else {
                            manifest.entries[rel] = 'C'
                        }
                    }

                    if (dest == null) dest = createFile(parent, fileName)
                    val out = ctx.contentResolver.openOutputStream(dest.uri, "wt")
                        ?: error("Tidak bisa menulis '$rel'")
                    out.use { o ->
                        while (true) {
                            val n = zis.read(buf)
                            if (n < 0) break
                            o.write(buf, 0, n)
                        }
                    }
                    applied++
                    if (applied % 25 == 0) {
                        writeManifest(ctx, pkg, manifest)
                        log("… $applied file diterapkan")
                    }
                }
            }
        } finally {
            writeManifest(ctx, pkg, manifest)
        }
        return Result(applied, backed, skipped)
    }

    fun restore(ctx: Context, pkg: String, treeUri: Uri, log: (String) -> Unit): Int {
        val m = readManifest(ctx, pkg) ?: error("Tidak ada patch yang tercatat")
        val base = resolveBase(ctx, treeUri, pkg) ?: error("Folder data game tidak valid")
        val root = ensureDir(base, m.target)
        val bdir = backupDir(ctx, pkg)
        var n = 0

        for ((rel, type) in m.entries) {
            val dirPart = rel.substringBeforeLast('/', "")
            val fileName = rel.substringAfterLast('/')
            if (type == 'C') {
                val parent = if (dirPart.isEmpty()) root else findDir(root, dirPart)
                parent?.findFile(fileName)?.delete()
            } else {
                val bf = File(bdir, rel)
                if (!bf.exists()) continue
                val parent = if (dirPart.isEmpty()) root else ensureDir(root, dirPart)
                val dest = parent.findFile(fileName) ?: createFile(parent, fileName)
                val out = ctx.contentResolver.openOutputStream(dest.uri, "wt")
                    ?: error("Tidak bisa menulis '$rel'")
                out.use { o -> bf.inputStream().use { it.copyTo(o) } }
            }
            n++
            if (n % 25 == 0) log("… $n file dipulihkan")
        }
        bdir.deleteRecursively()
        return n
    }
}
