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
import java.security.SecureRandom
import java.util.concurrent.ConcurrentHashMap

private val client = HttpClient(CIO) {
    install(ContentNegotiation) {
        Json {
            encodeDefaults = true
            explicitNulls = false
            allowStructuredMapKeys = true
        }
    }
}

// Сессия для безопасной аутентификации
@Serializable
data class UserSession(
    val steamId: String,
    val sessionToken: String,
    val createdAt: Long
)

// Хранилище активных сессий (в продакшене лучше использовать Redis)
private val activeSessions = ConcurrentHashMap<String, UserSession>()

// Генератор безопасных токенов
private val secureRandom = SecureRandom()

fun generateSessionToken(): String {
    val bytes = ByteArray(32)
    secureRandom.nextBytes(bytes)
    return bytes.joinToString("") { "%02x".format(it) }
}

// Основные модели данных
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
    val currentName: String,
    val lastUpdatedInDb: String
)

@Serializable
data class PlayerBalance(
    val steamId: String,
    val balance: Int,
    val lastUpdated: String
)

@Serializable
data class BalanceUpdateRequest(
    val amount: Int
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
data class StatisticsCollectionResult(
    val success: Boolean,
    val totalPlayers: Int,
    val successfullyProcessed: Int,
    val errors: List<String>,
    val processingTimeMs: Long
)

@Serializable
data class RconResponse(
    val success: Boolean,
    val message: String? = null,
    val error: String? = null
)

@Serializable
data class RconServerResponse(
    val Message: String,
    val Identifier: Int,
    val Type: String,
    val Stacktrace: String
)

@Serializable
data class AuthResponse(
    val success: Boolean,
    val sessionToken: String? = null,
    val steamId: String? = null,
    val error: String? = null
)

// Middleware для проверки сессии
suspend fun ApplicationCall.requireAuth(): UserSession? {
    val authHeader = request.headers["Authorization"]
    if (authHeader == null || !authHeader.startsWith("Bearer ")) {
        return null
    }

    val token = authHeader.removePrefix("Bearer ")
    val session = activeSessions[token]

    // Проверяем, не истекла ли сессия (24 часа)
    if (session != null && System.currentTimeMillis() - session.createdAt > 24 * 60 * 60 * 1000) {
        activeSessions.remove(token)
        return null
    }

    return session
}

// Очистка истекших сессий
fun cleanupExpiredSessions() {
    val now = System.currentTimeMillis()
    val expiredTokens = activeSessions.entries
        .filter { (_, session) -> now - session.createdAt > 24 * 60 * 60 * 1000 }
        .map { it.key }

    expiredTokens.forEach { token ->
        activeSessions.remove(token)
    }
}

// Функции для работы с балансом игроков
suspend fun getPlayerBalance(steamId: String): PlayerBalance? {
    return try {
        val docUrl = "${Config.RUST_PLAYER_BALANCE_COLLECTION}/$steamId"
        val response = client.get(docUrl)

        if (response.status == HttpStatusCode.OK) {
            val json = Json.parseToJsonElement(response.bodyAsText()).jsonObject
            val fields = json["fields"]?.jsonObject

            if (fields != null) {
                PlayerBalance(
                    steamId = fields["steamId"]?.jsonObject?.get("stringValue")?.jsonPrimitive?.content ?: steamId,
                    balance = fields["balance"]?.jsonObject?.get("integerValue")?.jsonPrimitive?.intOrNull ?: 0,
                    lastUpdated = fields["lastUpdated"]?.jsonObject?.get("timestampValue")?.jsonPrimitive?.content ?: ""
                )
            } else null
        } else null

    } catch (e: Exception) {
        println("❌ Error fetching balance for $steamId: ${e.message}")
        null
    }
}

suspend fun updatePlayerBalance(steamId: String, amount: Int): Boolean {
    return try {
        val rconPassword = System.getenv("RCON_PASSWORD") ?: return false
        val now = Clock.System.now().toString()

        // Получаем текущий баланс
        val currentBalance = getPlayerBalance(steamId)?.balance ?: 0
        val newBalance = currentBalance + amount

        // Отправляем команду на сервер
        val rconClient = RconClient("80.242.59.103", 36016, rconPassword)
        val rconResponse = rconClient.executeCommand("konurashop.addbalance $steamId $amount")

        // Сохраняем новый баланс в БД
        val docUrl = "${Config.RUST_PLAYER_BALANCE_COLLECTION}/$steamId"
        val updateBody = buildJsonObject {
            put("fields", buildJsonObject {
                put("steamId", buildJsonObject { put("stringValue", steamId) })
                put("balance", buildJsonObject { put("integerValue", newBalance) })
                put("lastUpdated", buildJsonObject { put("timestampValue", now) })
            })
        }

        val saveResponse = client.patch(docUrl) {
            contentType(ContentType.Application.Json)
            setBody(updateBody)
        }

        if (saveResponse.status.isSuccess()) {
            println("✅ Balance updated for $steamId: $currentBalance + $amount = $newBalance")
            true
        } else {
            println("❌ Failed to save balance for $steamId")
            false
        }

    } catch (e: Exception) {
        println("❌ Error updating balance for $steamId: ${e.message}")
        e.printStackTrace()
        false
    }
}

// Функции для работы со статистикой
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
        emptyList()
    }
}

