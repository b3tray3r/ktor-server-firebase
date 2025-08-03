package com.example

object Config {
    // Получаем переменные из системного окружения (Render, Heroku, Docker и т.д.)
    val PROJECT_ID: String = System.getenv("PROJECT_ID") ?: error("PROJECT_ID not set")
    val DISCORD_BOT_TOKEN: String = System.getenv("DISCORD_BOT_TOKEN") ?: error("DISCORD_BOT_TOKEN not set")
    val DISCORD_GUILD_ID: String = System.getenv("DISCORD_GUILD_ID") ?: error("DISCORD_GUILD_ID not set")
    val STEAM_API_KEY: String = System.getenv("STEAM_API_KEY") ?: error("STEAM_API_KEY not set")
    val SERVER_URL: String = System.getenv("SERVER_URL") ?: error("SERVER_URL not set")

    // Производные пути
    val BASE_URL: String = "https://firestore.googleapis.com/v1/projects/$PROJECT_ID/databases/(default)/documents"
    val STEAM_USERS_COLLECTION: String = "$BASE_URL/steam_users"
    val RUST_PLAYERS_COLLECTION = "https://firestore.googleapis.com/v1/projects/$PROJECT_ID/databases/(default)/documents/rust_login_players"

    // Новая коллекция для детальных данных игроков
    val RUST_PLAYER_DATA_COLLECTION = "https://firestore.googleapis.com/v1/projects/$PROJECT_ID/databases/(default)/documents/rust_player_data"
    val RUST_PLAYER_STATS_COLLECTION = "https://firestore.googleapis.com/v1/projects/$PROJECT_ID/databases/(default)/documents/rust_player_stats"
}