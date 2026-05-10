FROM gradle:9.4.1-jdk21 AS build
WORKDIR /home/gradle/project
COPY --chown=gradle:gradle . .
RUN gradle --no-daemon clean test shadowJar

FROM eclipse-temurin:21-jre
WORKDIR /app
COPY --from=build /home/gradle/project/build/libs/app.jar /app/app.jar
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
