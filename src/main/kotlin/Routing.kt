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
import io.ktor.client.statement.*
import io.ktor.serialization.kotlinx.json.*

private val PROJECT_ID = "ktor-server-b3tray3r"  // Замените на свой ID из Firebase
private val BASE_URL = "https://firestore.googleapis.com/v1/projects/$PROJECT_ID/databases/(default)/documents/users"

private val client = HttpClient(CIO) {
    install(ContentNegotiation) {
        json()
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

}
