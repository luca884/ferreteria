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
# Railway cobra la memoria tomada, no la usada. glibc con 2 arenas no la fragmenta por hilo (vale también para la JVM del
# lector de PDF, que hereda el entorno).
ENV MALLOC_ARENA_MAX=2
# java -jar: en el jar empaquetado el lector de PDF arranca su JVM con PropertiesLauncher.
# El tope de 768 MB es para las listas grandes. G1 explícito (con 1 vCPU la JVM elegiría Serial) y GC periódico cada 5 min
# en reposo (JEP 346): pasado un pico devuelve la memoria que sobra hasta dejar entre 10 % y 30 % libre.
ENTRYPOINT ["java", "-Xmx768m", "-XX:+UseG1GC", "-XX:G1PeriodicGCInterval=300000", "-XX:MinHeapFreeRatio=10", "-XX:MaxHeapFreeRatio=30", "-jar", "app.jar"]
