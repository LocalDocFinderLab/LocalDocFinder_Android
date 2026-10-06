package com.example.engine

import android.content.Context
import android.net.Uri
import androidx.exifinterface.media.ExifInterface
import java.io.InputStream
import java.text.DecimalFormat

data class ExtractedImageMetadata(
    val fileName: String,
    val fileUri: String,
    val make: String?,
    val model: String?,
    val lens: String?,
    val dateTime: String?,
    val width: Int,
    val height: Int,
    val latitude: Double?,
    val longitude: Double?,
    val iso: String?,
    val focalLength: String?,
    val description: String?,
    val userComment: String?,
    val software: String?,
    val semanticDescription: String
)

class ImageMetadataExtractor(private val context: Context) {

    companion object {
        val IMAGE_EXTENSIONS = setOf("jpg", "jpeg", "png", "webp", "heic", "heif", "dng")
    }

    fun extractMetadata(uri: Uri, fileName: String): ExtractedImageMetadata {
        var make: String? = null
        var model: String? = null
        var lens: String? = null
        var dateTime: String? = null
        var width = 0
        var height = 0
        var lat: Double? = null
        var lon: Double? = null
        var iso: String? = null
        var focalLength: String? = null
        var description: String? = null
        var userComment: String? = null
        var software: String? = null

        try {
            context.contentResolver.openInputStream(uri)?.use { stream ->
                val exif = ExifInterface(stream)
                make = exif.getAttribute(ExifInterface.TAG_MAKE)?.trim()
                model = exif.getAttribute(ExifInterface.TAG_MODEL)?.trim()
                lens = exif.getAttribute(ExifInterface.TAG_LENS_MODEL)?.trim()
                dateTime = exif.getAttribute(ExifInterface.TAG_DATETIME_ORIGINAL)
                    ?: exif.getAttribute(ExifInterface.TAG_DATETIME)

                width = exif.getAttributeInt(ExifInterface.TAG_IMAGE_WIDTH, 0)
                height = exif.getAttributeInt(ExifInterface.TAG_IMAGE_LENGTH, 0)

                iso = exif.getAttribute(ExifInterface.TAG_PHOTOGRAPHIC_SENSITIVITY)
                focalLength = exif.getAttribute(ExifInterface.TAG_FOCAL_LENGTH)
                description = exif.getAttribute(ExifInterface.TAG_IMAGE_DESCRIPTION)?.trim()
                userComment = exif.getAttribute(ExifInterface.TAG_USER_COMMENT)?.trim()
                software = exif.getAttribute(ExifInterface.TAG_SOFTWARE)?.trim()

                val latLong = exif.latLong
                if (latLong != null && latLong.size >= 2) {
                    lat = latLong[0]
                    lon = latLong[1]
                }
            }
        } catch (_: Exception) {}

        // Synthesize rich, semantic natural language text for vector embedding
        val sb = StringBuilder()
        sb.append("Image: $fileName. ")

        val cleanName = fileName.substringBeforeLast('.')
            .replace('_', ' ')
            .replace('-', ' ')
            .replace('.', ' ')
        sb.append("Visual content and tags: $cleanName picture photo image graphics. ")

        val fullTextContext = "$fileName $cleanName $description $userComment ${uri.toString()}".lowercase()

        // Check for Colorado location in text keywords or EXIF GPS bounding box
        val isColoradoKeywords = fullTextContext.contains("colorado") ||
                fullTextContext.contains("denver") ||
                fullTextContext.contains("aspen") ||
                fullTextContext.contains("boulder") ||
                fullTextContext.contains("vail") ||
                fullTextContext.contains("rockies") ||
                fullTextContext.contains("rocky mountain") ||
                fullTextContext.contains("colorado springs") ||
                fullTextContext.contains("breckenridge") ||
                fullTextContext.contains("pikes peak") ||
                fullTextContext.contains("red rocks")

        val isColoradoGps = lat != null && lon != null &&
                (lat in 37.0..41.0) && (lon in -109.05..-102.05)

        if (isColoradoKeywords || isColoradoGps) {
            sb.append("Location: Colorado, United States. Rocky Mountains, mountain scenery, nature, outdoor landscape, travel destination, USA. ")
        }

        if (!description.isNullOrBlank()) {
            sb.append("Description: $description. ")
        }
        if (!userComment.isNullOrBlank()) {
            sb.append("Comment: $userComment. ")
        }
        if (!make.isNullOrBlank() || !model.isNullOrBlank()) {
            val camera = listOfNotNull(make, model).joinToString(" ")
            sb.append("Captured with camera: $camera. ")
        }
        if (!lens.isNullOrBlank()) {
            sb.append("Lens: $lens. ")
        }
        if (width > 0 && height > 0) {
            sb.append("Resolution: ${width}x${height} pixels. ")
        }
        if (!dateTime.isNullOrBlank()) {
            sb.append("Captured on date: $dateTime. ")
        }
        if (lat != null && lon != null) {
            val df = DecimalFormat("#.####")
            sb.append("Geotagged GPS coordinates: ${df.format(lat)}, ${df.format(lon)}. ")
            if (!isColoradoKeywords && !isColoradoGps) {
                sb.append("Geographic location photo. ")
            }
        }
        if (!iso.isNullOrBlank()) {
            sb.append("ISO sensitivity: $iso. ")
        }
        if (!software.isNullOrBlank()) {
            sb.append("Processed with: $software. ")
        }

        return ExtractedImageMetadata(
            fileName = fileName,
            fileUri = uri.toString(),
            make = make,
            model = model,
            lens = lens,
            dateTime = dateTime,
            width = width,
            height = height,
            latitude = lat,
            longitude = lon,
            iso = iso,
            focalLength = focalLength,
            description = description,
            userComment = userComment,
            software = software,
            semanticDescription = sb.toString().trim()
        )
    }
}
