package com.example

import com.example.rcon.RconClient
import io.ktor.server.routing.*
import io.ktor.server.application.*
import io.ktor.server.response.*
import io.ktor.server.request.*
import io.ktor.http.*
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.*
import io.ktor.client.*
import io.ktor.client.engine.cio.*
import io.ktor.client.plugins.contentnegotiation.*
import io.ktor.client.request.*
import io.ktor.client.request.forms.FormDataContent
import io.ktor.client.statement.*
import io.ktor.serialization.kotlinx.json.*
import kotlinx.coroutines.*
import kotlinx.datetime.Clock

private val client = HttpClient(CIO) {
    install(ContentNegotiation) {
        json()
    }
}

@Serializable
data class DiscordGuildData(
    val approximate_member_count: Int? = null,
    val approximate_presence_count: Int? = null
)

@Serializable
data class SteamUserProfile(
    val steamIdData: String,
    val name: String,
    val avatar: String,
    val profile: String
)

@Serializable
data class RustPlayer(
    val id: String,
    val name: String,
    val ping: String,
    val connected: String,
    val ip: String,
    val ownerSteamID: String
)

@Serializable
data class UserCreateRequest(
    val name: String,
    val role: String,
    val gender: String,
    val age: Int
)

@Serializable
data class User(
    val id: String,
    val name: String,
    val role: String,
    val gender: String,
    val age: Int
)

// Новые data классы для RCON ответов
@Serializable
data class RconResponse(
    val success: Boolean,
    val raw_response: String? = null,
    val response_length: Int? = null,
    val response_lines: Int? = null,
    val error: String? = null,
    val error_type: String? = null
)

@Serializable
data class RconDebugResponse(
    val raw_response: String,
    val players_block: String,
    val players_block_length: Int,
    val parsed_players: List<RustPlayer>,
    val players_count: Int,
    val raw_lines: List<String>,
    val block_lines: List<String>,
    val error: String? = null,
    val stack_trace: String? = null
)

// Новые data классы для парсинга RCON ответа
@Serializable
data class RconServerResponse(
    val Message: String,
    val Identifier: Int,
    val Type: String,
    val Stacktrace: String
)
@Serializable
data class LeaderboardEntry(
    val steamId: String,
    val name: String,
    val value: Int,
    val statType: String
)

@Serializable
data class LeaderboardResponse(
    val statType: String,
    val limit: Int,
    val totalPlayers: Int,
    val leaderboard: List<LeaderboardEntry>
)
@Serializable
data class ServerInfo(
    val hostname: String,
    val version: String,
    val map: String,
    val players: Int,
    val maxPlayers: Int,
    val queued: Int,
    val joining: Int,
    val playersList: List<RustPlayer>
)

@Serializable
data class PlayerStatistics(
    val steamId: String,
    val lastUpdate: Long,
    val joins: Int,
    val leaves: Int,
    val kills: Int,
    val deaths: Int,
    val suicides: Int,
    val shots: Int,
    val headshots: Int,
    val experiments: Int,
    val recoveries: Int,
    val voiceBytes: Long,
    val woundedTimes: Int,
    val craftedItems: Int,
    val repairedItems: Int,
    val liftUsages: Int,
    val wheelSpins: Int,
    val hammerHits: Int,
    val explosivesThrown: Int,
    val weaponReloads: Int,
    val rocketsLaunched: Int,
    val secondsPlayed: Long,
    val names: List<String>,
    val ips: List<String>,
    val timeStamps: List<Long>,
    val gathered: Map<String, Int>,
    val collectiblePickups: Map<String, Int>,
    val plantPickups: Map<String, Int>,
    val lastUpdatedInDb: String,
    val rawResponse: String? = null
)

@Serializable
data class StatisticsCollectionResult(
    val success: Boolean,
    val totalPlayers: Int,
    val successfullyProcessed: Int,
    val errors: List<String>,
    val processingTimeMs: Long
)
suspend fun getAllSteamIdsFromPlayerData(): List<String> {
    return try {
        val response = client.get(Config.RUST_PLAYER_DATA_COLLECTION)
        if (response.status != HttpStatusCode.OK) {
            println("❌ Failed to fetch player data: ${response.status}")
            return emptyList()
        }

        val json = Json.parseToJsonElement(response.bodyAsText()).jsonObject
        val documents = json["documents"]?.jsonArray ?: return emptyList()

        val steamIds = mutableListOf<String>()
        for (doc in documents) {
            val fields = doc.jsonObject["fields"]?.jsonObject ?: continue
            val steamId = fields["steamId"]?.jsonObject?.get("stringValue")?.jsonPrimitive?.content
            if (!steamId.isNullOrBlank()) {
                steamIds.add(steamId)
            }
        }

        println("✅ Found ${steamIds.size} Steam IDs in player data")
        steamIds

    } catch (e: Exception) {
        println("❌ Error fetching Steam IDs: ${e.message}")
        e.printStackTrace()
        emptyList()
    }
}

// Функция для парсинга статистики из RCON ответа
fun parsePlayerStatistics(steamId: String, rawResponse: String): PlayerStatistics? {
    return try {
        val rconResponse = Json.decodeFromString<RconServerResponse>(rawResponse)
        val message = rconResponse.Message

        // Извлекаем JSON из сообщения (он находится между фигурными скобками)
        val jsonStart = message.indexOf("{")
        val jsonEnd = message.lastIndexOf("}") + 1

        if (jsonStart == -1 || jsonEnd <= jsonStart) {
            println("⚠️ No JSON found in statistics response for Steam ID: $steamId")
            println("Raw message: $message")
            return null
        }

        val jsonString = message.substring(jsonStart, jsonEnd)
        println("📊 Extracted JSON for $steamId: $jsonString")

        // Парсим JSON статистики
        val statisticsJson = Json.parseToJsonElement(jsonString).jsonObject

        // Извлекаем данные с безопасной обработкой
        val lastUpdate = statisticsJson["LastUpdate"]?.jsonPrimitive?.longOrNull ?: 0L
        val joins = statisticsJson["Joins"]?.jsonPrimitive?.intOrNull ?: 0
        val leaves = statisticsJson["Leaves"]?.jsonPrimitive?.intOrNull ?: 0
        val kills = statisticsJson["Kills"]?.jsonPrimitive?.intOrNull ?: 0
        val deaths = statisticsJson["Deaths"]?.jsonPrimitive?.intOrNull ?: 0
        val suicides = statisticsJson["Suicides"]?.jsonPrimitive?.intOrNull ?: 0
        val shots = statisticsJson["Shots"]?.jsonPrimitive?.intOrNull ?: 0
        val headshots = statisticsJson["Headshots"]?.jsonPrimitive?.intOrNull ?: 0
        val experiments = statisticsJson["Experiments"]?.jsonPrimitive?.intOrNull ?: 0
        val recoveries = statisticsJson["Recoveries"]?.jsonPrimitive?.intOrNull ?: 0
        val voiceBytes = statisticsJson["VoiceBytes"]?.jsonPrimitive?.longOrNull ?: 0L
        val woundedTimes = statisticsJson["WoundedTimes"]?.jsonPrimitive?.intOrNull ?: 0
        val craftedItems = statisticsJson["CraftedItems"]?.jsonPrimitive?.intOrNull ?: 0
        val repairedItems = statisticsJson["RepairedItems"]?.jsonPrimitive?.intOrNull ?: 0
        val liftUsages = statisticsJson["LiftUsages"]?.jsonPrimitive?.intOrNull ?: 0
        val wheelSpins = statisticsJson["WheelSpins"]?.jsonPrimitive?.intOrNull ?: 0
        val hammerHits = statisticsJson["HammerHits"]?.jsonPrimitive?.intOrNull ?: 0
        val explosivesThrown = statisticsJson["ExplosivesThrown"]?.jsonPrimitive?.intOrNull ?: 0
        val weaponReloads = statisticsJson["WeaponReloads"]?.jsonPrimitive?.intOrNull ?: 0
        val rocketsLaunched = statisticsJson["RocketsLaunched"]?.jsonPrimitive?.intOrNull ?: 0
        val secondsPlayed = statisticsJson["SecondsPlayed"]?.jsonPrimitive?.longOrNull ?: 0L

        // Извлекаем массивы
        val names = statisticsJson["Names"]?.jsonArray?.mapNotNull {
            it.jsonPrimitive?.contentOrNull
        } ?: emptyList()

        val ips = statisticsJson["IPs"]?.jsonArray?.mapNotNull {
            it.jsonPrimitive?.contentOrNull
        } ?: emptyList()

        val timeStamps = statisticsJson["TimeStamps"]?.jsonArray?.mapNotNull {
            it.jsonPrimitive?.longOrNull
        } ?: emptyList()

        // Извлекаем объекты с ресурсами
        val gathered = statisticsJson["Gathered"]?.jsonObject?.mapNotNull { (key, value) ->
            value.jsonPrimitive?.intOrNull?.let { key to it }
        }?.toMap() ?: emptyMap()

        val collectiblePickups = statisticsJson["CollectiblePickups"]?.jsonObject?.mapNotNull { (key, value) ->
            value.jsonPrimitive?.intOrNull?.let { key to it }
        }?.toMap() ?: emptyMap()

        val plantPickups = statisticsJson["PlantPickups"]?.jsonObject?.mapNotNull { (key, value) ->
            value.jsonPrimitive?.intOrNull?.let { key to it }
        }?.toMap() ?: emptyMap()

        PlayerStatistics(
            steamId = steamId,
            lastUpdate = lastUpdate,
            joins = joins,
            leaves = leaves,
            kills = kills,
            deaths = deaths,
            suicides = suicides,
            shots = shots,
            headshots = headshots,
            experiments = experiments,
            recoveries = recoveries,
            voiceBytes = voiceBytes,
            woundedTimes = woundedTimes,
            craftedItems = craftedItems,
            repairedItems = repairedItems,
            liftUsages = liftUsages,
            wheelSpins = wheelSpins,
            hammerHits = hammerHits,
            explosivesThrown = explosivesThrown,
            weaponReloads = weaponReloads,
            rocketsLaunched = rocketsLaunched,
            secondsPlayed = secondsPlayed,
            names = names,
            ips = ips,
            timeStamps = timeStamps,
            gathered = gathered,
            collectiblePickups = collectiblePickups,
            plantPickups = plantPickups,
            lastUpdatedInDb = Clock.System.now().toString(),
            rawResponse = rawResponse
        )

    } catch (e: Exception) {
        println("❌ Error parsing statistics for $steamId: ${e.message}")
        e.printStackTrace()
        null
    }
}

