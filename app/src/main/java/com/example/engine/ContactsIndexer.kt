package com.example.engine

import android.content.Context
import android.database.Cursor
import android.provider.ContactsContract
import android.util.Log
import com.example.data.local.DocumentChunkDao
import com.example.data.local.DocumentChunkEntity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.InputStream

data class ContactEntry(
    val id: String,
    val name: String,
    val phones: List<String> = emptyList(),
    val emails: List<String> = emptyList(),
    val company: String? = null,
    val title: String? = null,
    val notes: String? = null
) {
    fun toSearchableText(): String {
        val sb = StringBuilder()
        sb.append("Contact: ").append(name).append("\n")
        if (company != null || title != null) {
            sb.append("Organization: ")
            if (title != null) sb.append(title)
            if (company != null) sb.append(if (title != null) " at " else "").append(company)
            sb.append("\n")
        }
        if (phones.isNotEmpty()) {
            sb.append("Phones: ").append(phones.joinToString(", ")).append("\n")
        }
        if (emails.isNotEmpty()) {
            sb.append("Emails: ").append(emails.joinToString(", ")).append("\n")
        }
        if (!notes.isNullOrBlank()) {
            sb.append("Notes: ").append(notes).append("\n")
        }
        return sb.toString().trim()
    }
}

class ContactsIndexer(
    private val context: Context,
    private val dao: DocumentChunkDao,
    private val embeddingEngine: OnDeviceEmbeddingEngine
) {
    companion object {
        private const val TAG = "ContactsIndexer"

        /**
         * Parses standard vCard (.vcf) stream.
         */
        fun parseVcfStream(input: InputStream): List<ContactEntry> {
            val contacts = mutableListOf<ContactEntry>()
            val lines = input.bufferedReader().readLines()

            var currentName: String? = null
            val phones = mutableListOf<String>()
            val emails = mutableListOf<String>()
            var org: String? = null
            var title: String? = null
            var note: String? = null
            var inVcard = false

            for (line in lines) {
                val trimmed = line.trim()
                if (trimmed.equals("BEGIN:VCARD", ignoreCase = true)) {
                    inVcard = true
                    currentName = null
                    phones.clear()
                    emails.clear()
                    org = null
                    title = null
                    note = null
                } else if (trimmed.equals("END:VCARD", ignoreCase = true)) {
                    if (inVcard && !currentName.isNullOrBlank()) {
                        contacts.add(
                            ContactEntry(
                                id = "vcf_${contacts.size + 1}",
                                name = currentName,
                                phones = phones.toList(),
                                emails = emails.toList(),
                                company = org,
                                title = title,
                                notes = note
                            )
                        )
                    }
                    inVcard = false
                } else if (inVcard) {
                    when {
                        trimmed.startsWith("FN:", ignoreCase = true) -> currentName = trimmed.substring(3).trim()
                        trimmed.startsWith("N:", ignoreCase = true) && currentName == null -> {
                            val parts = trimmed.substring(2).split(";")
                            currentName = parts.filter { it.isNotBlank() }.reversed().joinToString(" ").trim()
                        }
                        trimmed.contains("TEL", ignoreCase = true) && trimmed.contains(":") -> {
                            phones.add(trimmed.substringAfter(':').trim())
                        }
                        trimmed.contains("EMAIL", ignoreCase = true) && trimmed.contains(":") -> {
                            emails.add(trimmed.substringAfter(':').trim())
                        }
                        trimmed.startsWith("ORG:", ignoreCase = true) -> org = trimmed.substring(4).trim()
                        trimmed.startsWith("TITLE:", ignoreCase = true) -> title = trimmed.substring(6).trim()
                        trimmed.startsWith("NOTE:", ignoreCase = true) -> note = trimmed.substring(5).trim()
                    }
                }
            }
            return contacts
        }
    }

    /**
     * Reads device contacts via ContactsContract 100% on-device (zero cloud leak),
     * generates neural vector embeddings, and stores them in SQLite.
     */
    suspend fun indexDeviceContacts(
        onProgress: ((current: Int, total: Int, name: String) -> Unit)? = null
    ): Pair<Int, String?> = withContext(Dispatchers.IO) {
        val contactsMap = HashMap<String, ContactEntry>()

        try {
            val resolver = context.contentResolver

            // 1. Query contact names and IDs
            val projection = arrayOf(
                ContactsContract.Contacts._ID,
                ContactsContract.Contacts.DISPLAY_NAME_PRIMARY
            )
            resolver.query(ContactsContract.Contacts.CONTENT_URI, projection, null, null, null)?.use { cursor ->
                val idIdx = cursor.getColumnIndex(ContactsContract.Contacts._ID)
                val nameIdx = cursor.getColumnIndex(ContactsContract.Contacts.DISPLAY_NAME_PRIMARY)

                while (cursor.moveToNext()) {
                    val id = if (idIdx != -1) cursor.getString(idIdx) else null ?: continue
                    val name = if (nameIdx != -1) cursor.getString(nameIdx) else null ?: "Unnamed Contact"
                    contactsMap[id] = ContactEntry(id = id, name = name)
                }
            }

            // 2. Query Phone numbers
            val phoneProjection = arrayOf(
                ContactsContract.CommonDataKinds.Phone.CONTACT_ID,
                ContactsContract.CommonDataKinds.Phone.NUMBER
            )
            resolver.query(ContactsContract.CommonDataKinds.Phone.CONTENT_URI, phoneProjection, null, null, null)?.use { cursor ->
                val idIdx = cursor.getColumnIndex(ContactsContract.CommonDataKinds.Phone.CONTACT_ID)
                val numIdx = cursor.getColumnIndex(ContactsContract.CommonDataKinds.Phone.NUMBER)

                while (cursor.moveToNext()) {
                    val id = if (idIdx != -1) cursor.getString(idIdx) else null ?: continue
                    val num = if (numIdx != -1) cursor.getString(numIdx) else null ?: continue
                    val existing = contactsMap[id]
                    if (existing != null) {
                        contactsMap[id] = existing.copy(phones = (existing.phones + num).distinct())
                    }
                }
            }

            // 3. Query Emails
            val emailProjection = arrayOf(
                ContactsContract.CommonDataKinds.Email.CONTACT_ID,
                ContactsContract.CommonDataKinds.Email.ADDRESS
            )
            resolver.query(ContactsContract.CommonDataKinds.Email.CONTENT_URI, emailProjection, null, null, null)?.use { cursor ->
                val idIdx = cursor.getColumnIndex(ContactsContract.CommonDataKinds.Email.CONTACT_ID)
                val emailIdx = cursor.getColumnIndex(ContactsContract.CommonDataKinds.Email.ADDRESS)

                while (cursor.moveToNext()) {
                    val id = if (idIdx != -1) cursor.getString(idIdx) else null ?: continue
                    val email = if (emailIdx != -1) cursor.getString(emailIdx) else null ?: continue
                    val existing = contactsMap[id]
                    if (existing != null) {
                        contactsMap[id] = existing.copy(emails = (existing.emails + email).distinct())
                    }
                }
            }

            // 4. Query Organization / Job title
            val orgProjection = arrayOf(
                ContactsContract.Data.CONTACT_ID,
                ContactsContract.CommonDataKinds.Organization.COMPANY,
                ContactsContract.CommonDataKinds.Organization.TITLE
            )
            val orgSelection = "${ContactsContract.Data.MIMETYPE} = ?"
            val orgArgs = arrayOf(ContactsContract.CommonDataKinds.Organization.CONTENT_ITEM_TYPE)

            resolver.query(ContactsContract.Data.CONTENT_URI, orgProjection, orgSelection, orgArgs, null)?.use { cursor ->
                val idIdx = cursor.getColumnIndex(ContactsContract.Data.CONTACT_ID)
                val companyIdx = cursor.getColumnIndex(ContactsContract.CommonDataKinds.Organization.COMPANY)
                val titleIdx = cursor.getColumnIndex(ContactsContract.CommonDataKinds.Organization.TITLE)

                while (cursor.moveToNext()) {
                    val id = if (idIdx != -1) cursor.getString(idIdx) else null ?: continue
                    val company = if (companyIdx != -1) cursor.getString(companyIdx) else null
                    val title = if (titleIdx != -1) cursor.getString(titleIdx) else null
                    val existing = contactsMap[id]
                    if (existing != null) {
                        contactsMap[id] = existing.copy(company = company, title = title)
                    }
                }
            }
        } catch (e: SecurityException) {
            return@withContext Pair(0, "Permission denied: READ_CONTACTS is required to index contacts.")
        } catch (e: Exception) {
            Log.e(TAG, "Error querying contacts: ${e.message}", e)
            return@withContext Pair(0, "Error reading contacts: ${e.message}")
        }

        val contactsList = contactsMap.values.toList()
        if (contactsList.isEmpty()) {
            return@withContext Pair(0, "No contacts found on device.")
        }

        val entities = mutableListOf<DocumentChunkEntity>()
        val textsToEmbed = mutableListOf<String>()

        contactsList.forEachIndexed { index, contact ->
            onProgress?.invoke(index + 1, contactsList.size, contact.name)
            val text = contact.toSearchableText()
            textsToEmbed.add(text)
        }

        // Batch embed with on-device embedding engine
        val embeddings = embeddingEngine.embedBatch(textsToEmbed, batchSize = 16)

        contactsList.forEachIndexed { index, contact ->
            val text = textsToEmbed[index]
            val emb = embeddings.getOrNull(index) ?: embeddingEngine.embedText(text)
            val blob = embeddingEngine.floatArrayToByteArray(emb)
            val uriStr = "contact://${contact.id}"

            entities.add(
                DocumentChunkEntity(
                    fileUri = uriStr,
                    fileName = "Contact: ${contact.name}",
                    chunkIndex = 0,
                    chunkText = text,
                    hash = "contact_${contact.id}_${contact.name.hashCode()}",
                    timestamp = System.currentTimeMillis(),
                    embeddingBlob = blob,
                    tags = "Contacts, People"
                )
            )
        }

        // Delete old contact records and insert
        contactsList.forEach {
            dao.deleteFileRecord("contact://${it.id}")
        }
        dao.insertChunksWithFts(entities)
        contactsList.forEach {
            dao.setTagsForFile("contact://${it.id}", listOf("Contacts", "People"))
        }

        Pair(entities.size, null)
    }

    /**
     * Parses standard vCard (.vcf) stream.
     */
    fun parseVcfStream(input: InputStream): List<ContactEntry> {
        val contacts = mutableListOf<ContactEntry>()
        val lines = input.bufferedReader().readLines()

        var currentName: String? = null
        val phones = mutableListOf<String>()
        val emails = mutableListOf<String>()
        var org: String? = null
        var title: String? = null
        var note: String? = null
        var inVcard = false

        for (line in lines) {
            val trimmed = line.trim()
            if (trimmed.equals("BEGIN:VCARD", ignoreCase = true)) {
                inVcard = true
                currentName = null
                phones.clear()
                emails.clear()
                org = null
                title = null
                note = null
            } else if (trimmed.equals("END:VCARD", ignoreCase = true)) {
                if (inVcard && !currentName.isNullOrBlank()) {
                    contacts.add(
                        ContactEntry(
                            id = "vcf_${contacts.size + 1}",
                            name = currentName,
                            phones = phones.toList(),
                            emails = emails.toList(),
                            company = org,
                            title = title,
                            notes = note
                        )
                    )
                }
                inVcard = false
            } else if (inVcard) {
                when {
                    trimmed.startsWith("FN:", ignoreCase = true) -> currentName = trimmed.substring(3).trim()
                    trimmed.startsWith("N:", ignoreCase = true) && currentName == null -> {
                        val parts = trimmed.substring(2).split(";")
                        currentName = parts.filter { it.isNotBlank() }.reversed().joinToString(" ").trim()
                    }
                    trimmed.contains("TEL", ignoreCase = true) && trimmed.contains(":") -> {
                        phones.add(trimmed.substringAfter(':').trim())
                    }
                    trimmed.contains("EMAIL", ignoreCase = true) && trimmed.contains(":") -> {
                        emails.add(trimmed.substringAfter(':').trim())
                    }
                    trimmed.startsWith("ORG:", ignoreCase = true) -> org = trimmed.substring(4).trim()
                    trimmed.startsWith("TITLE:", ignoreCase = true) -> title = trimmed.substring(6).trim()
                    trimmed.startsWith("NOTE:", ignoreCase = true) -> note = trimmed.substring(5).trim()
                }
            }
        }
        return contacts
    }
}
