package com.indsim.editor

import java.io.File

/**
 * ذخیره‌سازی پروژه‌ها به‌صورت فایل JSON در یک پوشه (آفلاین، بدون وابستگی به اندروید).
 * نوشتن اتمیک است: ابتدا فایل موقت، سپس جایگزینی؛ تا قطع برق وسط ذخیره‌ی خودکار فایل را خراب نکند.
 */
class ProjectStore(private val dir: File) {
    init { dir.mkdirs() }

    private fun fileFor(name: String) = File(dir, safeName(name) + EXT)

    fun list(): List<String> =
        dir.listFiles { f -> f.isFile && f.name.endsWith(EXT) }
            ?.sortedByDescending { it.lastModified() }
            ?.map { it.name.removeSuffix(EXT) }
            ?: emptyList()

    fun exists(name: String) = fileFor(name).exists()

    fun save(doc: Document) {
        val target = fileFor(doc.name)
        val tmp = File(dir, target.name + ".tmp")
        tmp.writeText(DocJson.encode(doc), Charsets.UTF_8)
        if (!tmp.renameTo(target)) {
            target.delete()
            check(tmp.renameTo(target)) { "cannot save ${target.name}" }
        }
    }

    fun load(name: String): Document? =
        runCatching { DocJson.decode(fileFor(name).readText(Charsets.UTF_8)) }.getOrNull()

    fun delete(name: String): Boolean = fileFor(name).delete()

    fun setLast(name: String) { File(dir, LAST).writeText(name, Charsets.UTF_8) }
    fun lastName(): String? = runCatching { File(dir, LAST).readText(Charsets.UTF_8).trim() }.getOrNull()?.takeIf { it.isNotEmpty() }

    companion object {
        const val EXT = ".indsim.json"
        private const val LAST = "last.txt"

        /** نام امن برای فایل؛ حروف فارسی و لاتین، عدد، فاصله، خط تیره و زیرخط مجازند. */
        fun safeName(name: String): String =
            name.replace(Regex("[^\\p{L}\\p{N} _-]"), "_").trim().ifEmpty { "project" }.take(60)
    }
}
