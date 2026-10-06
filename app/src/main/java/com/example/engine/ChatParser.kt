package com.example.engine

import android.util.Xml
import org.json.JSONArray
import org.json.JSONObject
import org.xmlpull.v1.XmlPullParser
import java.io.BufferedReader
import java.io.InputStream
import java.io.InputStreamReader
import java.io.StringReader
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

data class ChatMessage(
    val timestamp: String,
    val sender: String,
    val message: String,
    val isIncoming: Boolean = true,
    val contactName: String = sender,
    val address: String = ""
)

data class ParsedChatConversation(
    val title: String,
    val conversationType: String, // "SMS", "WhatsApp", "Signal", "Generic"
    val messages: List<ChatMessage>
)

class ChatParser {

    companion object {
        // WhatsApp formats:
        // "[12/10/24, 14:32:05] Alice: Hello"
        // "12/10/24, 2:32 PM - Alice: Hello"
        // "2024-10-12 14:32:05 Alice: Hello"
        private val WHATSAPP_BRACKET_REGEX = Regex("""^\[(\d{1,4}[-/.]\d{1,2}[-/.]\d{1,4}[, ]+\d{1,2}:\d{2}(?::\d{2})?(?:\s*[APap][Mm])?)\]\s*([^:]+):\s*(.*)$""")
        private val WHATSAPP_DASH_REGEX = Regex("""^(\d{1,4}[-/.]\d{1,2}[-/.]\d{1,4}[, ]+\d{1,2}:\d{2}(?::\d{2})?(?:\s*[APap][Mm])?)\s*-\s*([^:]+):\s*(.*)$""")
        private val GENERIC_CHAT_REGEX = Regex("""^\[?(\d{1,2}:\d{2}(?::\d{2})?)\]?\s*<([^>]+)>\s*(.*)$""")

        val CHAT_EXTENSIONS = setOf("xml", "json", "txt", "csv", "tsv")

        /**
         * Checks whether a file appears to be a messaging/SMS backup file by name or path.
         */
        fun isLikelyChatBackup(fileName: String): Boolean {
            val lower = fileName.lowercase(Locale.ROOT)
            return lower.contains("sms") ||
                    lower.contains("whatsapp") ||
                    lower.contains("signal") ||
                    lower.contains("telegram") ||
                    lower.contains("chat") ||
                    lower.contains("message") ||
                    lower.contains("imessage") ||
                    lower.startsWith("calls_") ||
                    lower.startsWith("sms_") ||
                    lower.startsWith("smses")
        }
    }

    /**
     * Parses any supported chat or SMS backup stream into semantic vector chunks.
     */
    fun parseChatBackupStream(inputStream: InputStream, fileName: String): List<String> {
        val lower = fileName.lowercase(Locale.ROOT)
        val ext = lower.substringAfterLast('.', "")

        return when {
            ext == "xml" || lower.contains("sms") -> parseSmsBackupXmlOrFallback(inputStream, fileName)
            ext == "json" -> parseSmsJson(inputStream, fileName)
            ext == "csv" || ext == "tsv" -> parseCsvChat(inputStream, fileName)
            else -> parseChatStream(inputStream, fileName)
        }
    }

    /**
     * Parses standard Android "SMS Backup & Restore" XML format.
     * Schema: <smses count=".."><sms protocol="0" address="+.." date="170.." type="1" body=".." contact_name=".." readable_date=".."/> ... </smses>
     */
    fun parseSmsBackupXml(inputStream: InputStream, title: String): List<String> {
        val messages = mutableListOf<ChatMessage>()
        val dateFormat = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault())

