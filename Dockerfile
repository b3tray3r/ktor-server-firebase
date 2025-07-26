# Используем официальный образ JDK
FROM eclipse-temurin:17-jdk

# Создаем рабочую директорию
WORKDIR /app

# Копируем gradle-файлы
COPY build.gradle.kts settings.gradle.kts gradlew /app/
COPY gradle /app/gradle

# Копируем исходники
COPY src /app/src

# Копируем wrapper (эта строка часто не нужна, если gradle/wrapper уже скопирован выше, но не повредит)
COPY gradle/wrapper /app/gradle/wrapper

# Делаем gradlew исполняемым и собираем проект (добавлена сборка!)
RUN chmod +x ./gradlew
# Эта строка выполняет сборку shadowJar
RUN ./gradlew shadowJar --no-daemon

# Указываем порт
EXPOSE 8080

# Запускаем JAR (убедитесь, что имя файла совпадает с тем, что указано в build.gradle.kts)
CMD ["java", "-jar", "build/libs/app.jar"]