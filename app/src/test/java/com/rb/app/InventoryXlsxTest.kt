package com.rb.app

import com.rb.app.data.*
import com.rb.app.export.InventoryXlsx
import java.io.*
import java.time.LocalDate
import java.util.zip.ZipInputStream
import javax.xml.parsers.DocumentBuilderFactory
import org.w3c.dom.Document
import org.w3c.dom.Element
import org.junit.Test
import org.junit.Assert.*

class InventoryXlsxTest {
    private fun workbook(snapshot: InventorySnapshot): Map<String, Document> {
        val output = ByteArrayOutputStream()
        InventoryXlsx.write(snapshot, output)
        val bytes = output.toByteArray()
        assertEquals('P'.code.toByte(), bytes[0]); assertEquals('K'.code.toByte(), bytes[1])
        val result = mutableMapOf<String, Document>()
        ZipInputStream(bytes.inputStream()).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: break
                val content = zip.readBytes()
                result[entry.name] = DocumentBuilderFactory.newInstance().apply { isNamespaceAware = true }
                    .newDocumentBuilder().parse(content.inputStream())
            }
        }
        return result
    }
    private fun cells(doc: Document): List<Element> = doc.getElementsByTagName("c").let { nodes -> (0 until nodes.length).map { nodes.item(it) as Element } }
    @Test fun textCellsPreserveLongSerialsLeadingZerosAndFormulaLikeOwners() {
        val owner = "=HYPERLINK(\"https://example.com\") & عوض < >"
        val group = Group(id = "g", code = "0055", ownerName = owner)
        val serials = listOf("0008996603430263612277", "8996603430263612277", "9999999999999999999999999999999999992277")
        val snapshot = InventorySnapshot(listOf(group), serials.mapIndexed { i, s -> Sim(id = "$i", serial = s, groupId = if (i == 2) null else "g") })
        val book = workbook(snapshot)
        assertEquals(setOf("[Content_Types].xml", "_rels/.rels", "xl/workbook.xml", "xl/_rels/workbook.xml.rels", "xl/styles.xml", "xl/worksheets/sheet1.xml", "xl/worksheets/sheet2.xml"), book.keys)
        val flat = book.getValue("xl/worksheets/sheet2.xml")
        val values = cells(flat).associate { it.getAttribute("r") to it.textContent }
        assertEquals(serials[0], values["A2"]); assertEquals(serials[1], values["A3"]); assertEquals(serials[2], values["A4"])
        assertEquals("2277", values["B2"]); assertEquals("2277", values["B4"])
        assertEquals("0055", values["C2"]); assertEquals(owner, values["D2"])
        assertEquals("", values["C4"]); assertEquals("الأرشيف", values["E4"])
        for (path in listOf("xl/worksheets/sheet1.xml", "xl/worksheets/sheet2.xml")) {
            val doc = book.getValue(path)
            assertEquals(0, doc.getElementsByTagName("f").length)
            cells(doc).forEach { assertEquals("inlineStr", it.getAttribute("t")) }
            assertEquals("1", (doc.getElementsByTagName("sheetView").item(0) as Element).getAttribute("rightToLeft"))
        }
        val xfs = book.getValue("xl/styles.xml").getElementsByTagName("cellXfs").item(0).childNodes
        (0 until xfs.length).forEach { assertEquals("49", (xfs.item(it) as Element).getAttribute("numFmtId")) }
        assertEquals("A1:E4", (flat.getElementsByTagName("autoFilter").item(0) as Element).getAttribute("ref"))
        assertEquals("frozen", (flat.getElementsByTagName("pane").item(0) as Element).getAttribute("state"))
        val section = book.getValue("xl/worksheets/sheet1.xml")
        assertTrue(cells(section).any { it.textContent == "مجموعة 0055 — $owner — 2 شرائح" })
        assertTrue(cells(section).any { it.textContent == "الأرشيف العام — 1 شرائح" })
    }
    @Test fun emptyGroupsAndEmptyInventoryStillProduceValidWorkbook() {
        val book = workbook(InventorySnapshot(listOf(Group(id = "empty", code = "0000", ownerName = "مسؤول")), emptyList()))
        assertTrue(cells(book.getValue("xl/worksheets/sheet1.xml")).any { it.textContent.contains("مجموعة 0000 — مسؤول — 0 شرائح") })
        val flat = book.getValue("xl/worksheets/sheet2.xml")
        assertEquals(5, cells(flat).size)
        assertEquals("A1:E1", (flat.getElementsByTagName("autoFilter").item(0) as Element).getAttribute("ref"))
        assertEquals(2, workbook(InventorySnapshot(emptyList(), emptyList())).getValue("xl/workbook.xml").getElementsByTagName("sheet").length)
    }
    @Test fun xmlControlsLiteralExcelEscapesAndArabicAreEncodedWithoutFormulas() {
        for (name in listOf("+SUM(1,2)", "-1+2", "@SUM(A1)", "عوض\u0001\r_x0041_")) {
            val book = workbook(InventorySnapshot(listOf(Group(id = "g", code = "01", ownerName = name)), listOf(Sim(serial = "001", groupId = "g"))))
            val flat = book.getValue("xl/worksheets/sheet2.xml")
            assertEquals(0, flat.getElementsByTagName("f").length)
            val cell = cells(flat).single { it.getAttribute("r") == "D2" }
            assertEquals(if (name.startsWith("عوض")) "عوض_x0001__x000d__x005F_x0041_" else name, cell.textContent)
        }
    }
    @Test fun thousandsOfSimsAppearExactlyOnceInEachSheet() {
        val sims = (1..3000).map { Sim(id = "$it", serial = "000000000000000000${it.toString().padStart(4, '0')}", groupId = if (it % 2 == 0) "g" else null) }
        val book = workbook(InventorySnapshot(listOf(Group(id = "g", code = "0055", ownerName = "عوض")), sims))
        for (path in listOf("xl/worksheets/sheet1.xml", "xl/worksheets/sheet2.xml")) {
            val values = cells(book.getValue(path)).map { it.textContent }
            val counts = values.groupingBy { it }.eachCount()
            sims.forEach { assertEquals(1, counts[it.serial]) }
        }
    }
    @Test fun writeFailuresPropagateAndFilenameUsesGivenDate() {
        assertEquals("Rb_Inventory_2026-11-14.xlsx", InventoryXlsx.filename(LocalDate.of(2026, 11, 14)))
        assertThrows(IOException::class.java) {
            InventoryXlsx.write(InventorySnapshot(emptyList(), emptyList()), object : OutputStream() {
                override fun write(value: Int) { throw IOException("ENOSPC") }
            })
        }
    }
    @Test fun longOwnersAndCodesHaveSpaceToWrapWithoutChangingTheirValues() {
        val group = Group(id = "g", code = "0".repeat(64), ownerName = "مسؤول ".repeat(20))
        val book = workbook(InventorySnapshot(listOf(group), listOf(Sim(serial = "0".repeat(128), groupId = "g"))))
        val flat = book.getValue("xl/worksheets/sheet2.xml")
        val row = flat.getElementsByTagName("row").item(1) as Element
        assertTrue(row.getAttribute("ht").toInt() >= 76)
        assertEquals(group.ownerName, cells(flat).single { it.getAttribute("r") == "D2" }.textContent)
        assertEquals(group.code, cells(flat).single { it.getAttribute("r") == "C2" }.textContent)
    }
    @Test fun independentReaderFixture() {
        val group = Group(id = "fixture", code = "0055", ownerName = "=SUM(1,2) عوض & <مسؤول>")
        val snapshot = InventorySnapshot(listOf(group, Group(id = "empty", code = "0000", ownerName = "أحمد")),
            listOf(Sim(serial = "0008996603430263612277", groupId = group.id),
                Sim(serial = "8996603430263612277", groupId = group.id), Sim(serial = "000099992277")))
        File("build/reports/export").mkdirs()
        File("build/reports/export/inventory.xlsx").outputStream().use { InventoryXlsx.write(snapshot, it) }
    }
    @Test fun overlongSerialFailsBeforeWritingAnything() {
        val out = ByteArrayOutputStream()
        assertThrows(IllegalArgumentException::class.java) { InventoryXlsx.write(InventorySnapshot(emptyList(), listOf(Sim(serial = "0".repeat(32768)))), out) }
        assertEquals(0, out.size())
    }
}
