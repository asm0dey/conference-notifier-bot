package cfpbot

import io.ktor.client.*
import io.ktor.client.request.*
import io.ktor.client.statement.*
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json

class ConferenceSource(
    private val client: HttpClient,
    private val url: String = "https://javaconferences.org/conferences.json",
) {
    private val json = Json { ignoreUnknownKeys = true }

    suspend fun fetch(): List<Conference> {
        val body = client.get(url).bodyAsText()
        return json.decodeFromString(ListSerializer(Conference.serializer()), body)
    }
}