// Функция для сохранения статистики в Firebase
suspend fun savePlayerStatistics(playerStats: PlayerStatistics): Boolean {
    return try {
        val docUrl = "${Config.RUST_PLAYER_STATS_COLLECTION}/${playerStats.steamId}"
        val now = Clock.System.now().toString()

        // Проверяем, существует ли документ
        val getResponse = client.get(docUrl)

        // Подготавливаем данные gathered для Firestore
        val gatheredObject = buildJsonObject {
            playerStats.gathered.forEach { (key, value) ->
                put(key.replace(".", "_"), buildJsonObject {
                    put("integerValue", value)
                })
            }
        }

        // Подготавливаем данные collectiblePickups для Firestore
        val collectiblePickupsObject = buildJsonObject {
            playerStats.collectiblePickups.forEach { (key, value) ->
                put(key.replace(".", "_"), buildJsonObject {
                    put("integerValue", value)
                })
            }
        }

        // Подготавливаем данные plantPickups для Firestore
        val plantPickupsObject = buildJsonObject {
            playerStats.plantPickups.forEach { (key, value) ->
                put(key.replace(".", "_"), buildJsonObject {
                    put("integerValue", value)
                })
            }
        }

        // Подготавливаем массивы names
        val namesArray = buildJsonArray {
            playerStats.names.forEach { name ->
                addJsonObject { put("stringValue", name) }
            }
        }

        // Подготавливаем массивы IPs
        val ipsArray = buildJsonArray {
            playerStats.ips.forEach { ip ->
                addJsonObject { put("stringValue", ip) }
            }
        }

        // Подготавливаем массивы timestamps
        val timestampsArray = buildJsonArray {
            playerStats.timeStamps.forEach { timestamp ->
                addJsonObject { put("integerValue", timestamp) }
            }
        }

        val requestBody = buildJsonObject {
            put("fields", buildJsonObject {
                put("steamId", buildJsonObject { put("stringValue", playerStats.steamId) })
                put("lastUpdate", buildJsonObject { put("integerValue", playerStats.lastUpdate) })
                put("joins", buildJsonObject { put("integerValue", playerStats.joins) })
                put("leaves", buildJsonObject { put("integerValue", playerStats.leaves) })
                put("kills", buildJsonObject { put("integerValue", playerStats.kills) })
                put("deaths", buildJsonObject { put("integerValue", playerStats.deaths) })
                put("suicides", buildJsonObject { put("integerValue", playerStats.suicides) })
                put("shots", buildJsonObject { put("integerValue", playerStats.shots) })
                put("headshots", buildJsonObject { put("integerValue", playerStats.headshots) })
                put("experiments", buildJsonObject { put("integerValue", playerStats.experiments) })
                put("recoveries", buildJsonObject { put("integerValue", playerStats.recoveries) })
                put("voiceBytes", buildJsonObject { put("integerValue", playerStats.voiceBytes) })
                put("woundedTimes", buildJsonObject { put("integerValue", playerStats.woundedTimes) })
                put("craftedItems", buildJsonObject { put("integerValue", playerStats.craftedItems) })
                put("repairedItems", buildJsonObject { put("integerValue", playerStats.repairedItems) })
                put("liftUsages", buildJsonObject { put("integerValue", playerStats.liftUsages) })
                put("wheelSpins", buildJsonObject { put("integerValue", playerStats.wheelSpins) })
                put("hammerHits", buildJsonObject { put("integerValue", playerStats.hammerHits) })
                put("explosivesThrown", buildJsonObject { put("integerValue", playerStats.explosivesThrown) })
                put("weaponReloads", buildJsonObject { put("integerValue", playerStats.weaponReloads) })
                put("rocketsLaunched", buildJsonObject { put("integerValue", playerStats.rocketsLaunched) })
                put("secondsPlayed", buildJsonObject { put("integerValue", playerStats.secondsPlayed) })

                // Массивы
                put("names", buildJsonObject {
                    put("arrayValue", buildJsonObject {
                        put("values", namesArray)
                    })
                })
                put("ips", buildJsonObject {
                    put("arrayValue", buildJsonObject {
                        put("values", ipsArray)
                    })
                })
                put("timeStamps", buildJsonObject {
                    put("arrayValue", buildJsonObject {
                        put("values", timestampsArray)
                    })
                })

                // Объекты с ресурсами
                put("gathered", buildJsonObject {
                    put("mapValue", buildJsonObject {
                        put("fields", gatheredObject)
                    })
                })
                put("collectiblePickups", buildJsonObject {
                    put("mapValue", buildJsonObject {
                        put("fields", collectiblePickupsObject)
                    })
                })
                put("plantPickups", buildJsonObject {
                    put("mapValue", buildJsonObject {
                        put("fields", plantPickupsObject)
                    })
                })

                // Метаданные
                put("lastUpdatedInDb", buildJsonObject { put("timestampValue", now) })
                put("rawResponse", buildJsonObject { put("stringValue", playerStats.rawResponse ?: "") })

                // Если документ не существует, добавляем дату создания
                if (getResponse.status != HttpStatusCode.OK) {
                    put("createdAt", buildJsonObject { put("timestampValue", now) })
                }
            })
        }

        val saveResponse = client.patch(docUrl) {
            contentType(ContentType.Application.Json)
            setBody(requestBody)
        }

        if (saveResponse.status.isSuccess()) {
            val action = if (getResponse.status == HttpStatusCode.OK) "Updated" else "Created"
            println("✅ $action statistics for Steam ID: ${playerStats.steamId} (${playerStats.kills} kills, ${playerStats.deaths} deaths)")
            true
        } else {
            println("❌ Failed to save statistics for ${playerStats.steamId}: ${saveResponse.status}")
            println("Response: ${saveResponse.bodyAsText()}")
            false
        }

    } catch (e: Exception) {
        println("❌ Error saving statistics for ${playerStats.steamId}: ${e.message}")
        e.printStackTrace()
        false
    }
}