fun parsePlayerStatistics(steamId: String, rawResponse: String): PlayerStatistics? {
    return try {
        val rconResponse = Json.decodeFromString<RconServerResponse>(rawResponse)
        val message = rconResponse.Message

        val jsonStart = message.indexOf("{")
        val jsonEnd = message.lastIndexOf("}") + 1

        if (jsonStart == -1 || jsonEnd <= jsonStart) {
            println("⚠️ No JSON found in statistics response for Steam ID: $steamId")
            return null
        }

        val jsonString = message.substring(jsonStart, jsonEnd)
        val statisticsJson = Json.parseToJsonElement(jsonString).jsonObject

        // Извлекаем имя игрока из массива имен
        val names = statisticsJson["Names"]?.jsonArray?.mapNotNull {
            it.jsonPrimitive?.contentOrNull
        } ?: emptyList()
        val currentName = names.lastOrNull() ?: "Unknown"

        PlayerStatistics(
            steamId = steamId,
            lastUpdate = statisticsJson["LastUpdate"]?.jsonPrimitive?.longOrNull ?: 0L,
            joins = statisticsJson["Joins"]?.jsonPrimitive?.intOrNull ?: 0,
            leaves = statisticsJson["Leaves"]?.jsonPrimitive?.intOrNull ?: 0,
            kills = statisticsJson["Kills"]?.jsonPrimitive?.intOrNull ?: 0,
            deaths = statisticsJson["Deaths"]?.jsonPrimitive?.intOrNull ?: 0,
            suicides = statisticsJson["Suicides"]?.jsonPrimitive?.intOrNull ?: 0,
            shots = statisticsJson["Shots"]?.jsonPrimitive?.intOrNull ?: 0,
            headshots = statisticsJson["Headshots"]?.jsonPrimitive?.intOrNull ?: 0,
            experiments = statisticsJson["Experiments"]?.jsonPrimitive?.intOrNull ?: 0,
            recoveries = statisticsJson["Recoveries"]?.jsonPrimitive?.intOrNull ?: 0,
            voiceBytes = statisticsJson["VoiceBytes"]?.jsonPrimitive?.longOrNull ?: 0L,
            woundedTimes = statisticsJson["WoundedTimes"]?.jsonPrimitive?.intOrNull ?: 0,
            craftedItems = statisticsJson["CraftedItems"]?.jsonPrimitive?.intOrNull ?: 0,
            repairedItems = statisticsJson["RepairedItems"]?.jsonPrimitive?.intOrNull ?: 0,
            liftUsages = statisticsJson["LiftUsages"]?.jsonPrimitive?.intOrNull ?: 0,
            wheelSpins = statisticsJson["WheelSpins"]?.jsonPrimitive?.intOrNull ?: 0,
            hammerHits = statisticsJson["HammerHits"]?.jsonPrimitive?.intOrNull ?: 0,
            explosivesThrown = statisticsJson["ExplosivesThrown"]?.jsonPrimitive?.intOrNull ?: 0,
            weaponReloads = statisticsJson["WeaponReloads"]?.jsonPrimitive?.intOrNull ?: 0,
            rocketsLaunched = statisticsJson["RocketsLaunched"]?.jsonPrimitive?.intOrNull ?: 0,
            secondsPlayed = statisticsJson["SecondsPlayed"]?.jsonPrimitive?.longOrNull ?: 0L,
            currentName = currentName,
            lastUpdatedInDb = Clock.System.now().toString()
        )

    } catch (e: Exception) {
        println("❌ Error parsing statistics for $steamId: ${e.message}")
        null
    }
}