        try {
            val parser = Xml.newPullParser()
            parser.setInput(inputStream, "UTF-8")

            var eventType = parser.eventType
            while (eventType != XmlPullParser.END_DOCUMENT) {
                if (eventType == XmlPullParser.START_TAG) {
                    val tagName = parser.name?.lowercase(Locale.ROOT)
                    if (tagName == "sms") {
                        val address = parser.getAttributeValue(null, "address") ?: ""
                        val body = parser.getAttributeValue(null, "body") ?: ""
                        val dateLongStr = parser.getAttributeValue(null, "date")
                        val typeStr = parser.getAttributeValue(null, "type")
                        val contact = parser.getAttributeValue(null, "contact_name") ?: address
                        val readableDate = parser.getAttributeValue(null, "readable_date")

                        val isIncoming = (typeStr == null || typeStr == "1") // 1 = Received, 2 = Sent
                        val senderName = if (isIncoming) {
                            if (contact.isNotBlank() && contact != "(Unknown)") contact else address
                        } else {
                            "Me"
                        }

                        val timeFormatted = readableDate?.takeIf { it.isNotBlank() } ?: run {
                            val timeLong = dateLongStr?.toLongOrNull() ?: 0L
                            if (timeLong > 0) dateFormat.format(Date(timeLong)) else "Recent"
                        }

                        if (body.isNotBlank()) {
                            messages.add(
                                ChatMessage(
                                    timestamp = timeFormatted,
                                    sender = senderName,
                                    message = body,
                                    isIncoming = isIncoming,
                                    contactName = contact,
                                    address = address
                                )
                            )
                        }
                    } else if (tagName == "part") {
                        // MMS text parts
                        val cType = parser.getAttributeValue(null, "ct")
                        val text = parser.getAttributeValue(null, "text")
                        if (cType == "text/plain" && !text.isNullOrBlank()) {
                            messages.add(
                                ChatMessage(
                                    timestamp = "MMS",
                                    sender = "Conversation",
                                    message = text,
                                    isIncoming = true
                                )
                            )
                        }
                    }
                }
                eventType = parser.next()
            }
        } catch (e: Exception) {
            // If XML parsing fails, fallback to general parsing
            return emptyList()
        }

        if (messages.isEmpty()) {
            return emptyList()
        }