// Основная функция для сбора статистики всех игроков
suspend fun collectAllPlayersStatistics(): StatisticsCollectionResult {
    val startTime = System.currentTimeMillis()
    val errors = mutableListOf<String>()
    var successfullyProcessed = 0

    return try {
        val rconPassword = System.getenv("RCON_PASSWORD")
        if (rconPassword == null) {
            return StatisticsCollectionResult(
                success = false,
                totalPlayers = 0,
                successfullyProcessed = 0,
                errors = listOf("RCON_PASSWORD not found"),
                processingTimeMs = System.currentTimeMillis() - startTime
            )
        }

        println("🔄 Starting statistics collection...")

        // Получаем все Steam ID
        val steamIds = getAllSteamIdsFromPlayerData()
        if (steamIds.isEmpty()) {
            return StatisticsCollectionResult(
                success = false,
                totalPlayers = 0,
                successfullyProcessed = 0,
                errors = listOf("No Steam IDs found in player data"),
                processingTimeMs = System.currentTimeMillis() - startTime
            )
        }

        println("🔄 Processing ${steamIds.size} players...")
        val rconClient = RconClient("80.242.59.103", 36016, rconPassword)

        // Обрабатываем каждого игрока
        for ((index, steamId) in steamIds.withIndex()) {
            try {
                println("🔄 Processing player ${index + 1}/${steamIds.size}: $steamId")

                // Получаем статистику от сервера
                val rawResponse = rconClient.getPlayerStatistics(steamId)

                // Парсим статистику
                val playerStats = parsePlayerStatistics(steamId, rawResponse)

                if (playerStats != null) {
                    // Сохраняем в базу данных
                    val saved = savePlayerStatistics(playerStats)
                    if (saved) {
                        successfullyProcessed++
                    } else {
                        errors.add("Failed to save statistics for $steamId")
                    }
                } else {
                    errors.add("Failed to parse statistics for $steamId")
                }

                // Добавляем задержку между запросами, чтобы не перегружать сервер
                if (index < steamIds.size - 1) {
                    delay(1000) // 1 секунда между запросами
                }

            } catch (e: Exception) {
                val errorMsg = "Error processing $steamId: ${e.message}"
                println("❌ $errorMsg")
                errors.add(errorMsg)
            }
        }

        val processingTime = System.currentTimeMillis() - startTime
        println("✅ Statistics collection completed: $successfullyProcessed/$steamIds.size processed in ${processingTime}ms")

        StatisticsCollectionResult(
            success = successfullyProcessed > 0,
            totalPlayers = steamIds.size,
            successfullyProcessed = successfullyProcessed,
            errors = errors,
            processingTimeMs = processingTime
        )

    } catch (e: Exception) {
        println("❌ Fatal error in statistics collection: ${e.message}")
        e.printStackTrace()

        StatisticsCollectionResult(
            success = false,
            totalPlayers = 0,
            successfullyProcessed = successfullyProcessed,
            errors = errors + "Fatal error: ${e.message}",
            processingTimeMs = System.currentTimeMillis() - startTime
        )
    }
}
// Firebase и Steam функции
suspend fun getSteamUserFromFirebase(steamId: String): SteamUserProfile? {
    val response = client.get(Config.STEAM_USERS_COLLECTION)
    if (response.status != HttpStatusCode.OK) return null
    val json = Json.parseToJsonElement(response.bodyAsText()).jsonObject
    val docs = json["documents"]?.jsonArray ?: return null
    for (doc in docs) {
        val fields = doc.jsonObject["fields"]?.jsonObject ?: continue
        val sid = fields["steamIdData"]?.jsonObject?.get("stringValue")?.jsonPrimitive?.content ?: continue
        if (sid == steamId) {
            return SteamUserProfile(
                steamIdData = sid,
                name = fields["name"]?.jsonObject?.get("stringValue")?.jsonPrimitive?.content ?: "Unknown",
                avatar = fields["avatar"]?.jsonObject?.get("stringValue")?.jsonPrimitive?.content ?: "",
                profile = fields["profile"]?.jsonObject?.get("stringValue")?.jsonPrimitive?.content ?: ""
            )
        }
    }
    return null
}

suspend fun getDiscordGuildInfo(): DiscordGuildData? {
    return try {
        val response = client.get("https://discord.com/api/v10/guilds/${Config.DISCORD_GUILD_ID}?with_counts=true") {
            headers {
                append("Authorization", "Bot ${Config.DISCORD_BOT_TOKEN}")
            }
        }
        if (response.status == HttpStatusCode.OK) {
            val json = Json.parseToJsonElement(response.bodyAsText()).jsonObject
            DiscordGuildData(
                approximate_member_count = json["approximate_member_count"]?.jsonPrimitive?.intOrNull,
                approximate_presence_count = json["approximate_presence_count"]?.jsonPrimitive?.intOrNull
            )
        } else {
            println("Discord API error: ${response.status}, ${response.bodyAsText()}")
            null
        }
    } catch (e: Exception) {
        e.printStackTrace()
        null
    }
}

// Функции для работы с пользователями
fun parseUser(doc: JsonObject): User {
    val fields = doc["fields"]!!.jsonObject
    return User(
        id = doc["name"]!!.jsonPrimitive.content.split("/").last(),
        name = fields["name"]!!.jsonObject["stringValue"]!!.jsonPrimitive.content,
        role = fields["role"]!!.jsonObject["stringValue"]!!.jsonPrimitive.content,
        gender = fields["gender"]!!.jsonObject["stringValue"]!!.jsonPrimitive.content,
        age = fields["age"]!!.jsonObject["integerValue"]!!.jsonPrimitive.int
    )
}

suspend fun getAllUsers(): List<User> {
    val response: HttpResponse = client.get(Config.BASE_URL)
    if (response.status != HttpStatusCode.OK) return emptyList()
    val json = Json.parseToJsonElement(response.bodyAsText()).jsonObject
    val documents = json["documents"]?.jsonArray ?: return emptyList()
    return documents.map { parseUser(it.jsonObject) }
}

suspend fun getUserById(id: String): User? {
    val response = client.get("${Config.BASE_URL}/$id")
    if (response.status != HttpStatusCode.OK) return null
    val json = Json.parseToJsonElement(response.bodyAsText()).jsonObject
    return parseUser(json)
}

suspend fun deleteUserById(id: String): Boolean {
    val response = client.delete("${Config.BASE_URL}/$id")
    return response.status == HttpStatusCode.OK
}

suspend fun addUser(user: UserCreateRequest): User? {
    val firestoreData = buildJsonObject {
        put("fields", buildJsonObject {
            put("name", buildJsonObject { put("stringValue", user.name) })
            put("role", buildJsonObject { put("stringValue", user.role) })
            put("gender", buildJsonObject { put("stringValue", user.gender) })
            put("age", buildJsonObject { put("integerValue", user.age) })
        })
    }
    val response = client.post(Config.BASE_URL) {
        contentType(ContentType.Application.Json)
        setBody(firestoreData)
    }
    if (response.status != HttpStatusCode.OK) return null
    val json = Json.parseToJsonElement(response.bodyAsText()).jsonObject
    return parseUser(json)
}

// Steam авторизация
fun Route.steamAuthRoutes() {
    get("/steam/login") {
        val returnUrl = "${Config.SERVER_URL}/steam/callback"
        val realm = Config.SERVER_URL
        val redirectUrl = buildString {
            append("https://steamcommunity.com/openid/login?")
            append("openid.ns=http://specs.openid.net/auth/2.0")
            append("&openid.mode=checkid_setup")
            append("&openid.return_to=$returnUrl")
            append("&openid.realm=$realm")
            append("&openid.identity=http://specs.openid.net/auth/2.0/identifier_select")
            append("&openid.claimed_id=http://specs.openid.net/auth/2.0/identifier_select")
        }
        call.respondRedirect(redirectUrl)
    }

    get("/steam/callback") {
        val params = call.request.queryParameters
        val steamOpenIdUrl = "https://steamcommunity.com/openid/login"

        val assocHandle = params["openid.assoc_handle"] ?: return@get call.respondText("Missing assoc_handle")
        val signed = params["openid.signed"] ?: return@get call.respondText("Missing signed")
        val sig = params["openid.sig"] ?: return@get call.respondText("Missing sig")
        val ns = params["openid.ns"] ?: return@get call.respondText("Missing ns")

        val verifyResponse = client.post(steamOpenIdUrl) {
            contentType(ContentType.Application.FormUrlEncoded)
            setBody(FormDataContent(Parameters.build {
                append("openid.assoc_handle", assocHandle)
                append("openid.signed", signed)
                append("openid.sig", sig)
                append("openid.ns", ns)
                signed.split(",").forEach {
                    val value = params["openid.$it"] ?: ""
                    append("openid.$it", value)
                }
                append("openid.mode", "check_authentication")
            }))
        }
        val body = verifyResponse.bodyAsText()
        if (body.contains("is_valid:true")) {
            val claimedId = params["openid.claimed_id"]
            val steamId = claimedId?.substringAfterLast("/") ?: return@get call.respondText("Steam ID не найден")

            val exists = checkIfSteamUserExists(steamId)
            if (!exists) {
                saveSteamUser(steamId)
            }

            call.respondRedirect("https://konurarust.com/?steamId=$steamId")

        } else {
            println("Steam verification failed: $body")
            call.respondText("Ошибка авторизации через Steam")
        }
    }
}

suspend fun checkIfSteamUserExists(steamId: String): Boolean {
    val response = client.get("${Config.STEAM_USERS_COLLECTION}/$steamId")
    return response.status == HttpStatusCode.OK
}

