# Imagen del backend para Railway: compila el jar con Maven y lo corre en una JRE 17.
FROM eclipse-temurin:17-jdk AS build
WORKDIR /app
COPY .mvn .mvn
COPY mvnw pom.xml ./
RUN ./mvnw -B -q dependency:go-offline
COPY src src
# Los tests necesitan Docker (Testcontainers): corren afuera, no en la imagen.
RUN ./mvnw -B -q package -DskipTests

# JRE completa: el lector de PDF lanza otra JVM con java.home y el classpath del jar (LectorPdf).
FROM eclipse-temurin:17-jre
WORKDIR /app
# La zona de la JVM no puede cambiar entre deploys (docs/spec.md, "Despliegue: zona horaria").
ENV TZ=America/Argentina/Buenos_Aires
RUN useradd --system --uid 10001 ferreteria
COPY --from=build /app/target/ferreteria-api-*.jar app.jar
USER ferreteria
EXPOSE 8080
# java -jar: en el jar empaquetado el lector de PDF arranca su JVM con PropertiesLauncher.
ENTRYPOINT ["java", "-Xmx768m", "-jar", "app.jar"]
