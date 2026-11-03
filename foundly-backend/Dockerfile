FROM eclipse-temurin:21-jdk-jammy AS build
WORKDIR /build

COPY .mvn/ .mvn/
COPY mvnw pom.xml ./
RUN chmod +x mvnw && ./mvnw -B -ntp dependency:go-offline

COPY src/ src/
# Tests are scheduled for a separate sprint; build the application without test compilation.
RUN ./mvnw -B -ntp -Dmaven.test.skip=true package

FROM eclipse-temurin:21-jre-jammy
WORKDIR /app

RUN groupadd --system foundly && useradd --system --gid foundly foundly
COPY --from=build --chown=foundly:foundly /build/target/*.jar /app/app.jar

USER foundly:foundly
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