suspend fun saveSteamUser(steamId: String) {
    val apiKey = Config.STEAM_API_KEY
    val now = Clock.System.now().toString()

    val response = client.get("https://api.steampowered.com/ISteamUser/GetPlayerSummaries/v2/?key=$apiKey&steamids=$steamId")
    val json = Json.parseToJsonElement(response.bodyAsText()).jsonObject
    val player = json["response"]?.jsonObject
        ?.get("players")?.jsonArray?.firstOrNull()?.jsonObject

    val name = player?.get("personaname")?.jsonPrimitive?.content ?: "Unknown"
    val avatar = player?.get("avatarfull")?.jsonPrimitive?.content ?: ""
    val profileUrl = player?.get("profileurl")?.jsonPrimitive?.content ?: ""

    val data = buildJsonObject {
        put("fields", buildJsonObject {
            put("steamIdData", buildJsonObject { put("stringValue", steamId) })
            put("name", buildJsonObject { put("stringValue", name) })
            put("avatar", buildJsonObject { put("stringValue", avatar) })
            put("profile", buildJsonObject { put("stringValue", profileUrl) })
            put("joined", buildJsonObject { put("stringValue", now) })
        })
    }

    try {
        val patchResponse = client.patch("${Config.STEAM_USERS_COLLECTION}/$steamId") {
            contentType(ContentType.Application.Json)
            setBody(data)
        }
        if (patchResponse.status.isSuccess()) {
            println("Steam user $steamId saved/updated successfully.")
        } else {
            println("Failed to save/update Steam user $steamId: ${patchResponse.status}, ${patchResponse.bodyAsText()}")
        }
    } catch (e: Exception) {
        println("Exception while saving/updating Steam user $steamId: ${e.message}")
        e.printStackTrace()
    }
}

// Обновленные функции парсинга RCON
fun extractPlayersBlock(jsonResponse: String): String {
    return try {
        val rconResponse = Json.decodeFromString<RconServerResponse>(jsonResponse)
        val message = rconResponse.Message

        val startIndex = message.indexOf("id                name")
        if (startIndex == -1) {
            println("Players header not found in message")
            ""
        } else {
            val playersSection = message.substring(startIndex).trim()
            println("Extracted players section: '$playersSection'")
            playersSection
        }

    } catch (e: Exception) {
        println("Error parsing RCON JSON response: ${e.message}")
        ""
    }
}

fun parsePlayerLine(line: String): RustPlayer? {
    val trimmedLine = line.trim()

    if (trimmedLine.isEmpty() || trimmedLine.startsWith("id                name")) {
        return null
    }

    println("Parsing line: '$trimmedLine'")

    val regex = Regex("""(\S+)\s+"([^"]+)"\s+(\d+)\s+(\S+)\s+(\S+)\s+(\S+)\s+(\S+)""")
    val matchResult = regex.find(trimmedLine)

    return if (matchResult != null) {
        val groups = matchResult.groupValues
        RustPlayer(
            id = groups[1],
            name = groups[2],
            ping = groups[3],
            connected = groups[4],
            ip = groups[5],
            ownerSteamID = groups[1]
        )
    } else {
        println("Failed to parse player line: '$trimmedLine'")
        null
    }
}

fun parsePlayers(block: String): List<RustPlayer> {
    val lines = block.lines()
    val players = mutableListOf<RustPlayer>()

    for (line in lines) {
        val player = parsePlayerLine(line)
        if (player != null) {
            players.add(player)
            println("Parsed player: ${player.name} (${player.id})")
        }
    }

    println("Total players parsed: ${players.size}")
    return players
}

fun parseServerInfo(jsonResponse: String): ServerInfo? {
    return try {
        val rconResponse = Json.decodeFromString<RconServerResponse>(jsonResponse)
        val message = rconResponse.Message

        val lines = message.lines()
        var hostname = ""
        var version = ""
        var map = ""
        var players = 0
        var maxPlayers = 0
        var queued = 0
        var joining = 0

        for (line in lines) {
            when {
                line.startsWith("hostname:") -> {
                    hostname = line.substringAfter("hostname:").trim()
                }
                line.startsWith("version :") -> {
                    version = line.substringAfter("version :").trim()
                }
                line.startsWith("map     :") -> {
                    map = line.substringAfter("map     :").trim()
                }
                line.startsWith("players :") -> {
                    val playerInfo = line.substringAfter("players :").trim()
                    val regex = Regex("""(\d+) \((\d+) max\) \((\d+) queued\) \((\d+) joining\)""")
                    val matchResult = regex.find(playerInfo)
                    if (matchResult != null) {
                        players = matchResult.groupValues[1].toInt()
                        maxPlayers = matchResult.groupValues[2].toInt()
                        queued = matchResult.groupValues[3].toInt()
                        joining = matchResult.groupValues[4].toInt()
                    }
                }
            }
        }

        val playersBlock = extractPlayersBlock(jsonResponse)
        val playersList = parsePlayers(playersBlock)

        ServerInfo(
            hostname = hostname,
            version = version,
            map = map,
            players = players,
            maxPlayers = maxPlayers,
            queued = queued,
            joining = joining,
            playersList = playersList
        )

    } catch (e: Exception) {
        println("Error parsing server info: ${e.message}")
        e.printStackTrace()
        null
    }
}

suspend fun savePlayersToFirebase(players: List<RustPlayer>) {
    for (player in players) {
        val docUrl = "${Config.RUST_PLAYERS_COLLECTION}/${player.id}"
        val getResponse = client.get(docUrl)

        if (getResponse.status == HttpStatusCode.OK) {
            val updateBody = buildJsonObject {
                put("fields", buildJsonObject {
                    put("name", buildJsonObject {
                        put("arrayValue", buildJsonObject {
                            put("values", buildJsonArray {
                                addJsonObject { put("stringValue", player.name) }
                            })
                        })
                    })
                })
            }
            client.patch(docUrl) {
                contentType(ContentType.Application.Json)
                setBody(updateBody)
            }
        } else {
            val createBody = buildJsonObject {
                put("fields", buildJsonObject {
                    put("name", buildJsonObject {
                        put("arrayValue", buildJsonObject {
                            put("values", buildJsonArray {
                                addJsonObject { put("stringValue", player.name) }
                            })
                        })
                    })
                    put("createdAt", buildJsonObject {
                        put("timestampValue", Clock.System.now().toString())
                    })
                })
            }
            client.patch(docUrl) {
                contentType(ContentType.Application.Json)
                setBody(createBody)
            }
        }
    }
}

// Новая функция для сохранения детальных данных игроков в rust_player_data
suspend fun savePlayersDataToFirebase(players: List<RustPlayer>) {
    val now = Clock.System.now().toString()

    for (player in players) {
        try {
            val docUrl = "${Config.RUST_PLAYER_DATA_COLLECTION}/${player.id}"
            val getResponse = client.get(docUrl)

            if (getResponse.status == HttpStatusCode.OK) {
                // Документ существует - добавляем новую сессию в историю
                val existingDoc = Json.parseToJsonElement(getResponse.bodyAsText()).jsonObject
                val fields = existingDoc["fields"]?.jsonObject
                val existingSessions = fields?.get("sessions")?.jsonObject?.get("arrayValue")?.jsonObject?.get("values")?.jsonArray ?: buildJsonArray {}

                // Создаем новую сессию
                val newSession = buildJsonObject {
                    put("mapValue", buildJsonObject {
                        put("fields", buildJsonObject {
                            put("name", buildJsonObject { put("stringValue", player.name) })
                            put("ping", buildJsonObject { put("stringValue", player.ping) })
                            put("connected", buildJsonObject { put("stringValue", player.connected) })
                            put("ip", buildJsonObject { put("stringValue", player.ip) })
                            put("timestamp", buildJsonObject { put("timestampValue", now) })
                        })
                    })
                }

                // Добавляем новую сессию к существующим
                val updatedSessions = buildJsonArray {
                    existingSessions.forEach { add(it) }
                    add(newSession)
                }

                val updateBody = buildJsonObject {
                    put("fields", buildJsonObject {
                        put("steamId", buildJsonObject { put("stringValue", player.id) })
                        put("currentName", buildJsonObject { put("stringValue", player.name) })
                        put("lastSeen", buildJsonObject { put("timestampValue", now) })
                        put("sessions", buildJsonObject {
                            put("arrayValue", buildJsonObject {
                                put("values", updatedSessions)
                            })
                        })
                    })
                }

                client.patch(docUrl) {
                    contentType(ContentType.Application.Json)
                    setBody(updateBody)
                }

            } else {
                // Документ не существует - создаем новый
                val createBody = buildJsonObject {
                    put("fields", buildJsonObject {
                        put("steamId", buildJsonObject { put("stringValue", player.id) })
                        put("currentName", buildJsonObject { put("stringValue", player.name) })
                        put("firstSeen", buildJsonObject { put("timestampValue", now) })
                        put("lastSeen", buildJsonObject { put("timestampValue", now) })
                        put("sessions", buildJsonObject {
                            put("arrayValue", buildJsonObject {
                                put("values", buildJsonArray {
                                    addJsonObject {
                                        put("mapValue", buildJsonObject {
                                            put("fields", buildJsonObject {
                                                put("name", buildJsonObject { put("stringValue", player.name) })
                                                put("ping", buildJsonObject { put("stringValue", player.ping) })
                                                put("connected", buildJsonObject { put("stringValue", player.connected) })
                                                put("ip", buildJsonObject { put("stringValue", player.ip) })
                                                put("timestamp", buildJsonObject { put("timestampValue", now) })
                                            })
                                        })
                                    }
                                })
                            })
                        })
                    })
                }

                client.patch(docUrl) {
                    contentType(ContentType.Application.Json)
                    setBody(createBody)
                }
            }

            println("✅ Saved player data: ${player.name} (${player.id})")

        } catch (e: Exception) {
            println("❌ Error saving player data for ${player.name}: ${e.message}")
            e.printStackTrace()
        }
    }
}

