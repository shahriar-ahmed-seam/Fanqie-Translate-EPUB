package com.example.tomato

import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Reads the names in an ELF shared library's dynamic string table (where exported JNI function names
 * live), so an engine library can be checked against the app's glue before it is ever loaded.
 */
object ElfSymbols {
    private const val SHT_DYNSYM = 11

    /** Exported/imported symbol names, or null when the file isn't a readable ELF library. */
    fun dynamicSymbolNames(file: File): Set<String>? = try {
        RandomAccessFile(file, "r").use { raf -> read(raf) }
    } catch (e: Exception) {
        null
    }

    private fun read(raf: RandomAccessFile): Set<String>? {
        val ident = ByteArray(16)
        raf.readFully(ident)
        if (ident[0] != 0x7f.toByte() || ident[1] != 'E'.code.toByte() || ident[2] != 'L'.code.toByte() || ident[3] != 'F'.code.toByte()) {
            return null
        }
        val is64 = ident[4].toInt() == 2
        val order = if (ident[5].toInt() == 2) ByteOrder.BIG_ENDIAN else ByteOrder.LITTLE_ENDIAN

        val header = ByteBuffer.wrap(readAt(raf, 0, if (is64) 64 else 52)).order(order)
        val sectionOffset = if (is64) header.getLong(0x28) else header.getInt(0x20).toLong() and 0xffffffffL
        val sectionSize = (if (is64) header.getShort(0x3A) else header.getShort(0x2E)).toInt() and 0xffff
        val sectionCount = (if (is64) header.getShort(0x3C) else header.getShort(0x30)).toInt() and 0xffff
        if (sectionOffset <= 0 || sectionCount == 0 || sectionSize == 0) return null

        val sections = ByteBuffer.wrap(readAt(raf, sectionOffset, sectionSize * sectionCount)).order(order)
        fun type(i: Int) = sections.getInt(i * sectionSize + 4)
        fun link(i: Int) = sections.getInt(i * sectionSize + if (is64) 0x28 else 0x18)
        fun offset(i: Int) = if (is64) sections.getLong(i * sectionSize + 0x18) else sections.getInt(i * sectionSize + 0x10).toLong() and 0xffffffffL
        fun size(i: Int) = if (is64) sections.getLong(i * sectionSize + 0x20) else sections.getInt(i * sectionSize + 0x14).toLong() and 0xffffffffL

        val dynsym = (0 until sectionCount).firstOrNull { type(it) == SHT_DYNSYM } ?: return null
        val strtab = link(dynsym)
        if (strtab !in 0 until sectionCount) return null
        val length = size(strtab)
        if (length <= 0 || length > 64L * 1024 * 1024) return null
        val strings = readAt(raf, offset(strtab), length.toInt())

        val names = HashSet<String>()
        var start = 0
        for (i in strings.indices) {
            if (strings[i].toInt() == 0) {
                if (i > start) names.add(String(strings, start, i - start, Charsets.ISO_8859_1))
                start = i + 1
            }
        }
        return names
    }

    private fun readAt(raf: RandomAccessFile, offset: Long, length: Int): ByteArray {
        val bytes = ByteArray(length)
        raf.seek(offset)
        raf.readFully(bytes)
        return bytes
    }
}
