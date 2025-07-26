# Используем официальный образ JDK
FROM eclipse-temurin:17-jdk

# Создаем рабочую директорию
WORKDIR /app

# Копируем gradle-файлы
COPY build.gradle.kts settings.gradle.kts gradlew /app/
COPY gradle /app/gradle

# Копируем исходники
COPY src /app/src

# Копируем wrapper
COPY gradle/wrapper /app/gradle/wrapper

# Собираем shadowJar
RUN chmod +x ./gradlew
RUN ./gradlew shadowJar --no-daemon

# Указываем порт
EXPOSE 8080

# Запускаем JAR
CMD ["java", "-jar", "build/libs/app.jar"]