// RCON роуты
fun Route.rconRoutes() {
    get("/rcon/leaderboard/{statType}/{limit}") {
        val statType = call.parameters["statType"] ?: return@get call.respond(
            HttpStatusCode.BadRequest,
            mapOf("error" to "Stat type is required (kills, deaths, shots, headshots, etc.)")
        )

        val limit = call.parameters["limit"]?.toIntOrNull() ?: return@get call.respond(
            HttpStatusCode.BadRequest,
            mapOf("error" to "Limit must be a valid number")
        )

        try {
            val response = client.get(Config.RUST_PLAYER_STATS_COLLECTION)
            if (response.status != HttpStatusCode.OK) {
                return@get call.respond(HttpStatusCode.InternalServerError, mapOf("error" to "Failed to fetch statistics data"))
            }

            val json = Json.parseToJsonElement(response.bodyAsText()).jsonObject
            val documents = json["documents"]?.jsonArray ?: return@get call.respond(
                LeaderboardResponse(
                    statType = statType,
                    limit = limit,
                    totalPlayers = 0,
                    leaderboard = emptyList()
                )
            )

            val leaderboard = mutableListOf<LeaderboardEntry>()

            for (doc in documents) {
                val fields = doc.jsonObject["fields"]?.jsonObject ?: continue
                val steamId = fields["steamId"]?.jsonObject?.get("stringValue")?.jsonPrimitive?.content ?: continue
                val names = fields["names"]?.jsonObject?.get("arrayValue")?.jsonObject?.get("values")?.jsonArray?.mapNotNull {
                    it.jsonObject["stringValue"]?.jsonPrimitive?.content
                } ?: emptyList()

                val currentName = names.lastOrNull() ?: "Unknown"

                val statValue = when (statType.lowercase()) {
                    "kills" -> fields["kills"]?.jsonObject?.get("integerValue")?.jsonPrimitive?.intOrNull ?: 0
                    "deaths" -> fields["deaths"]?.jsonObject?.get("integerValue")?.jsonPrimitive?.intOrNull ?: 0
                    "shots" -> fields["shots"]?.jsonObject?.get("integerValue")?.jsonPrimitive?.intOrNull ?: 0
                    "headshots" -> fields["headshots"]?.jsonObject?.get("integerValue")?.jsonPrimitive?.intOrNull ?: 0
                    "secondsplayed" -> fields["secondsPlayed"]?.jsonObject?.get("integerValue")?.jsonPrimitive?.intOrNull ?: 0
                    "crafteditems" -> fields["craftedItems"]?.jsonObject?.get("integerValue")?.jsonPrimitive?.intOrNull ?: 0
                    "joins" -> fields["joins"]?.jsonObject?.get("integerValue")?.jsonPrimitive?.intOrNull ?: 0
                    "kdr" -> {
                        val kills = fields["kills"]?.jsonObject?.get("integerValue")?.jsonPrimitive?.intOrNull ?: 0
                        val deaths = fields["deaths"]?.jsonObject?.get("integerValue")?.jsonPrimitive?.intOrNull ?: 0
                        if (deaths > 0) (kills.toDouble() / deaths * 100).toInt() else kills * 100
                    }
                    "accuracy" -> {
                        val shots = fields["shots"]?.jsonObject?.get("integerValue")?.jsonPrimitive?.intOrNull ?: 0
                        val headshots = fields["headshots"]?.jsonObject?.get("integerValue")?.jsonPrimitive?.intOrNull ?: 0
                        if (shots > 0) (headshots.toDouble() / shots * 100).toInt() else 0
                    }
                    else -> {
                        call.respond(HttpStatusCode.BadRequest, mapOf("error" to "Unknown stat type: $statType"))
                        return@get
                    }
                }

                leaderboard.add(
                    LeaderboardEntry(
                        steamId = steamId,
                        name = currentName,
                        value = statValue,
                        statType = statType
                    )
                )
            }

            // Сортируем по убыванию и ограничиваем количество
            val sortedLeaderboard = leaderboard.sortedByDescending { it.value }.take(limit)

            val leaderboardResponse = LeaderboardResponse(
                statType = statType,
                limit = limit,
                totalPlayers = leaderboard.size,
                leaderboard = sortedLeaderboard
            )

            call.respond(leaderboardResponse)

        } catch (e: Exception) {
            println("❌ Error getting leaderboard: ${e.message}")
            e.printStackTrace()
            call.respond(HttpStatusCode.InternalServerError, mapOf("error" to e.message))
        }
    }

    // Совместимость со старым endpoint (лимит в query parameter)
    get("/rcon/leaderboard/{statType}") {
        val statType = call.parameters["statType"] ?: return@get call.respond(
            HttpStatusCode.BadRequest,
            mapOf("error" to "Stat type is required (kills, deaths, shots, headshots, etc.)")
        )

        val limit = call.request.queryParameters["limit"]?.toIntOrNull() ?: 10

        try {
            val response = client.get(Config.RUST_PLAYER_STATS_COLLECTION)
            if (response.status != HttpStatusCode.OK) {
                return@get call.respond(HttpStatusCode.InternalServerError, mapOf("error" to "Failed to fetch statistics data"))
            }

            val json = Json.parseToJsonElement(response.bodyAsText()).jsonObject
            val documents = json["documents"]?.jsonArray ?: return@get call.respond(
                LeaderboardResponse(
                    statType = statType,
                    limit = limit,
                    totalPlayers = 0,
                    leaderboard = emptyList()
                )
            )

            val leaderboard = mutableListOf<LeaderboardEntry>()

            for (doc in documents) {
                val fields = doc.jsonObject["fields"]?.jsonObject ?: continue
                val steamId = fields["steamId"]?.jsonObject?.get("stringValue")?.jsonPrimitive?.content ?: continue
                val names = fields["names"]?.jsonObject?.get("arrayValue")?.jsonObject?.get("values")?.jsonArray?.mapNotNull {
                    it.jsonObject["stringValue"]?.jsonPrimitive?.content
                } ?: emptyList()

                val currentName = names.lastOrNull() ?: "Unknown"

                val statValue = when (statType.lowercase()) {
                    "kills" -> fields["kills"]?.jsonObject?.get("integerValue")?.jsonPrimitive?.intOrNull ?: 0
                    "deaths" -> fields["deaths"]?.jsonObject?.get("integerValue")?.jsonPrimitive?.intOrNull ?: 0
                    "shots" -> fields["shots"]?.jsonObject?.get("integerValue")?.jsonPrimitive?.intOrNull ?: 0
                    "headshots" -> fields["headshots"]?.jsonObject?.get("integerValue")?.jsonPrimitive?.intOrNull ?: 0
                    "secondsplayed" -> fields["secondsPlayed"]?.jsonObject?.get("integerValue")?.jsonPrimitive?.intOrNull ?: 0
                    "crafteditems" -> fields["craftedItems"]?.jsonObject?.get("integerValue")?.jsonPrimitive?.intOrNull ?: 0
                    "joins" -> fields["joins"]?.jsonObject?.get("integerValue")?.jsonPrimitive?.intOrNull ?: 0
                    "kdr" -> {
                        val kills = fields["kills"]?.jsonObject?.get("integerValue")?.jsonPrimitive?.intOrNull ?: 0
                        val deaths = fields["deaths"]?.jsonObject?.get("integerValue")?.jsonPrimitive?.intOrNull ?: 0
                        if (deaths > 0) (kills.toDouble() / deaths * 100).toInt() else kills * 100
                    }
                    "accuracy" -> {
                        val shots = fields["shots"]?.jsonObject?.get("integerValue")?.jsonPrimitive?.intOrNull ?: 0
                        val headshots = fields["headshots"]?.jsonObject?.get("integerValue")?.jsonPrimitive?.intOrNull ?: 0
                        if (shots > 0) (headshots.toDouble() / shots * 100).toInt() else 0
                    }
                    else -> {
                        call.respond(HttpStatusCode.BadRequest, mapOf("error" to "Unknown stat type: $statType"))
                        return@get
                    }
                }

                leaderboard.add(
                    LeaderboardEntry(
                        steamId = steamId,
                        name = currentName,
                        value = statValue,
                        statType = statType
                    )
                )
            }

            // Сортируем по убыванию и ограничиваем количество
            val sortedLeaderboard = leaderboard.sortedByDescending { it.value }.take(limit)

            val leaderboardResponse = LeaderboardResponse(
                statType = statType,
                limit = limit,
                totalPlayers = leaderboard.size,
                leaderboard = sortedLeaderboard
            )

            call.respond(leaderboardResponse)

        } catch (e: Exception) {
            println("❌ Error getting leaderboard: ${e.message}")
            e.printStackTrace()
            call.respond(HttpStatusCode.InternalServerError, mapOf("error" to e.message))
        }
    }
    // Только получение данных игроков (без записи в БД)
    get("/rcon/player-stats/{steamId}") {
        val steamId = call.parameters["steamId"] ?: return@get call.respond(
            HttpStatusCode.BadRequest,
            RconResponse(success = false, error = "Steam ID is required")
        )

        val rconPassword = System.getenv("RCON_PASSWORD") ?: return@get call.respond(
            HttpStatusCode.InternalServerError,
            RconResponse(success = false, error = "No RCON_PASSWORD")
        )

        try {
            println("🔄 Getting statistics for Steam ID: $steamId")
            val rconClient = RconClient("80.242.59.103", 36016, rconPassword)
            val rawResponse = rconClient.getPlayerStatistics(steamId)

            val playerStats = parsePlayerStatistics(steamId, rawResponse)

            if (playerStats != null) {
                call.respond(playerStats)
            } else {
                call.respond(HttpStatusCode.NotFound, RconResponse(
                    success = false,
                    error = "No statistics found for Steam ID: $steamId",
                    raw_response = rawResponse
                ))
            }

        } catch (e: Exception) {
            println("❌ Error getting player statistics: ${e.message}")
            e.printStackTrace()
            call.respond(HttpStatusCode.InternalServerError, RconResponse(
                success = false,
                error = e.message,
                error_type = e.javaClass.simpleName
            ))
        }
    }

    // Сбор статистики всех игроков и сохранение в БД
    post("/rcon/collect-all-statistics") {
        val rconPassword = System.getenv("RCON_PASSWORD") ?: return@post call.respond(
            HttpStatusCode.InternalServerError,
            RconResponse(success = false, error = "No RCON_PASSWORD")
        )

        try {
            println("🔄 Starting full statistics collection...")
            val result = collectAllPlayersStatistics()

            call.respond(result)

        } catch (e: Exception) {
            println("❌ Error in statistics collection: ${e.message}")
            e.printStackTrace()
            call.respond(HttpStatusCode.InternalServerError, RconResponse(
                success = false,
                error = e.message,
                error_type = e.javaClass.simpleName
            ))
        }
    }

    // Получение сохраненной статистики игрока из БД
    get("/rcon/saved-stats/{steamId}") {
        val steamId = call.parameters["steamId"] ?: return@get call.respond(
            HttpStatusCode.BadRequest,
            mapOf("error" to "Steam ID is required")
        )

        try {
            val docUrl = "${Config.RUST_PLAYER_STATS_COLLECTION}/$steamId"
            val response = client.get(docUrl)

            if (response.status == HttpStatusCode.OK) {
                val json = Json.parseToJsonElement(response.bodyAsText()).jsonObject
                val fields = json["fields"]?.jsonObject

                if (fields != null) {
                    // Извлекаем базовые поля
                    val steamIdField = fields["steamId"]?.jsonObject?.get("stringValue")?.jsonPrimitive?.content ?: steamId
                    val lastUpdate = fields["lastUpdate"]?.jsonObject?.get("integerValue")?.jsonPrimitive?.longOrNull ?: 0L
                    val joins = fields["joins"]?.jsonObject?.get("integerValue")?.jsonPrimitive?.intOrNull ?: 0
                    val leaves = fields["leaves"]?.jsonObject?.get("integerValue")?.jsonPrimitive?.intOrNull ?: 0
                    val kills = fields["kills"]?.jsonObject?.get("integerValue")?.jsonPrimitive?.intOrNull ?: 0
                    val deaths = fields["deaths"]?.jsonObject?.get("integerValue")?.jsonPrimitive?.intOrNull ?: 0
                    val suicides = fields["suicides"]?.jsonObject?.get("integerValue")?.jsonPrimitive?.intOrNull ?: 0
                    val shots = fields["shots"]?.jsonObject?.get("integerValue")?.jsonPrimitive?.intOrNull ?: 0
                    val headshots = fields["headshots"]?.jsonObject?.get("integerValue")?.jsonPrimitive?.intOrNull ?: 0
                    val experiments = fields["experiments"]?.jsonObject?.get("integerValue")?.jsonPrimitive?.intOrNull ?: 0
                    val recoveries = fields["recoveries"]?.jsonObject?.get("integerValue")?.jsonPrimitive?.intOrNull ?: 0
                    val voiceBytes = fields["voiceBytes"]?.jsonObject?.get("integerValue")?.jsonPrimitive?.longOrNull ?: 0L
                    val woundedTimes = fields["woundedTimes"]?.jsonObject?.get("integerValue")?.jsonPrimitive?.intOrNull ?: 0
                    val craftedItems = fields["craftedItems"]?.jsonObject?.get("integerValue")?.jsonPrimitive?.intOrNull ?: 0
                    val repairedItems = fields["repairedItems"]?.jsonObject?.get("integerValue")?.jsonPrimitive?.intOrNull ?: 0
                    val liftUsages = fields["liftUsages"]?.jsonObject?.get("integerValue")?.jsonPrimitive?.intOrNull ?: 0
                    val wheelSpins = fields["wheelSpins"]?.jsonObject?.get("integerValue")?.jsonPrimitive?.intOrNull ?: 0
                    val hammerHits = fields["hammerHits"]?.jsonObject?.get("integerValue")?.jsonPrimitive?.intOrNull ?: 0
                    val explosivesThrown = fields["explosivesThrown"]?.jsonObject?.get("integerValue")?.jsonPrimitive?.intOrNull ?: 0
                    val weaponReloads = fields["weaponReloads"]?.jsonObject?.get("integerValue")?.jsonPrimitive?.intOrNull ?: 0
                    val rocketsLaunched = fields["rocketsLaunched"]?.jsonObject?.get("integerValue")?.jsonPrimitive?.intOrNull ?: 0
                    val secondsPlayed = fields["secondsPlayed"]?.jsonObject?.get("integerValue")?.jsonPrimitive?.longOrNull ?: 0L
                    val lastUpdatedInDb = fields["lastUpdatedInDb"]?.jsonObject?.get("timestampValue")?.jsonPrimitive?.content ?: ""

                    // Извлекаем массивы
                    val names = fields["names"]?.jsonObject?.get("arrayValue")?.jsonObject?.get("values")?.jsonArray?.mapNotNull {
                        it.jsonObject["stringValue"]?.jsonPrimitive?.content
                    } ?: emptyList()

                    val ips = fields["ips"]?.jsonObject?.get("arrayValue")?.jsonObject?.get("values")?.jsonArray?.mapNotNull {
                        it.jsonObject["stringValue"]?.jsonPrimitive?.content
                    } ?: emptyList()

                    val timeStamps = fields["timeStamps"]?.jsonObject?.get("arrayValue")?.jsonObject?.get("values")?.jsonArray?.mapNotNull {
                        it.jsonObject["integerValue"]?.jsonPrimitive?.longOrNull
                    } ?: emptyList()

                    // Извлекаем объекты с ресурсами
                    val gathered = fields["gathered"]?.jsonObject?.get("mapValue")?.jsonObject?.get("fields")?.jsonObject?.mapNotNull { (key, value) ->
                        value.jsonObject["integerValue"]?.jsonPrimitive?.intOrNull?.let { key.replace("_", ".") to it }
                    }?.toMap() ?: emptyMap()

                    val collectiblePickups = fields["collectiblePickups"]?.jsonObject?.get("mapValue")?.jsonObject?.get("fields")?.jsonObject?.mapNotNull { (key, value) ->
                        value.jsonObject["integerValue"]?.jsonPrimitive?.intOrNull?.let { key.replace("_", ".") to it }
                    }?.toMap() ?: emptyMap()

                    val plantPickups = fields["plantPickups"]?.jsonObject?.get("mapValue")?.jsonObject?.get("fields")?.jsonObject?.mapNotNull { (key, value) ->
                        value.jsonObject["integerValue"]?.jsonPrimitive?.intOrNull?.let { key.replace("_", ".") to it }
                    }?.toMap() ?: emptyMap()

                    val playerStats = PlayerStatistics(
                        steamId = steamIdField,
                        lastUpdate = lastUpdate,
                        joins = joins,
                        leaves = leaves,
                        kills = kills,
                        deaths = deaths,
                        suicides = suicides,
                        shots = shots,
                        headshots = headshots,
                        experiments = experiments,
                        recoveries = recoveries,
                        voiceBytes = voiceBytes,
                        woundedTimes = woundedTimes,
                        craftedItems = craftedItems,
                        repairedItems = repairedItems,
                        liftUsages = liftUsages,
                        wheelSpins = wheelSpins,
                        hammerHits = hammerHits,
                        explosivesThrown = explosivesThrown,
                        weaponReloads = weaponReloads,
                        rocketsLaunched = rocketsLaunched,
                        secondsPlayed = secondsPlayed,
                        names = names,
                        ips = ips,
                        timeStamps = timeStamps,
                        gathered = gathered,
                        collectiblePickups = collectiblePickups,
                        plantPickups = plantPickups,
                        lastUpdatedInDb = lastUpdatedInDb,
                        rawResponse = null
                    )

                    call.respond(playerStats)
                } else {
                    call.respond(HttpStatusCode.NotFound, mapOf("error" to "Statistics not found for Steam ID: $steamId"))
                }
            } else {
                call.respond(HttpStatusCode.NotFound, mapOf("error" to "Statistics not found for Steam ID: $steamId"))
            }

        } catch (e: Exception) {
            println("❌ Error getting saved statistics: ${e.message}")
            e.printStackTrace()
            call.respond(HttpStatusCode.InternalServerError, mapOf("error" to e.message))
        }
    }

    // Получение списка всех игроков, для которых есть статистика
    get("/rcon/stats-players-list") {
        try {
            val response = client.get(Config.RUST_PLAYER_STATS_COLLECTION)
            if (response.status != HttpStatusCode.OK) {
                return@get call.respond(HttpStatusCode.InternalServerError, mapOf("error" to "Failed to fetch statistics data"))
            }

            val json = Json.parseToJsonElement(response.bodyAsText()).jsonObject
            val documents = json["documents"]?.jsonArray ?: return@get call.respond(emptyList<PlayerStatistics>())

            val playerStatsList = mutableListOf<PlayerStatistics>()

            for (doc in documents) {
                val fields = doc.jsonObject["fields"]?.jsonObject ?: continue

                try {
                    val steamId = fields["steamId"]?.jsonObject?.get("stringValue")?.jsonPrimitive?.content ?: continue
                    val lastUpdate = fields["lastUpdate"]?.jsonObject?.get("integerValue")?.jsonPrimitive?.longOrNull ?: 0L
                    val joins = fields["joins"]?.jsonObject?.get("integerValue")?.jsonPrimitive?.intOrNull ?: 0
                    val leaves = fields["leaves"]?.jsonObject?.get("integerValue")?.jsonPrimitive?.intOrNull ?: 0
                    val kills = fields["kills"]?.jsonObject?.get("integerValue")?.jsonPrimitive?.intOrNull ?: 0
                    val deaths = fields["deaths"]?.jsonObject?.get("integerValue")?.jsonPrimitive?.intOrNull ?: 0
                    val suicides = fields["suicides"]?.jsonObject?.get("integerValue")?.jsonPrimitive?.intOrNull ?: 0
                    val shots = fields["shots"]?.jsonObject?.get("integerValue")?.jsonPrimitive?.intOrNull ?: 0
                    val headshots = fields["headshots"]?.jsonObject?.get("integerValue")?.jsonPrimitive?.intOrNull ?: 0
                    val experiments = fields["experiments"]?.jsonObject?.get("integerValue")?.jsonPrimitive?.intOrNull ?: 0
                    val recoveries = fields["recoveries"]?.jsonObject?.get("integerValue")?.jsonPrimitive?.intOrNull ?: 0
                    val voiceBytes = fields["voiceBytes"]?.jsonObject?.get("integerValue")?.jsonPrimitive?.longOrNull ?: 0L
                    val woundedTimes = fields["woundedTimes"]?.jsonObject?.get("integerValue")?.jsonPrimitive?.intOrNull ?: 0
                    val craftedItems = fields["craftedItems"]?.jsonObject?.get("integerValue")?.jsonPrimitive?.intOrNull ?: 0
                    val repairedItems = fields["repairedItems"]?.jsonObject?.get("integerValue")?.jsonPrimitive?.intOrNull ?: 0
                    val liftUsages = fields["liftUsages"]?.jsonObject?.get("integerValue")?.jsonPrimitive?.intOrNull ?: 0
                    val wheelSpins = fields["wheelSpins"]?.jsonObject?.get("integerValue")?.jsonPrimitive?.intOrNull ?: 0
                    val hammerHits = fields["hammerHits"]?.jsonObject?.get("integerValue")?.jsonPrimitive?.intOrNull ?: 0
                    val explosivesThrown = fields["explosivesThrown"]?.jsonObject?.get("integerValue")?.jsonPrimitive?.intOrNull ?: 0
                    val weaponReloads = fields["weaponReloads"]?.jsonObject?.get("integerValue")?.jsonPrimitive?.intOrNull ?: 0
                    val rocketsLaunched = fields["rocketsLaunched"]?.jsonObject?.get("integerValue")?.jsonPrimitive?.intOrNull ?: 0
                    val secondsPlayed = fields["secondsPlayed"]?.jsonObject?.get("integerValue")?.jsonPrimitive?.longOrNull ?: 0L
                    val lastUpdatedInDb = fields["lastUpdatedInDb"]?.jsonObject?.get("timestampValue")?.jsonPrimitive?.content ?: ""

                    val names = fields["names"]?.jsonObject?.get("arrayValue")?.jsonObject?.get("values")?.jsonArray?.mapNotNull {
                        it.jsonObject["stringValue"]?.jsonPrimitive?.content
                    } ?: emptyList()

                    val ips = fields["ips"]?.jsonObject?.get("arrayValue")?.jsonObject?.get("values")?.jsonArray?.mapNotNull {
                        it.jsonObject["stringValue"]?.jsonPrimitive?.content
                    } ?: emptyList()

                    val timeStamps = fields["timeStamps"]?.jsonObject?.get("arrayValue")?.jsonObject?.get("values")?.jsonArray?.mapNotNull {
                        it.jsonObject["integerValue"]?.jsonPrimitive?.longOrNull
                    } ?: emptyList()

                    val gathered = fields["gathered"]?.jsonObject?.get("mapValue")?.jsonObject?.get("fields")?.jsonObject?.mapNotNull { (key, value) ->
                        value.jsonObject["integerValue"]?.jsonPrimitive?.intOrNull?.let { key.replace("_", ".") to it }
                    }?.toMap() ?: emptyMap()

                    val collectiblePickups = fields["collectiblePickups"]?.jsonObject?.get("mapValue")?.jsonObject?.get("fields")?.jsonObject?.mapNotNull { (key, value) ->
                        value.jsonObject["integerValue"]?.jsonPrimitive?.intOrNull?.let { key.replace("_", ".") to it }
                    }?.toMap() ?: emptyMap()

                    val plantPickups = fields["plantPickups"]?.jsonObject?.get("mapValue")?.jsonObject?.get("fields")?.jsonObject?.mapNotNull { (key, value) ->
                        value.jsonObject["integerValue"]?.jsonPrimitive?.intOrNull?.let { key.replace("_", ".") to it }
                    }?.toMap() ?: emptyMap()

                    val playerStats = PlayerStatistics(
                        steamId = steamId,
                        lastUpdate = lastUpdate,
                        joins = joins,
                        leaves = leaves,
                        kills = kills,
                        deaths = deaths,
                        suicides = suicides,
                        shots = shots,
                        headshots = headshots,
                        experiments = experiments,
                        recoveries = recoveries,
                        voiceBytes = voiceBytes,
                        woundedTimes = woundedTimes,
                        craftedItems = craftedItems,
                        repairedItems = repairedItems,
                        liftUsages = liftUsages,
                        wheelSpins = wheelSpins,
                        hammerHits = hammerHits,
                        explosivesThrown = explosivesThrown,
                        weaponReloads = weaponReloads,
                        rocketsLaunched = rocketsLaunched,
                        secondsPlayed = secondsPlayed,
                        names = names,
                        ips = ips,
                        timeStamps = timeStamps,
                        gathered = gathered,
                        collectiblePickups = collectiblePickups,
                        plantPickups = plantPickups,
                        lastUpdatedInDb = lastUpdatedInDb,
                        rawResponse = null
                    )

                    playerStatsList.add(playerStats)

                } catch (e: Exception) {
                    println("❌ Error parsing player stats: ${e.message}")
                    e.printStackTrace()
                    continue
                }
            }

            call.respond(playerStatsList)

        } catch (e: Exception) {
            println("❌ Error in stats-players-list: ${e.message}")
            e.printStackTrace()
            call.respond(HttpStatusCode.InternalServerError, mapOf("error" to e.message))
        }
    }


    // Тестовый эндпоинт для проверки RCON команды statistics.output
    get("/rcon/test-statistics/{steamId}") {
        val steamId = call.parameters["steamId"] ?: return@get call.respond(
            HttpStatusCode.BadRequest,
            RconResponse(success = false, error = "Steam ID is required")
        )

        val rconPassword = System.getenv("RCON_PASSWORD") ?: return@get call.respond(
            HttpStatusCode.InternalServerError,
            RconResponse(success = false, error = "No RCON_PASSWORD")
        )

        try {
            val rconClient = RconClient("80.242.59.103", 36016, rconPassword)
            val rawResponse = rconClient.getPlayerStatistics(steamId)

            call.respond(RconResponse(
                success = true,
                raw_response = rawResponse,
                response_length = rawResponse.length,
                response_lines = rawResponse.lines().size
            ))

        } catch (e: Exception) {
            println("❌ Error testing statistics command: ${e.message}")
            e.printStackTrace()
            call.respond(HttpStatusCode.InternalServerError, RconResponse(
                success = false,
                error = e.message,
                error_type = e.javaClass.simpleName
            ))
        }
    }


    get("/rcon/fetch") {
        val rconPassword = System.getenv("RCON_PASSWORD") ?: return@get call.respond(HttpStatusCode.InternalServerError, "No RCON_PASSWORD")

        try {
            val client = RconClient("80.242.59.103", 36016, rconPassword)
            val rawResponse = client.connectAndFetchStatus()

            val playersBlock = extractPlayersBlock(rawResponse)
            val players = parsePlayers(playersBlock)

            call.respond(players)

        } catch (e: Exception) {
            println("RCON Error: ${e.message}")
            e.printStackTrace()
            call.respond(HttpStatusCode.InternalServerError, mapOf("error" to e.message))
        }
    }

    // Отладочный эндпоинт для просмотра сырого ответа
    get("/rcon/debug") {
        val rconPassword = System.getenv("RCON_PASSWORD") ?: return@get call.respond(
            HttpStatusCode.InternalServerError,
            RconResponse(success = false, error = "No RCON_PASSWORD")
        )

        try {
            val client = RconClient("80.242.59.103", 36016, rconPassword)
            val rawResponse = client.connectAndFetchStatus()

            call.respond(RconResponse(
                success = true,
                raw_response = rawResponse,
                response_length = rawResponse.length,
                response_lines = rawResponse.lines().size
            ))

        } catch (e: Exception) {
            println("RCON Error: ${e.message}")
            e.printStackTrace()

            call.respond(HttpStatusCode.InternalServerError, RconResponse(
                success = false,
                error = e.message,
                error_type = e.javaClass.simpleName
            ))
        }
    }

    // Только получение информации о сервере (без записи в БД)
    get("/rcon/server-info") {
        val rconPassword = System.getenv("RCON_PASSWORD") ?: return@get call.respond(
            HttpStatusCode.InternalServerError,
            RconResponse(success = false, error = "No RCON_PASSWORD")
        )

        try {
            val client = RconClient("80.242.59.103", 36016, rconPassword)
            val rawResponse = client.connectAndFetchStatus()

            val serverInfo = parseServerInfo(rawResponse)

            if (serverInfo != null) {
                call.respond(serverInfo)
            } else {
                call.respond(HttpStatusCode.InternalServerError, RconResponse(
                    success = false,
                    error = "Failed to parse server info"
                ))
            }

        } catch (e: Exception) {
            println("RCON Error: ${e.message}")
            e.printStackTrace()

            call.respond(HttpStatusCode.InternalServerError, RconResponse(
                success = false,
                error = e.message,
                error_type = e.javaClass.simpleName
            ))
        }
    }

    // Новый эндпоинт для записи данных игроков в БД
    post("/rcon/save-players") {
        val rconPassword = System.getenv("RCON_PASSWORD") ?: return@post call.respond(
            HttpStatusCode.InternalServerError,
            RconResponse(success = false, error = "No RCON_PASSWORD")
        )

        try {
            val client = RconClient("80.242.59.103", 36016, rconPassword)
            val rawResponse = client.connectAndFetchStatus()

            val playersBlock = extractPlayersBlock(rawResponse)
            val players = parsePlayers(playersBlock)

            if (players.isNotEmpty()) {
                // Сохраняем в обе коллекции
                savePlayersToFirebase(players) // Старая коллекция
                savePlayersDataToFirebase(players) // Новая детальная коллекция

                call.respond(RconResponse(
                    success = true,
                    error = "Successfully saved ${players.size} players to database"
                ))

                println("✅ Manually saved ${players.size} players to both collections")
            } else {
                call.respond(RconResponse(
                    success = true,
                    error = "No players online to save"
                ))
            }

        } catch (e: Exception) {
            println("RCON Save Error: ${e.message}")
            e.printStackTrace()

            call.respond(HttpStatusCode.InternalServerError, RconResponse(
                success = false,
                error = e.message,
                error_type = e.javaClass.simpleName
            ))
        }
    }

    // Эндпоинт для получения полной информации о сервере И записи в БД
    post("/rcon/server-info-and-save") {
        val rconPassword = System.getenv("RCON_PASSWORD") ?: return@post call.respond(
            HttpStatusCode.InternalServerError,
            RconResponse(success = false, error = "No RCON_PASSWORD")
        )

        try {
            val client = RconClient("80.242.59.103", 36016, rconPassword)
            val rawResponse = client.connectAndFetchStatus()

            val serverInfo = parseServerInfo(rawResponse)

            if (serverInfo != null) {
                // Сохраняем игроков в БД
                if (serverInfo.playersList.isNotEmpty()) {
                    savePlayersToFirebase(serverInfo.playersList) // Старая коллекция
                    savePlayersDataToFirebase(serverInfo.playersList) // Новая детальная коллекция
                    println("✅ Saved ${serverInfo.playersList.size} players to both collections")
                }

                call.respond(serverInfo)
            } else {
                call.respond(HttpStatusCode.InternalServerError, RconResponse(
                    success = false,
                    error = "Failed to parse server info"
                ))
            }

        } catch (e: Exception) {
            println("RCON Error: ${e.message}")
            e.printStackTrace()

            call.respond(HttpStatusCode.InternalServerError, RconResponse(
                success = false,
                error = e.message,
                error_type = e.javaClass.simpleName
            ))
        }
    }
}

