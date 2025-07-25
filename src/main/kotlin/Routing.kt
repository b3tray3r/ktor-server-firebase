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

private const val PROJECT_ID = "ktor-server-b3tray3r"
private const val BASE_URL = "https://firestore.googleapis.com/v1/projects/$PROJECT_ID/databases/(default)/documents"
private const val STEAM_USERS_COLLECTION = "$BASE_URL/steam_users"
private const val DISCORD_BOT_TOKEN = "Bot MTM5NjE1ODg2OTAyOTI1NzM5Nw.G0f_zf.QPCbWrRAWY5FP9TTUY-BV3y0OVFVeM5mBL2bJc"
private const val DISCORD_GUILD_ID = "722201462939058317"
private const val STEAM_API_KEY = "ECCB6C28A6D2DFE42E04E8C770364443"

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
    val response = client.get(STEAM_USERS_COLLECTION)
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
        val response = client.get("https://discord.com/api/v10/guilds/$DISCORD_GUILD_ID?with_counts=true") {
            headers {
                append("Authorization", DISCORD_BOT_TOKEN)
            }
        }
        if (response.status == HttpStatusCode.OK) {
            val json = kotlinx.serialization.json.Json.parseToJsonElement(response.bodyAsText()).jsonObject
            DiscordGuildData(
                approximate_member_count = json["approximate_member_count"]?.jsonPrimitive?.intOrNull,
                approximate_presence_count = json["approximate_presence_count"]?.jsonPrimitive?.intOrNull
            )
        } else {
            null
        }
    } catch (e: Exception) {
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
    val response: HttpResponse = client.get(BASE_URL)
    if (response.status != HttpStatusCode.OK) return emptyList()
    val json = Json.parseToJsonElement(response.bodyAsText()).jsonObject
    val documents = json["documents"]?.jsonArray ?: return emptyList()
    return documents.map { parseUser(it.jsonObject) }
}

suspend fun getUserById(id: String): User? {
    val response = client.get("$BASE_URL/$id")
    if (response.status != HttpStatusCode.OK) return null
    val json = Json.parseToJsonElement(response.bodyAsText()).jsonObject
    return parseUser(json)
}

suspend fun deleteUserById(id: String): Boolean {
    val response = client.delete("$BASE_URL/$id")
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

    val response = client.post(BASE_URL) {
        contentType(ContentType.Application.Json)
        setBody(firestoreData)
    }

    if (response.status != HttpStatusCode.OK) return null
    val json = Json.parseToJsonElement(response.bodyAsText()).jsonObject
    return parseUser(json)
}

fun Route.steamAuthRoutes() {
    get("/steam/login") {
        val returnUrl = "https://ktor-server-u2py.onrender.com/steam/callback"  // замени на свой Render-домен
        val realm = "https://ktor-server-u2py.onrender.com/"

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

        val verifyResponse = client.post(steamOpenIdUrl) {
            contentType(ContentType.Application.FormUrlEncoded)
            setBody(FormDataContent(Parameters.build {
                append("openid.assoc_handle", params["openid.assoc_handle"] ?: "")
                append("openid.signed", params["openid.signed"] ?: "")
                append("openid.sig", params["openid.sig"] ?: "")
                append("openid.ns", params["openid.ns"] ?: "")
                params["openid.signed"]?.split(",")?.forEach {
                    append("openid.$it", params["openid.$it"] ?: "")
                }
                append("openid.mode", "check_authentication")
            }))
        }

        val body = verifyResponse.bodyAsText()

        if (body.contains("is_valid:true")) {
            val steamId = params["openid.claimed_id"]?.substringAfterLast("/") ?: return@get call.respondText("Steam ID не найден")

            val apiKey = STEAM_API_KEY  // твой Steam API ключ
            val profileUrl = "https://api.steampowered.com/ISteamUser/GetPlayerSummaries/v2/?key=$apiKey&steamids=$steamId"

            val steamResponse = client.get(profileUrl)
            val steamData = Json.parseToJsonElement(steamResponse.bodyAsText()).jsonObject

            val player = steamData["response"]?.jsonObject
                ?.get("players")?.jsonArray?.firstOrNull()?.jsonObject

            val name = player?.get("personaname")?.jsonPrimitive?.contentOrNull
            val avatar = player?.get("avatarfull")?.jsonPrimitive?.contentOrNull
            val profileUrlSteam = player?.get("profileurl")?.jsonPrimitive?.contentOrNull

            // Проверка в Firestore
            val exists = checkIfSteamUserExists(steamId)
            if (!exists) {
                saveSteamUser(steamId)
            }

            call.respondText("Добро пожаловать! Твой Steam ID: $steamId")
        } else {
            call.respondText("Ошибка авторизации через Steam")
        }
    }
}

suspend fun checkIfSteamUserExists(steamId: String): Boolean {
    val response = client.get("https://firestore.googleapis.com/v1/projects/ktor-server-b3tray3r/databases/(default)/documents/steam_users/$steamId")
    return response.status == HttpStatusCode.OK
}

suspend fun saveSteamUser(steamId: String) {
    val apiKey = STEAM_API_KEY
    val now = kotlinx.datetime.Clock.System.now().toString()
    val response = client.get("https://api.steampowered.com/ISteamUser/GetPlayerSummaries/v2/?key=$apiKey&steamids=$steamId")
    val json = Json.parseToJsonElement(response.bodyAsText()).jsonObject

    val player = json["response"]?.jsonObject
        ?.get("players")?.jsonArray?.firstOrNull()?.jsonObject

    val name = player?.get("personaname")?.jsonPrimitive?.content ?: "Unknown"
    val avatar = player?.get("avatarfull")?.jsonPrimitive?.content ?: ""
    val profileUrl = player?.get("profileurl")?.jsonPrimitive?.content ?: ""

    val data = buildJsonObject {
        put("fields", buildJsonObject {
            put("steam_id", buildJsonObject { put("stringValue", steamId) })
            put("name", buildJsonObject { put("stringValue", name) })
            put("avatar", buildJsonObject { put("stringValue", avatar) })
            put("profile", buildJsonObject { put("stringValue", profileUrl) })
            put("joined", buildJsonObject { put("stringValue", now) })
        })
    }

    client.post("https://firestore.googleapis.com/v1/projects/ktor-server-b3tray3r/databases/(default)/documents/steam_users") {
        contentType(ContentType.Application.Json)
        setBody(data)
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