suspend fun savePlayerStatistics(playerStats: PlayerStatistics): Boolean {
    return try {
        val docUrl = "${Config.RUST_PLAYER_STATS_COLLECTION}/${playerStats.steamId}"
        val now = Clock.System.now().toString()

        val requestBody = buildJsonObject {
            put("fields", buildJsonObject {
                put("steamId", buildJsonObject { put("stringValue", playerStats.steamId) })
                put("currentName", buildJsonObject { put("stringValue", playerStats.currentName) })
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
                put("lastUpdatedInDb", buildJsonObject { put("timestampValue", now) })
            })
        }

        val saveResponse = client.patch(docUrl) {
            contentType(ContentType.Application.Json)
            setBody(requestBody)
        }

        if (saveResponse.status.isSuccess()) {
            println("✅ Saved statistics for Steam ID: ${playerStats.steamId} (${playerStats.kills} kills, ${playerStats.deaths} deaths)")
            true
        } else {
            println("❌ Failed to save statistics for ${playerStats.steamId}: ${saveResponse.status}")
            false
        }

    } catch (e: Exception) {
        println("❌ Error saving statistics for ${playerStats.steamId}: ${e.message}")
        false
    }
}

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

        for ((index, steamId) in steamIds.withIndex()) {
            try {
                println("🔄 Processing player ${index + 1}/${steamIds.size}: $steamId")
                val rawResponse = rconClient.getPlayerStatistics(steamId)
                val playerStats = parsePlayerStatistics(steamId, rawResponse)

                if (playerStats != null) {
                    val saved = savePlayerStatistics(playerStats)
                    if (saved) {
                        successfullyProcessed++
                    } else {
                        errors.add("Failed to save statistics for $steamId")
                    }
                } else {
                    errors.add("Failed to parse statistics for $steamId")
                }

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
        println("✅ Statistics collection completed: $successfullyProcessed/${steamIds.size} processed in ${processingTime}ms")

        StatisticsCollectionResult(
            success = successfullyProcessed > 0,
            totalPlayers = steamIds.size,
            successfullyProcessed = successfullyProcessed,
            errors = errors,
            processingTimeMs = processingTime
        )

    } catch (e: Exception) {
        println("❌ Fatal error in statistics collection: ${e.message}")
        StatisticsCollectionResult(
            success = false,
            totalPlayers = 0,
            successfullyProcessed = successfullyProcessed,
            errors = errors + "Fatal error: ${e.message}",
            processingTimeMs = System.currentTimeMillis() - startTime
        )
    }
}

// Steam и Discord функции
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
            println("Discord API error: ${response.status}")
            null
        }
    } catch (e: Exception) {
        println("❌ Discord API error: ${e.message}")
        null
    }
}

// Парсинг данных сервера
fun extractPlayersBlock(jsonResponse: String): String {
    return try {
        val rconResponse = Json.decodeFromString<RconServerResponse>(jsonResponse)
        val message = rconResponse.Message
        val startIndex = message.indexOf("id                name")

        if (startIndex == -1) {
            println("Players header not found in message")
            ""
        } else {
            message.substring(startIndex).trim()
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
        null
    }
}

fun parsePlayers(block: String): List<RustPlayer> {
    return block.lines().mapNotNull { parsePlayerLine(it) }
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
                line.startsWith("hostname:") -> hostname = line.substringAfter("hostname:").trim()
                line.startsWith("version :") -> version = line.substringAfter("version :").trim()
                line.startsWith("map     :") -> map = line.substringAfter("map     :").trim()
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
        null
    }
}

