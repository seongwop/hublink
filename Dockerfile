FROM eclipse-temurin:17-jdk AS build
WORKDIR /app

COPY gradlew .
COPY gradle gradle
COPY settings.gradle .
COPY build.gradle .

RUN sed -i 's/\r$//' ./gradlew && chmod +x ./gradlew

# Gradle dependency cache
RUN ./gradlew dependencies || true

COPY . .

ARG SERVICE_NAME
RUN --mount=type=cache,target=/root/.gradle \
    sed -i 's/\r$//' ./gradlew && chmod +x ./gradlew && \
    ./gradlew :${SERVICE_NAME}:clean :${SERVICE_NAME}:bootJar -x test && \
    find /app/${SERVICE_NAME}/build/libs/ \
    -name "*.jar" ! -name "*plain.jar" \
    -exec cp {} /app/app.jar \;

FROM eclipse-temurin:17-jre
WORKDIR /app

ARG SERVICE_NAME
COPY --from=build /app/app.jar app.jar

ENTRYPOINT ["java", "-jar", "app.jar"]
