package com.rb.app.export

import com.rb.app.data.InventorySnapshot
import java.io.FilterOutputStream
import java.io.OutputStream
import java.io.OutputStreamWriter
import java.time.LocalDate
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/** Small, streaming ECMA-376 workbook writer using Android's built-in ZIP library.
 * Every value is an inline string with text number format: no formulas or numeric coercion.
 * Never closes the caller's stream; the caller must close it to detect provider write errors.
 */
object InventoryXlsx {
    const val MIME = "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"
    fun filename(date: LocalDate = LocalDate.now()) = "Rb_Inventory_$date.xlsx"
    private const val NS = "http://schemas.openxmlformats.org/spreadsheetml/2006/main"
    private const val REL = "http://schemas.openxmlformats.org/officeDocument/2006/relationships"
    private const val MAX_ROWS = 1_048_576

    fun write(snapshot: InventorySnapshot, output: OutputStream) {
        val grouped = snapshot.sims.groupBy { it.groupId }
        val groupedRows = snapshot.groups.sumOf { 3L + grouped[it.id].orEmpty().size } + 2 + grouped[null].orEmpty().size
        if (groupedRows > MAX_ROWS || snapshot.sims.size >= MAX_ROWS) throw XlsxLimitException("تجاوز المخزون الحد الأقصى لصفوف Excel")
        if (snapshot.sims.any { it.serial.length > 32767 }) throw XlsxLimitException("يوجد رقم أطول من حد خلية Excel؛ تعذر التصدير دون اختصاره.")
        ZipOutputStream(object : FilterOutputStream(output) {
            override fun close() { flush() }
            override fun write(bytes: ByteArray, offset: Int, length: Int) { out.write(bytes, offset, length) }
        }).use { zip ->
            fun part(path: String, body: (OutputStreamWriter) -> Unit) {
                zip.putNextEntry(ZipEntry(path))
                val writer = OutputStreamWriter(zip, Charsets.UTF_8)
                writer.write("<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>")
                body(writer); writer.flush(); zip.closeEntry()
            }
            fun xml(path: String, value: String) = part(path) { it.write(value) }
            xml("[Content_Types].xml", """<Types xmlns="http://schemas.openxmlformats.org/package/2006/content-types"><Default Extension="rels" ContentType="application/vnd.openxmlformats-package.relationships+xml"/><Default Extension="xml" ContentType="application/xml"/><Override PartName="/xl/workbook.xml" ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.sheet.main+xml"/><Override PartName="/xl/styles.xml" ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.styles+xml"/><Override PartName="/xl/worksheets/sheet1.xml" ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.worksheet+xml"/><Override PartName="/xl/worksheets/sheet2.xml" ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.worksheet+xml"/></Types>""")
            xml("_rels/.rels", """<Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships"><Relationship Id="rId1" Type="$REL/officeDocument" Target="xl/workbook.xml"/></Relationships>""")
            xml("xl/workbook.xml", """<workbook xmlns="$NS" xmlns:r="$REL"><bookViews><workbookView/></bookViews><sheets><sheet name="المجموعات والشرائح" sheetId="1" r:id="rId1"/><sheet name="جميع الشرائح" sheetId="2" r:id="rId2"/></sheets></workbook>""")
            xml("xl/_rels/workbook.xml.rels", """<Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships"><Relationship Id="rId1" Type="$REL/worksheet" Target="worksheets/sheet1.xml"/><Relationship Id="rId2" Type="$REL/worksheet" Target="worksheets/sheet2.xml"/><Relationship Id="rId3" Type="$REL/styles" Target="styles.xml"/></Relationships>""")
            xml("xl/styles.xml", """<styleSheet xmlns="$NS"><fonts count="2"><font><sz val="11"/><name val="Arial"/><color rgb="FF192D28"/></font><font><b/><sz val="12"/><name val="Arial"/><color rgb="FFFFFFFF"/></font></fonts><fills count="3"><fill><patternFill patternType="none"/></fill><fill><patternFill patternType="gray125"/></fill><fill><patternFill patternType="solid"><fgColor rgb="FF14685D"/><bgColor indexed="64"/></patternFill></fill></fills><borders count="1"><border/></borders><cellStyleXfs count="1"><xf numFmtId="0" fontId="0" fillId="0" borderId="0"/></cellStyleXfs><cellXfs count="4"><xf numFmtId="49" fontId="0" fillId="0" borderId="0" xfId="0" applyNumberFormat="1"><alignment horizontal="left" vertical="center" readingOrder="1"/></xf><xf numFmtId="49" fontId="1" fillId="2" borderId="0" xfId="0" applyNumberFormat="1"><alignment horizontal="right" vertical="center" wrapText="1" readingOrder="2"/></xf><xf numFmtId="49" fontId="0" fillId="0" borderId="0" xfId="0" applyNumberFormat="1"><alignment horizontal="right" vertical="center" wrapText="1" readingOrder="2"/></xf><xf numFmtId="49" fontId="0" fillId="0" borderId="0" xfId="0" applyNumberFormat="1"><alignment horizontal="left" vertical="center" wrapText="1" readingOrder="1"/></xf></cellXfs><cellStyles count="1"><cellStyle name="Normal" xfId="0" builtinId="0"/></cellStyles></styleSheet>""")
            part("xl/worksheets/sheet1.xml") { w ->
                w.write("""<worksheet xmlns="$NS"><sheetViews><sheetView workbookViewId="0" rightToLeft="1"/></sheetViews><sheetFormatPr defaultRowHeight="24"/><cols><col min="1" max="1" width="22" customWidth="1"/><col min="2" max="2" width="72" customWidth="1"/></cols><sheetData>""")
                var row = 0
                val merges = mutableListOf<Int>()
                for (group in snapshot.groups) {
                    val sims = grouped[group.id].orEmpty()
                    merges += ++row
                    w.row(row, listOf("مجموعة ${group.code} — ${group.ownerName} — ${sims.size} شرائح"), listOf(1), 42)
                    w.row(++row, listOf("آخر 4 أرقام", "الرقم الكامل"), listOf(1, 1))
                    sims.forEach { w.row(++row, listOf(it.last4, it.serial), listOf(0, 3), rowHeight(listOf(it.last4, it.serial), listOf(22, 72))) }
                    row++
                }
                merges += ++row
                w.row(row, listOf("الأرشيف العام — ${grouped[null].orEmpty().size} شرائح"), listOf(1), 42)
                w.row(++row, listOf("آخر 4 أرقام", "الرقم الكامل"), listOf(1, 1))
                grouped[null].orEmpty().forEach { w.row(++row, listOf(it.last4, it.serial), listOf(0, 3), rowHeight(listOf(it.last4, it.serial), listOf(22, 72))) }
                w.write("</sheetData><mergeCells count=\"${merges.size}\">")
                merges.forEach { w.write("<mergeCell ref=\"A$it:B$it\"/>") }
                w.write("</mergeCells></worksheet>")
            }
            part("xl/worksheets/sheet2.xml") { w ->
                w.write("""<worksheet xmlns="$NS"><sheetViews><sheetView workbookViewId="0" rightToLeft="1"><pane ySplit="1" topLeftCell="A2" activePane="bottomLeft" state="frozen"/></sheetView></sheetViews><sheetFormatPr defaultRowHeight="28"/><cols><col min="1" max="1" width="42" customWidth="1"/><col min="2" max="2" width="18" customWidth="1"/><col min="3" max="3" width="24" customWidth="1"/><col min="4" max="4" width="32" customWidth="1"/><col min="5" max="5" width="22" customWidth="1"/></cols><sheetData>""")
                w.row(1, listOf("الرقم التسلسلي الكامل", "آخر أربعة أرقام", "رقم المجموعة", "اسم المسؤول", "الحالة"), List(5) { 1 }, 36)
                val groups = snapshot.groups.associateBy { it.id }
                snapshot.sims.forEachIndexed { index, sim ->
                    val group = groups[sim.groupId]
                    val values = listOf(sim.serial, sim.last4, group?.code.orEmpty(), group?.ownerName.orEmpty(), if (sim.groupId == null) "الأرشيف" else "ضمن مجموعة")
                    w.row(index + 2, values, listOf(3, 0, 3, 2, 2), rowHeight(values, listOf(42, 18, 24, 32, 22)))
                }
                w.write("</sheetData><autoFilter ref=\"A1:E${snapshot.sims.size + 1}\"/></worksheet>")
            }
            zip.finish(); zip.flush()
        }
    }