suspend fun savePlayersDataToFirebase(players: List<RustPlayer>) {
    val now = Clock.System.now().toString()

    for (player in players) {
        try {
            val docUrl = "${Config.RUST_PLAYER_DATA_COLLECTION}/${player.id}"

            val createBody = buildJsonObject {
                put("fields", buildJsonObject {
                    put("steamId", buildJsonObject { put("stringValue", player.id) })
                    put("currentName", buildJsonObject { put("stringValue", player.name) })
                    put("lastSeen", buildJsonObject { put("timestampValue", now) })
                })
            }

            client.patch(docUrl) {
                contentType(ContentType.Application.Json)
                setBody(createBody)
            }

            println("✅ Saved player data: ${player.name} (${player.id})")

        } catch (e: Exception) {
            println("❌ Error saving player data for ${player.name}: ${e.message}")
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
            put("joined", buildJsonObject { put("timestampValue", now) })
        })
    }

    try {
        val patchResponse = client.patch("${Config.STEAM_USERS_COLLECTION}/$steamId") {
            contentType(ContentType.Application.Json)
            setBody(data)
        }
        if (patchResponse.status.isSuccess()) {
            println("Steam user $steamId saved successfully.")
        }
    } catch (e: Exception) {
        println("Exception while saving Steam user $steamId: ${e.message}")
    }
}

// Роуты
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

            // Создаем безопасную сессию
            val sessionToken = generateSessionToken()
            val session = UserSession(
                steamId = steamId,
                sessionToken = sessionToken,
                createdAt = System.currentTimeMillis()
            )
            activeSessions[sessionToken] = session

            // Перенаправляем на фронтенд с токеном вместо Steam ID
            call.respondRedirect("https://konurarust.com/?token=$sessionToken")
        } else {
            println("Steam verification failed: $body")
            call.respondText("Ошибка авторизации через Steam")
        }
    }

    // Новый эндпоинт для получения информации о пользователе по токену
    get("/auth/me") {
        val session = call.requireAuth()
        if (session == null) {
            call.respond(HttpStatusCode.Unauthorized, AuthResponse(
                success = false,
                error = "Invalid or expired session"
            ))
            return@get
        }

        val userProfile = getSteamUserFromFirebase(session.steamId)
        if (userProfile != null) {
            call.respond(mapOf(
                "success" to true,
                "steamId" to session.steamId,
                "user" to userProfile
            ))
        } else {
            call.respond(HttpStatusCode.NotFound, AuthResponse(
                success = false,
                error = "User profile not found"
            ))
        }
    }

    // Эндпоинт для выхода
    post("/auth/logout") {
        val session = call.requireAuth()
        if (session != null) {
            activeSessions.remove(session.sessionToken)
        }
        call.respond(AuthResponse(success = true))
    }
}

