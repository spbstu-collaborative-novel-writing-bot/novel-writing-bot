FROM gradle:9.4.1-jdk25 AS build
WORKDIR /home/gradle/project
COPY --chown=gradle:gradle . .
RUN gradle --no-daemon clean test shadowJar

FROM eclipse-temurin:25-jre
WORKDIR /app
COPY --from=build /home/gradle/project/build/libs/app.jar /app/app.jar
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
