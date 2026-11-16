package com.mambadoc.app.data.parser

import android.util.Xml
import org.xmlpull.v1.XmlPullParser
import org.xmlpull.v1.XmlPullParserException
import java.io.InputStream
import java.util.zip.ZipInputStream

/**
 * Pure Kotlin / Android SDK DOCX parser.
 * Zero third-party libraries – ZipInputStream + XmlPullParser only.
 *
 * Handles:
 *  - word/document.xml body paragraphs and runs
 *  - optional headers / footers / footnotes
 *  - tabs, soft/hard line breaks, table cells (tab-separated)
 *  - basic run-level text extraction in reading order
 */
object DocxParser {

    data class ParseResult(
        val text: String,
        val paragraphCount: Int = 0
    )

    fun parse(input: InputStream): ParseResult {
        val sections = mutableListOf<String>()
        ZipInputStream(input).use { zis ->
            var entry = zis.nextEntry
            while (entry != null) {
                val name = entry.name
                if (name == "word/document.xml" ||
                    name.matches(Regex("word/header\\d*\\.xml")) ||
                    name.matches(Regex("word/footer\\d*\\.xml")) ||
                    name == "word/footnotes.xml" ||
                    name == "word/endnotes.xml"
                ) {
                    try {
                        val body = parseDocumentXml(zis)
                        if (body.isNotBlank()) sections.add(body)
                    } catch (_: XmlPullParserException) {
                    } catch (_: Exception) {
                    }
                }
                zis.closeEntry()
                entry = zis.nextEntry
            }
        }
        val full = sections.joinToString("\n\n").trim()
        val paraCount = full.split(Regex("\n{2,}")).count { it.isNotBlank() }
        return ParseResult(full, paraCount)
    }

    private fun parseDocumentXml(stream: InputStream): String {
        val parser = Xml.newPullParser()
        parser.setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, true)
        parser.setInput(stream, "UTF-8")

        val sb = StringBuilder()
        var event = parser.eventType
        var inTextRun = false
        var lastEmittedBreak = true

        while (event != XmlPullParser.END_DOCUMENT) {
            when (event) {
                XmlPullParser.START_TAG -> {
                    when (local(parser)) {
                        "t" -> inTextRun = true
                        "tab" -> {
                            sb.append('\t')
                            lastEmittedBreak = false
                        }
                        "br", "cr" -> {
                            sb.append('\n')
                            lastEmittedBreak = true
                        }
                        "p" -> {
                            if (sb.isNotEmpty() && !lastEmittedBreak) {
                                sb.append('\n')
                                lastEmittedBreak = true
                            }
                        }
                        "tc" -> {
                            if (sb.isNotEmpty()) {
                                val last = sb.last()
                                if (last != '\t' && last != '\n') {
                                    sb.append('\t')
                                    lastEmittedBreak = false
                                }
                            }
                        }
                    }
                }
                XmlPullParser.TEXT -> {
                    if (inTextRun) {
                        val t = parser.text
                        if (!t.isNullOrEmpty()) {
                            sb.append(t)
                            lastEmittedBreak = false
                        }
                    }
                }
                XmlPullParser.END_TAG -> {
                    when (local(parser)) {
                        "t" -> inTextRun = false
                        "tr" -> {
                            if (sb.isNotEmpty() && sb.last() != '\n') {
                                sb.append('\n')
                                lastEmittedBreak = true
                            }
                        }
                        "p" -> {
                            if (sb.isNotEmpty() && sb.last() != '\n') {
                                sb.append('\n')
                                lastEmittedBreak = true
                            }
                        }
                    }
                }
            }
            event = parser.next()
        }
        return sb.toString().trim()
    }

    private fun local(parser: XmlPullParser): String {
        val name = parser.name
        val idx = name.indexOf(':')
        return if (idx >= 0) name.substring(idx + 1) else name
    }
}