fun Route.rconRoutes() {
    // Получение статистики игрока напрямую с сервера (требует авторизацию)
    get("/rcon/player-stats/{steamId}") {
        val session = call.requireAuth()
        if (session == null) {
            return@get call.respond(HttpStatusCode.Unauthorized, RconResponse(
                success = false,
                error = "Authentication required"
            ))
        }

        val steamId = call.parameters["steamId"] ?: return@get call.respond(
            HttpStatusCode.BadRequest,
            RconResponse(success = false, error = "Steam ID is required")
        )

        // Пользователь может получать только свои данные или если это админ
        if (session.steamId != steamId && !isAdmin(session.steamId)) {
            return@get call.respond(HttpStatusCode.Forbidden, RconResponse(
                success = false,
                error = "Access denied"
            ))
        }

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
                    error = "No statistics found for Steam ID: $steamId"
                ))
            }

        } catch (e: Exception) {
            println("❌ Error getting player statistics: ${e.message}")
            call.respond(HttpStatusCode.InternalServerError, RconResponse(
                success = false,
                error = e.message
            ))
        }
    }

    // Получение сохраненной статистики из БД (требует авторизацию)
    get("/rcon/saved-stats/{steamId}") {
        val session = call.requireAuth()
        if (session == null) {
            return@get call.respond(HttpStatusCode.Unauthorized, mapOf("error" to "Authentication required"))
        }

        val steamId = call.parameters["steamId"] ?: return@get call.respond(
            HttpStatusCode.BadRequest,
            mapOf("error" to "Steam ID is required")
        )

        // Пользователь может получать только свои данные или если это админ
        if (session.steamId != steamId && !isAdmin(session.steamId)) {
            return@get call.respond(HttpStatusCode.Forbidden, mapOf("error" to "Access denied"))
        }

        try {
            val docUrl = "${Config.RUST_PLAYER_STATS_COLLECTION}/$steamId"
            val response = client.get(docUrl)

            if (response.status == HttpStatusCode.OK) {
                val json = Json.parseToJsonElement(response.bodyAsText()).jsonObject
                val fields = json["fields"]?.jsonObject

                if (fields != null) {
                    val playerStats = parsePlayerStatisticsFromFirestore(steamId, fields)
                    call.respond(playerStats)
                } else {
                    call.respond(HttpStatusCode.NotFound, mapOf("error" to "Statistics not found for Steam ID: $steamId"))
                }
            } else {
                call.respond(HttpStatusCode.NotFound, mapOf("error" to "Statistics not found for Steam ID: $steamId"))
            }

        } catch (e: Exception) {
            println("❌ Error getting saved statistics: ${e.message}")
            call.respond(HttpStatusCode.InternalServerError, mapOf("error" to e.message))
        }
    }

    // Получение списка всех игроков со статистикой (публичный доступ)
    get("/rcon/stats-players-list") {
        try {
            println("🔄 Fetching from URL: ${Config.RUST_PLAYER_STATS_COLLECTION}")

            val response = client.get(Config.RUST_PLAYER_STATS_COLLECTION)

            println("📊 Response status: ${response.status}")
            if (response.status != HttpStatusCode.OK) {
                val errorBody = response.bodyAsText()
                println("❌ Error response body: $errorBody")
                return@get call.respond(HttpStatusCode.InternalServerError, mapOf(
                    "error" to "Failed to fetch statistics data",
                    "status" to response.status.value,
                    "details" to errorBody
                ))
            }

            val json = Json.parseToJsonElement(response.bodyAsText()).jsonObject
            val documents = json["documents"]?.jsonArray ?: return@get call.respond(emptyList<PlayerStatistics>())

            val playerStatsList = documents.mapNotNull { doc ->
                try {
                    val fields = doc.jsonObject["fields"]?.jsonObject ?: return@mapNotNull null
                    val steamId = fields["steamId"]?.jsonObject?.get("stringValue")?.jsonPrimitive?.content ?: return@mapNotNull null
                    parsePlayerStatisticsFromFirestore(steamId, fields)
                } catch (e: Exception) {
                    println("❌ Error parsing player stats: ${e.message}")
                    null
                }
            }

            call.respond(playerStatsList)

        } catch (e: Exception) {
            println("❌ Exception in stats-players-list: ${e.message}")
            e.printStackTrace()
            call.respond(HttpStatusCode.InternalServerError, mapOf("error" to e.message))
        }
    }

    // Сбор статистики всех игроков (только для админов)
    post("/rcon/collect-all-statistics") {
        val session = call.requireAuth()
        if (session == null || !isAdmin(session.steamId)) {
            return@post call.respond(HttpStatusCode.Forbidden, RconResponse(
                success = false,
                error = "Admin access required"
            ))
        }

        try {
            val result = collectAllPlayersStatistics()
            call.respond(result)
        } catch (e: Exception) {
            println("❌ Error in statistics collection: ${e.message}")
            call.respond(HttpStatusCode.InternalServerError, RconResponse(
                success = false,
                error = e.message
            ))
        }
    }

    // Лидерборд с лимитом в пути (публичный доступ)
    get("/rcon/leaderboard/{statType}/{limit}") {
        val statType = call.parameters["statType"] ?: return@get call.respond(
            HttpStatusCode.BadRequest,
            mapOf("error" to "Stat type is required")
        )

        val limit = call.parameters["limit"]?.toIntOrNull() ?: return@get call.respond(
            HttpStatusCode.BadRequest,
            mapOf("error" to "Limit must be a valid number")
        )

        try {
            val leaderboard = getLeaderboard(statType, limit)
            call.respond(leaderboard)
        } catch (e: Exception) {
            println("❌ Error getting leaderboard: ${e.message}")
            call.respond(HttpStatusCode.InternalServerError, mapOf("error" to e.message))
        }
    }

    // Лидерборд с лимитом по умолчанию (публичный доступ)
    get("/rcon/leaderboard/{statType}") {
        val statType = call.parameters["statType"] ?: return@get call.respond(
            HttpStatusCode.BadRequest,
            mapOf("error" to "Stat type is required")
        )

        val limit = call.request.queryParameters["limit"]?.toIntOrNull() ?: 10

        try {
            val leaderboard = getLeaderboard(statType, limit)
            call.respond(leaderboard)
        } catch (e: Exception) {
            println("❌ Error getting leaderboard: ${e.message}")
            call.respond(HttpStatusCode.InternalServerError, mapOf("error" to e.message))
        }
    }

    // Получение информации о сервере и сохранение в БД (только для админов)
    post("/rcon/server-info-and-save") {
        val session = call.requireAuth()
        if (session == null || !isAdmin(session.steamId)) {
            return@post call.respond(HttpStatusCode.Forbidden, RconResponse(
                success = false,
                error = "Admin access required"
            ))
        }

        val rconPassword = System.getenv("RCON_PASSWORD") ?: return@post call.respond(
            HttpStatusCode.InternalServerError,
            RconResponse(success = false, error = "No RCON_PASSWORD")
        )

        try {
            val rconClient = RconClient("80.242.59.103", 36016, rconPassword)
            val rawResponse = rconClient.connectAndFetchStatus()
            val serverInfo = parseServerInfo(rawResponse)

            if (serverInfo != null) {
                if (serverInfo.playersList.isNotEmpty()) {
                    savePlayersDataToFirebase(serverInfo.playersList)
                    println("✅ Saved ${serverInfo.playersList.size} players to database")
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
            call.respond(HttpStatusCode.InternalServerError, RconResponse(
                success = false,
                error = e.message
            ))
        }
    }

    // Добавление баланса игроку (только для админов)
    post("/rcon/add-balance/{steamId}") {
        val session = call.requireAuth()
        if (session == null || !isAdmin(session.steamId)) {
            return@post call.respond(HttpStatusCode.Forbidden, RconResponse(
                success = false,
                error = "Admin access required"
            ))
        }

        val steamId = call.parameters["steamId"] ?: return@post call.respond(
            HttpStatusCode.BadRequest,
            RconResponse(success = false, error = "Steam ID is required")
        )

        val request = try {
            call.receive<BalanceUpdateRequest>()
        } catch (e: Exception) {
            return@post call.respond(
                HttpStatusCode.BadRequest,
                RconResponse(success = false, error = "Invalid request body")
            )
        }

        if (request.amount <= 0) {
            return@post call.respond(
                HttpStatusCode.BadRequest,
                RconResponse(success = false, error = "Amount must be positive")
            )
        }

        try {
            val success = updatePlayerBalance(steamId, request.amount)
            if (success) {
                val newBalance = getPlayerBalance(steamId)
                call.respond(RconResponse(
                    success = true,
                    message = "Balance updated successfully. New balance: ${newBalance?.balance ?: 0}"
                ))
            } else {
                call.respond(HttpStatusCode.InternalServerError, RconResponse(
                    success = false,
                    error = "Failed to update balance"
                ))
            }
        } catch (e: Exception) {
            println("❌ Error adding balance: ${e.message}")
            call.respond(HttpStatusCode.InternalServerError, RconResponse(
                success = false,
                error = e.message
            ))
        }
    }

    // Получение баланса игрока (пользователь может получить только свой баланс)
    get("/rcon/balance/{steamId}") {
        val session = call.requireAuth()
        if (session == null) {
            return@get call.respond(HttpStatusCode.Unauthorized, mapOf("error" to "Authentication required"))
        }

        val steamId = call.parameters["steamId"] ?: return@get call.respond(
            HttpStatusCode.BadRequest,
            mapOf("error" to "Steam ID is required")
        )

        // Пользователь может получать только свой баланс или если это админ
        if (session.steamId != steamId && !isAdmin(session.steamId)) {
            return@get call.respond(HttpStatusCode.Forbidden, mapOf("error" to "Access denied"))
        }

        try {
            val balance = getPlayerBalance(steamId)
            if (balance != null) {
                call.respond(balance)
            } else {
                // Если баланс не найден, создаем новый с нулевым балансом
                val newBalance = PlayerBalance(
                    steamId = steamId,
                    balance = 0,
                    lastUpdated = Clock.System.now().toString()
                )
                call.respond(newBalance)
            }
        } catch (e: Exception) {
            println("❌ Error getting balance: ${e.message}")
            call.respond(HttpStatusCode.InternalServerError, mapOf("error" to e.message))
        }
    }
}

// Вспомогательные функции
suspend fun parsePlayerStatisticsFromFirestore(steamId: String, fields: JsonObject): PlayerStatistics {
    val currentName = fields["currentName"]?.jsonObject?.get("stringValue")?.jsonPrimitive?.content ?: "Unknown"
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

    return PlayerStatistics(
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
        currentName = currentName,
        lastUpdatedInDb = lastUpdatedInDb
    )
}



fun isAdmin(steamId: String): Boolean {
    val adminSteamIds = System.getenv("ADMIN_STEAM_IDS")?.split(",") ?: emptyList()
    return adminSteamIds.contains(steamId)
}

// Вспомогательная функция для получения лидерборда
suspend fun getLeaderboard(statType: String, limit: Int): LeaderboardResponse {
    val response = client.get(Config.RUST_PLAYER_STATS_COLLECTION)
    if (response.status != HttpStatusCode.OK) {
        return LeaderboardResponse(
            statType = statType,
            limit = limit,
            totalPlayers = 0,
            leaderboard = emptyList()
        )
    }

    val json = Json.parseToJsonElement(response.bodyAsText()).jsonObject
    val documents = json["documents"]?.jsonArray ?: return LeaderboardResponse(
        statType = statType,
        limit = limit,
        totalPlayers = 0,
        leaderboard = emptyList()
    )

    val leaderboard = documents.mapNotNull { doc ->
        try {
            val fields = doc.jsonObject["fields"]?.jsonObject ?: return@mapNotNull null
            val steamId = fields["steamId"]?.jsonObject?.get("stringValue")?.jsonPrimitive?.content ?: return@mapNotNull null
            val currentName = fields["currentName"]?.jsonObject?.get("stringValue")?.jsonPrimitive?.content ?: "Unknown"

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
                else -> return@mapNotNull null
            }

            LeaderboardEntry(
                steamId = steamId,
                name = currentName,
                value = statValue,
                statType = statType
            )
        } catch (e: Exception) {
            null
        }
    }

    val sortedLeaderboard = leaderboard.sortedByDescending { it.value }.take(limit)

    return LeaderboardResponse(
        statType = statType,
        limit = limit,
        totalPlayers = leaderboard.size,
        leaderboard = sortedLeaderboard
    )
}

fun Route.userRoutes() {
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
    rconRoutes()
}

fun Application.scheduleRconTask() {
    val rconPassword = System.getenv("RCON_PASSWORD") ?: return

    // Периодическая очистка истекших сессий
    launch {
        while (true) {
            delay(60 * 60 * 1000) // Каждый час
            cleanupExpiredSessions()
            println("✅ [SESSION CLEANUP] Cleaned expired sessions")
        }
    }

    // Основная задача RCON
    launch {
        while (true) {
            delay(60 * 60 * 1000) // Каждый час
            try {
                val rconClient = RconClient("80.242.59.103", 36016, rconPassword)
                val rawResponse = rconClient.connectAndFetchStatus()
                val block = extractPlayersBlock(rawResponse)
                val players = parsePlayers(block)
                savePlayersDataToFirebase(players)
                println("✅ [RCON SCHEDULER] Players saved: ${players.size}")
            } catch (e: Exception) {
                println("❌ [RCON SCHEDULER ERROR] ${e.message}")
            }
        }
    }
}