package com.example

import com.example.engine.office.CompoundFile
import com.example.engine.office.LegacyExcelExtractor
import com.example.engine.office.LegacyOfficeText
import com.example.engine.office.LegacyPowerPointExtractor
import com.example.engine.office.LegacyWordExtractor
import com.example.engine.office.RtfExtractor
import com.example.engine.office.XlsxExtractor
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Fixtures in test/resources/office were written by LibreOffice (doc/xls/ppt) and openpyxl (xlsx). */
class OfficeExtractorsTest {

    private fun bytes(name: String): ByteArray =
        javaClass.classLoader!!.getResourceAsStream("office/$name")!!.use { it.readBytes() }

    private fun compound(name: String): CompoundFile = CompoundFile.open(bytes(name))!!

    @Test
    fun rejectsNonCompoundFiles() {
        assertNull(CompoundFile.open("hello".toByteArray()))
        assertNull(CompoundFile.open(bytes("budget.xlsx")))
    }

    @Test
    fun wordDocumentTextWithoutFieldCodes() {
        val text = LegacyWordExtractor.extract(compound("letter.doc"))
        assertTrue(text, text.contains("Rental Agreement"))
        assertTrue(text, text.contains("Asha Rao and Greenfield Apartments"))
        assertTrue(text, text.contains("the full terms"))
        assertFalse("field code leaked: $text", text.contains("HYPERLINK"))
        assertTrue(text, text.contains("Deposit | 2400\nParking | Included\n"))
        assertTrue(text, text.contains("Résumé attached"))
    }

    @Test
    fun wordDocumentUnicodeText() {
        val text = LegacyWordExtractor.extract(compound("unicode.doc"))
        assertTrue(text, text.contains("Müller GmbH"))
        assertTrue(text, text.contains("東京"))
        assertTrue(text, text.contains("€"))
    }

    @Test
    fun excelSheetsRowsNumbersAndFormulas() {
        val text = LegacyExcelExtractor.extract(compound("budget.xls"))
        assertTrue(text, text.contains("Sheet: Expenses"))
        assertTrue(text, text.contains("Date | Item | Amount"))
        assertTrue(text, text.contains("Rent | 1200"))
        assertTrue(text, text.contains("Groceries Café | 84.25"))
        assertTrue(text, text.contains("Total | 1284.25"))
        assertTrue(text, text.contains("Sheet: Contacts"))
        // Strings late in the shared string table sit in CONTINUE records.
        assertTrue(text, text.contains("Person number 00599 with a reasonably long unique name | 1797"))
        assertTrue(text, text.contains("Final row marker zebra"))
    }

    @Test
    fun xlsxMatchesTheLegacyLayout() {
        val text = XlsxExtractor.extract(bytes("budget.xlsx").inputStream())
        assertTrue(text, text.startsWith("Sheet: Expenses\nDate | Item | Amount\n"))
        assertTrue(text, text.contains("Groceries Café | 84.25"))
        assertTrue(text, text.contains("Sheet: Contacts"))
        assertTrue(text, text.contains("Person number 00599 with a reasonably long unique name | 1797"))
    }

    @Test
    fun powerPointSlidesTextBoxesAndNotes() {
        val text = LegacyPowerPointExtractor.extract(compound("deck.ppt"))
        assertTrue(text, text.contains("Quarterly Review"))
        assertTrue(text, text.contains("Revenue grew 12 percent"))
        assertTrue(text, text.contains("Hire two engineers in Bengaluru"))
        assertTrue(text, text.contains("Speaker note: mention the budget"))
        assertFalse(text, text.contains("Click to edit"))
        assertFalse(text, text.contains("Outline Level"))
    }

    @Test
    fun rkNumbersDecode() {
        assertEquals(1.0, LegacyExcelExtractor.rkValue(0x3FF00000), 0.0)
        assertEquals(12.34, LegacyExcelExtractor.rkValue((1234 shl 2) or 0x03), 1e-9)
        assertEquals("84.25", LegacyExcelExtractor.formatNumber(84.25))
        assertEquals("1200", LegacyExcelExtractor.formatNumber(1200.0))
    }

    @Test
    fun rtfTextWithCodePageAndUnicodeEscapes() {
        val letter = RtfExtractor.extract(bytes("letter.rtf"))
        assertTrue(letter, letter.contains("Rental Agreement"))
        assertTrue(letter, letter.contains("Asha Rao and Greenfield Apartments"))
        assertTrue(letter, letter.contains("Résumé attached"))
        assertFalse(letter, letter.contains("HYPERLINK"))
        assertFalse(letter, letter.contains("Times New Roman"))
        val unicode = RtfExtractor.extract(bytes("unicode.rtf"))
        assertTrue(unicode, unicode.contains("Müller GmbH"))
        assertTrue(unicode, unicode.contains("東京"))
    }

    @Test
    fun formatIsDetectedFromContentNotExtension() {
        assertEquals(LegacyOfficeText.Kind.COMPOUND, LegacyOfficeText.sniff(bytes("letter.doc")))
        assertEquals(LegacyOfficeText.Kind.ZIP, LegacyOfficeText.sniff(bytes("budget.xlsx")))
        assertEquals(LegacyOfficeText.Kind.RTF, LegacyOfficeText.sniff(bytes("letter.rtf")))
        assertEquals(LegacyOfficeText.Kind.MARKUP, LegacyOfficeText.sniff("\uFEFF<html><table>".toByteArray()))
        assertEquals(LegacyOfficeText.Kind.OTHER, LegacyOfficeText.sniff("a,b,c\n1,2,3".toByteArray()))
        // A workbook saved with a .doc extension is still read as a workbook.
        assertTrue(LegacyOfficeText.extractCompound(bytes("budget.xls"), "doc").contains("Sheet: Expenses"))
    }
}
