package com.example

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
                append("Authorization", "Bot ${Config.DISCORD_BOT_TOKEN}") // Добавлен префикс Bot
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

// Парсим документ Firestore в User
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

fun Route.steamAuthRoutes() {
    get("/steam/login") {
        // Убедитесь, что returnUrl и realm соответствуют вашему домену и настройкам
        val returnUrl = "https://ktor-server-u2py.onrender.com/steam/callback" // Замените на свой Render-домен
        val realm = "https://ktor-server-u2py.onrender.com/" // Замените на свой Render-домен
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

        // Проверка параметров
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

            // Проверка существования пользователя в Firestore
            val exists = checkIfSteamUserExists(steamId)
            if (!exists) {
                saveSteamUser(steamId)
            } else {
                // Если пользователь существует, можно обновить данные (по желанию)
                // updateSteamUser(steamId) // Реализуйте эту функцию при необходимости
            }

            // Редирект на ваш сайт после успешной авторизации
            // ЗАМЕНИТЕ https://yourwebsite.com на ваш реальный сайт
            call.respondRedirect("https://www.google.com")

        } else {
            println("Steam verification failed: $body")
            call.respondText("Ошибка авторизации через Steam")
        }
    }
}

suspend fun checkIfSteamUserExists(steamId: String): Boolean {
    val response = client.get("${Config.STEAM_USERS_COLLECTION}/$steamId") // Проверка конкретного документа
    return response.status == HttpStatusCode.OK
}

suspend fun saveSteamUser(steamId: String) {
    val apiKey = Config.STEAM_API_KEY
    val now = Clock.System.now().toString()

    // Получение данных из Steam API
    val response = client.get("https://api.steampowered.com/ISteamUser/GetPlayerSummaries/v2/?key=$apiKey&steamids=$steamId")
    val json = Json.parseToJsonElement(response.bodyAsText()).jsonObject
    val player = json["response"]?.jsonObject
        ?.get("players")?.jsonArray?.firstOrNull()?.jsonObject

    val name = player?.get("personaname")?.jsonPrimitive?.content ?: "Unknown"
    val avatar = player?.get("avatarfull")?.jsonPrimitive?.content ?: ""
    val profileUrl = player?.get("profileurl")?.jsonPrimitive?.content ?: ""

    // Подготовка данных для Firestore
    val data = buildJsonObject {
        put("fields", buildJsonObject {
            put("steamIdData", buildJsonObject { put("stringValue", steamId) }) // Используем steamIdData как в других частях
            put("name", buildJsonObject { put("stringValue", name) })
            put("avatar", buildJsonObject { put("stringValue", avatar) })
            put("profile", buildJsonObject { put("stringValue", profileUrl) })
            put("joined", buildJsonObject { put("stringValue", now) })
        })
    }

    // Используем PATCH для обновления/создания документа с ID = steamId
    // Это предотвращает дублирование, так как документ будет иметь ID steamId
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