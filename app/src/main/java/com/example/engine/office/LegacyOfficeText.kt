package com.example.engine.office

/**
 * Picks the right reader for a `.doc`, `.xls` or `.ppt` file from its first bytes, because the extension
 * often lies: banks export HTML tables as `.xls`, WordPad saves RTF as `.doc`, and renamed `.docx` files
 * are common.
 */
object LegacyOfficeText {

    enum class Kind { COMPOUND, ZIP, RTF, MARKUP, OTHER }

    fun sniff(head: ByteArray): Kind = when {
        CompoundFile.isCompoundFile(head) -> Kind.COMPOUND
        head.size >= 4 && head[0] == 'P'.code.toByte() && head[1] == 'K'.code.toByte() &&
            head[2].toInt() == 3 && head[3].toInt() == 4 -> Kind.ZIP
        RtfExtractor.isRtf(head) -> Kind.RTF
        String(head, 0, minOf(head.size, 512), Charsets.ISO_8859_1).trimStart('﻿', 'ï', '»', '¿', ' ', '\r', '\n', '\t')
            .startsWith("<") -> Kind.MARKUP
        else -> Kind.OTHER
    }

    /** Text of a binary Office file ([extension] is "doc", "xls" or "ppt"); "" when nothing can be read. */
    fun extractCompound(bytes: ByteArray, extension: String): String {
        val file = CompoundFile.open(bytes) ?: return ""
        // Trust the streams inside over the extension.
        return when {
            file.hasStream("WordDocument") -> LegacyWordExtractor.extract(file)
            file.hasStream("Workbook") -> LegacyExcelExtractor.extract(file)
            file.hasStream("PowerPoint Document") -> LegacyPowerPointExtractor.extract(file)
            else -> when (extension) {
                "doc" -> LegacyWordExtractor.extract(file)
                "xls" -> LegacyExcelExtractor.extract(file)
                "ppt" -> LegacyPowerPointExtractor.extract(file)
                else -> ""
            }
        }
    }
}