        return groupMessagesIntoVectorChunks(messages, "SMS Backup: $title", "SMS")
    }

    private fun parseSmsBackupXmlOrFallback(inputStream: InputStream, fileName: String): List<String> {
        val bytes = inputStream.readBytes()
        val xmlChunks = parseSmsBackupXml(bytes.inputStream(), fileName)
        if (xmlChunks.isNotEmpty()) {
            return xmlChunks
        }
        // Fallback: regular chat stream
        return parseChatStream(bytes.inputStream(), fileName)
    }

    /**
     * Parses JSON SMS/messaging export backups.
     */
    fun parseSmsJson(inputStream: InputStream, title: String): List<String> {
        val messages = mutableListOf<ChatMessage>()
        val dateFormat = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault())

        try {
            val jsonString = inputStream.bufferedReader().use { it.readText() }
            val trimmed = jsonString.trim()

            val jsonArray = if (trimmed.startsWith("[")) {
                JSONArray(trimmed)
            } else {
                val obj = JSONObject(trimmed)
                when {
                    obj.has("messages") -> obj.getJSONArray("messages")
                    obj.has("sms") -> obj.getJSONArray("sms")
                    obj.has("chats") -> obj.getJSONArray("chats")
                    obj.has("conversations") -> obj.getJSONArray("conversations")
                    else -> null
                }
            }

            if (jsonArray != null) {
                for (i in 0 until jsonArray.length()) {
                    val item = jsonArray.optJSONObject(i) ?: continue
                    val body = item.optString("body", item.optString("text", item.optString("message", "")))
                    if (body.isBlank()) continue

                    val address = item.optString("address", item.optString("phone", item.optString("from", "")))
                    val contact = item.optString("contact_name", item.optString("name", item.optString("sender", address)))
                    val type = item.optInt("type", if (item.optBoolean("is_outgoing", false)) 2 else 1)
                    val isIncoming = type != 2

                    val sender = if (isIncoming) {
                        if (contact.isNotBlank()) contact else address.ifBlank { "Contact" }
                    } else {
                        "Me"
                    }

                    val dateVal = item.optLong("date", item.optLong("timestamp", 0L))
                    val dateStr = item.optString("readable_date", item.optString("date_str", ""))
                    val timestamp = if (dateStr.isNotBlank()) {
                        dateStr
                    } else if (dateVal > 0) {
                        dateFormat.format(Date(dateVal))
                    } else {
                        "Unknown date"
                    }

                    messages.add(
                        ChatMessage(
                            timestamp = timestamp,
                            sender = sender,
                            message = body,
                            isIncoming = isIncoming,
                            contactName = contact,
                            address = address
                        )
                    )
                }
            }
        } catch (e: Exception) {
            return emptyList()
        }

        if (messages.isEmpty()) {
            return emptyList()
        }

        return groupMessagesIntoVectorChunks(messages, "Messaging Backup: $title", "JSON Chat")
    }

    /**
     * Parses CSV or TSV chat and message backups.
     */
    fun parseCsvChat(inputStream: InputStream, title: String): List<String> {
        val lines = inputStream.bufferedReader().readLines()
        if (lines.isEmpty()) return emptyList()

        val messages = mutableListOf<ChatMessage>()
        val header = lines.first().lowercase(Locale.ROOT)
        val delimiter = if (header.contains("\t") || title.endsWith(".tsv", ignoreCase = true)) "\t" else ","

        for (line in lines.drop(1)) {
            val parts = line.split(delimiter)
            if (parts.size >= 2) {
                val time = parts.getOrNull(0)?.trim()?.removeSurrounding("\"") ?: ""
                val sender = parts.getOrNull(1)?.trim()?.removeSurrounding("\"") ?: "Unknown"
                val body = parts.drop(2).joinToString(delimiter).trim().removeSurrounding("\"")
                if (body.isNotBlank()) {
                    messages.add(
                        ChatMessage(
                            timestamp = time,
                            sender = sender,
                            message = body,
                            isIncoming = !sender.equals("me", ignoreCase = true)
                        )
                    )
                }
            }
        }

        if (messages.isEmpty()) {
            return chunkRawChatText(lines, title)
        }

        return groupMessagesIntoVectorChunks(messages, "Chat History: $title", "CSV Chat")
    }

    /**
     * Parses WhatsApp text export files or generic chat logs.
     */
    fun parseChatStream(inputStream: InputStream, chatTitle: String): List<String> {
        val lines = inputStream.bufferedReader().readLines()
        val messages = mutableListOf<ChatMessage>()

        var currentMsg: ChatMessage? = null

        for (line in lines) {
            val trimmed = line.trim()
            if (trimmed.isEmpty()) continue

            val match1 = WHATSAPP_BRACKET_REGEX.find(trimmed)
            val match2 = if (match1 == null) WHATSAPP_DASH_REGEX.find(trimmed) else null
            val match3 = if (match1 == null && match2 == null) GENERIC_CHAT_REGEX.find(trimmed) else null

            val match = match1 ?: match2 ?: match3

            if (match != null) {
                if (currentMsg != null) {
                    messages.add(currentMsg)
                }
                val time = match.groupValues[1].trim()
                val sender = match.groupValues[2].trim()
                val text = match.groupValues[3].trim()
                currentMsg = ChatMessage(time, sender, text, isIncoming = !sender.equals("You", ignoreCase = true) && !sender.equals("Me", ignoreCase = true))
            } else {
                // Continuation of previous message
                if (currentMsg != null) {
                    currentMsg = currentMsg.copy(message = currentMsg.message + "\n" + trimmed)
                }
            }
        }
        if (currentMsg != null) {
            messages.add(currentMsg)
        }

        if (messages.isEmpty()) {
            return chunkRawChatText(lines, chatTitle)
        }

        return groupMessagesIntoVectorChunks(messages, "WhatsApp / Chat: $chatTitle", "WhatsApp")
    }

    /**
     * Groups parsed chat messages into semantically rich context blocks.
     * Each chunk contains conversation participants, timeframe, direction (Incoming/Outgoing),
     * and messages with overlap to ensure vector search matches both questions and answers.
     */
    private fun groupMessagesIntoVectorChunks(
        messages: List<ChatMessage>,
        chatTitle: String,
        platform: String
    ): List<String> {
        val chunks = mutableListOf<String>()
        val blockSize = 7
        val stepSize = 4 // 3-message overlap for context continuity

        val distinctParticipants = messages.map { it.sender }.filter { it.isNotBlank() }.distinct()
        val participantHeader = distinctParticipants.take(5).joinToString(", ")

        for (i in messages.indices step stepSize) {
            val block = messages.subList(i, minOf(i + blockSize, messages.size))
            val sb = StringBuilder()
            sb.append("Source: ").append(platform).append(" • ").append(chatTitle).append("\n")
            sb.append("Participants: ").append(participantHeader).append("\n")
            sb.append("Timeframe: ").append(block.first().timestamp).append(" to ").append(block.last().timestamp).append("\n")
            sb.append("---\n")

            for (msg in block) {
                val dir = if (msg.isIncoming) "[Incoming]" else "[Outgoing]"
                sb.append(dir).append(" [").append(msg.timestamp).append("] ")
                    .append(msg.sender).append(": ")
                    .append(msg.message).append("\n")
            }
            chunks.add(sb.toString().trim())
        }

        return chunks
    }

    private fun chunkRawChatText(lines: List<String>, title: String): List<String> {
        val chunks = mutableListOf<String>()
        val batch = StringBuilder()
        batch.append("Chat History: ").append(title).append("\n---\n")

        for (line in lines) {
            if (batch.length + line.length + 1 > 900) {
                chunks.add(batch.toString().trim())
                batch.clear()
                batch.append("Chat History: ").append(title).append(" (cont.)\n---\n")
            }
            batch.append(line).append("\n")
        }
        if (batch.isNotEmpty()) {
            chunks.add(batch.toString().trim())
        }
        return chunks
    }
}
