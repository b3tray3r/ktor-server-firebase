package com.example

import io.github.cdimascio.dotenv.dotenv

object Config {
    private val dotenv = dotenv()

    val PROJECT_ID: String = dotenv["PROJECT_ID"] ?: throw IllegalStateException("PROJECT_ID not found in .env")
    val DISCORD_BOT_TOKEN: String = dotenv["DISCORD_BOT_TOKEN"] ?: throw IllegalStateException("DISCORD_BOT_TOKEN not found in .env")
    val DISCORD_GUILD_ID: String = dotenv["DISCORD_GUILD_ID"] ?: throw IllegalStateException("DISCORD_GUILD_ID not found in .env")
    val STEAM_API_KEY: String = dotenv["STEAM_API_KEY"] ?: throw IllegalStateException("STEAM_API_KEY not found in .env")
    val SERVER_URL: String = dotenv["SERVER_URL"] ?: throw IllegalStateException("SERVER_URL not found in .env")

    // ИСПРАВЛЕНО: Убраны лишние пробелы
    val BASE_URL: String = "https://firestore.googleapis.com/v1/projects/$PROJECT_ID/databases/(default)/documents"
    val STEAM_USERS_COLLECTION: String = "$BASE_URL/steam_users"
}