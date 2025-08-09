package com.example

object Config {
    // Получаем переменные из системного окружения
    val PROJECT_ID: String = System.getenv("PROJECT_ID") ?: error("PROJECT_ID not set")
    val DISCORD_BOT_TOKEN: String = System.getenv("DISCORD_BOT_TOKEN") ?: error("DISCORD_BOT_TOKEN not set")
    val DISCORD_GUILD_ID: String = System.getenv("DISCORD_GUILD_ID") ?: error("DISCORD_GUILD_ID not set")
    val STEAM_API_KEY: String = System.getenv("STEAM_API_KEY") ?: error("STEAM_API_KEY not set")
    val SERVER_URL: String = System.getenv("SERVER_URL") ?: error("SERVER_URL not set")

    // Производные пути для Firestore
    private val BASE_URL: String = "https://firestore.googleapis.com/v1/projects/$PROJECT_ID/databases/(default)/documents"

    val STEAM_USERS_COLLECTION: String = "$BASE_URL/steam_users"
    val RUST_PLAYER_DATA_COLLECTION = "$BASE_URL/rust_player_data"
    val RUST_PLAYER_STATS_COLLECTION = "$BASE_URL/rust_player_stats"
    val RUST_PLAYER_BALANCE_COLLECTION = "$BASE_URL/rust_player_balance"

    // RCON настройки
    const val RCON_HOST = "80.242.59.103"
    const val RCON_PORT = 36016
}