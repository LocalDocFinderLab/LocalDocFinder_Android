package com.example.engine.extraction

/**
 * Layout of the text indexed for a picture: what OCR read from the pixels first, then the photo's
 * details (camera, date, place). Search uses the headers to tell "the word is written in the picture"
 * apart from "the word is in the photo's details".
 */
object ImageText {
    const val OCR_HEADER = "Text in image:"
    const val DETAILS_HEADER = "Photo details:"

    fun compose(recognizedText: String, details: String): String {
        val ocr = recognizedText.trim()
        val info = details.trim()
        return buildString {
            if (ocr.isNotEmpty()) append(OCR_HEADER).append('\n').append(ocr)
            if (info.isNotEmpty()) {
                if (isNotEmpty()) append("\n\n")
                append(DETAILS_HEADER).append(' ').append(info)
            }
        }
    }

    /**
     * The OCR part of one indexed chunk of a picture. Long OCR text is split over several chunks, so a chunk
     * without either header is OCR text too.
     */
    fun recognizedPart(chunkText: String): String {
        val details = chunkText.indexOf(DETAILS_HEADER)
        val ocr = if (details >= 0) chunkText.substring(0, details) else chunkText
        return ocr.replace(OCR_HEADER, " ").trim()
    }
}