fun Application.scheduleRconTask() {
    val rconPassword = System.getenv("RCON_PASSWORD") ?: return
    launch {
        while (true) {
            delay(60 * 60 * 1000) // Каждый час
            try {
                val client = RconClient("80.242.59.103", 36016, rconPassword)
                val rawResponse = client.connectAndFetchStatus()
                val block = extractPlayersBlock(rawResponse)
                val players = parsePlayers(block)

                // Сохраняем в обе коллекции
                savePlayersToFirebase(players) // Старая коллекция
                savePlayersDataToFirebase(players) // Новая детальная коллекция

                println("✅ [RCON SCHEDULER] Players saved: ${players.size}")
            } catch (e: Exception) {
                println("❌ [RCON SCHEDULER ERROR] ${e.message}")
            }
        }
    }
}

// Пользовательские роуты
fun Route.userRoutes() {
    get("/") {
        val users = getAllUsers()
        call.respond(users)
    }
    get("/{id}") {
        val id = call.parameters["id"]
        if (id == null) {
            call.respond(HttpStatusCode.BadRequest, "Missing id")
            return@get
        }
        val user = getUserById(id)
        if (user == null) {
            call.respond(HttpStatusCode.NotFound, "User not found")
            return@get
        }
        call.respond(user)
    }
    post("/addUser") {
        val request = call.receive<UserCreateRequest>()
        val user = addUser(request)
        if (user == null) {
            call.respond(HttpStatusCode.InternalServerError, "Failed to add user")
            return@post
        }
        call.respond(user)
    }
    delete("/{id}") {
        val id = call.parameters["id"]
        if (id == null) {
            call.respond(HttpStatusCode.BadRequest, "Missing id")
            return@delete
        }
        val success = deleteUserById(id)
        if (!success) {
            call.respond(HttpStatusCode.NotFound, "User not found or failed to delete")
            return@delete
        }
        call.respond(HttpStatusCode.OK, "User deleted successfully")
    }
    get("/discord") {
        val guildInfo = getDiscordGuildInfo()
        if (guildInfo != null) {
            call.respond(guildInfo)
        } else {
            call.respond(HttpStatusCode.InternalServerError, mapOf("error" to "Failed to get Discord data"))
        }
    }
    get("/steam/userinfo/{steamId}") {
        val steamId = call.parameters["steamId"] ?: return@get call.respond(HttpStatusCode.BadRequest, "Steam ID missing")
        val user = getSteamUserFromFirebase(steamId)
        if (user == null) {
            call.respond(HttpStatusCode.NotFound, "Steam user not found")
        } else {
            call.respond(user)
        }
    }
    steamAuthRoutes()
}