    // Explicit heights keep wrapped long group codes and owner names visible in spreadsheet viewers.
    private fun rowHeight(values: List<String>, widths: List<Int>): Int {
        val lines = values.mapIndexed { index, value ->
            value.split('\n').sumOf { maxOf(1, (it.length + widths[index] - 1) / widths[index]) }
        }.maxOrNull() ?: 1
        return (lines * 16 + 12).coerceIn(28, 409)
    }

    private fun OutputStreamWriter.row(index: Int, values: List<String>, styles: List<Int>, height: Int = 28) {
        write("<row r=\"$index\" ht=\"$height\" customHeight=\"1\">")
        values.forEachIndexed { col, value ->
            write("<c r=\"${'A' + col}$index\" s=\"${styles[col]}\" t=\"inlineStr\"><is><t xml:space=\"preserve\">")
            write(escape(value)); write("</t></is></c>")
        }
        write("</row>")
    }

    // Excel also decodes _xHHHH_ escapes: escape literal underscores first, including formula-like names.
    private fun escape(value: String): String {
        val literal = value.replace(Regex("_x[0-9a-fA-F]{4}_")) { "_x005F_" + it.value.substring(1) }
        return buildString {
            literal.forEach { c -> append(when (c) {
                '&' -> "&amp;"; '<' -> "&lt;"; '>' -> "&gt;"; '"' -> "&quot;"; '\'' -> "&apos;"
                else -> if ((c.code < 32 && c !in "\t\n\r") || c == '\uFFFE' || c == '\uFFFF' || c == '\r') "_x${c.code.toString(16).padStart(4, '0')}_" else c.toString()
            }) }
        }
    }
}

class XlsxLimitException(message: String) : IllegalArgumentException(message)
