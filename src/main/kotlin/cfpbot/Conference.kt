package cfpbot

import kotlinx.serialization.Serializable
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale

@Serializable
data class Coordinates(val lat: Double = 0.0, val lon: Double = 0.0)

@Serializable
data class Conference(
    val name: String,
    val link: String = "",
    val locationName: String = "",
    val date: String = "",
    val cfpLink: String = "",
    val cfpEndDate: String = "",
    val coordinates: Coordinates? = null,
)

private val CFP_DATE_FORMAT: DateTimeFormatter =
    DateTimeFormatter.ofPattern("d MMMM yyyy", Locale.ENGLISH)

fun Conference.cfpClose(): LocalDate? =
    cfpEndDate.trim().takeIf { it.isNotEmpty() }?.let { raw ->
        runCatching { LocalDate.parse(raw, CFP_DATE_FORMAT) }.getOrNull()
    }

fun Conference.hasMap(): Boolean =
    coordinates?.let { it.lat != 0.0 || it.lon != 0.0 } == true

fun Conference.mapsUrl(): String? =
    coordinates?.takeIf { hasMap() }?.let { "https://maps.google.com/?q=${it.lat},${it.lon}" }
