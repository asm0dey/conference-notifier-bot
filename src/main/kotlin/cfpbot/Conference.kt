package cfpbot

import kotlinx.serialization.Serializable
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale

@Serializable
data class Conference(
    val name: String,
    val link: String = "",
    val locationName: String = "",
    val date: String = "",
    val cfpLink: String = "",
    val cfpEndDate: String = "",
)

private val CFP_DATE_FORMAT: DateTimeFormatter =
    DateTimeFormatter.ofPattern("d MMMM yyyy", Locale.ENGLISH)

fun Conference.cfpClose(): LocalDate? =
    cfpEndDate.trim().takeIf { it.isNotEmpty() }?.let { raw ->
        runCatching { LocalDate.parse(raw, CFP_DATE_FORMAT) }.getOrNull()
    }
