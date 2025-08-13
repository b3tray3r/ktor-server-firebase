package com.example

import io.github.cdimascio.dotenv.dotenv

object Config {
    // Получаем переменные из системного окружения
    private val dotenv = dotenv {
        ignoreIfMissing = true // чтобы не падало, если файла нет
    }
    val PROJECT_ID: String = dotenv["PROJECT_ID"] ?: System.getenv("PROJECT_ID") ?: error("PROJECT_ID not set")
    val DISCORD_BOT_TOKEN: String = dotenv["DISCORD_BOT_TOKEN"] ?: System.getenv("DISCORD_BOT_TOKEN") ?: error("DISCORD_BOT_TOKEN not set")
    val DISCORD_GUILD_ID: String = dotenv["DISCORD_GUILD_ID"] ?: System.getenv("DISCORD_GUILD_ID") ?: error("DISCORD_GUILD_ID not set")
    val STEAM_API_KEY: String = dotenv["STEAM_API_KEY"] ?: System.getenv("STEAM_API_KEY") ?: error("STEAM_API_KEY not set")
    val SERVER_URL: String = dotenv["SERVER_URL"] ?: System.getenv("SERVER_URL") ?: error("SERVER_URL not set")

    // Производные пути для Firestore
    private val BASE_URL: String = "https://firestore.googleapis.com/v1/projects/$PROJECT_ID/databases/(default)/documents"

    val STEAM_USERS_COLLECTION: String = "$BASE_URL/steam_users"
    val RUST_PLAYER_DATA_COLLECTION = "$BASE_URL/rust_player_data"
    val RUST_PLAYER_STATS_COLLECTION = "$BASE_URL/rust_player_stats"
    val RUST_PLAYER_BALANCE_COLLECTION = "$BASE_URL/rust_player_balance"
    val ACTIVITY_COLLECTION = "$BASE_URL/player_activity"
}