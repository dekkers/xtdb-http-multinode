FROM clojure:temurin-11-lein-bullseye-slim AS build
COPY . /code
WORKDIR /code
RUN lein uberjar

FROM openjdk:11-jre-slim
ENV MALLOC_ARENA_MAX=2
VOLUME /var/lib/xtdb
EXPOSE 3000
WORKDIR /app
COPY --from=build /code/target/*uberjar/*-standalone.jar ./app.jar
CMD ["java", "-jar", "app.jar"]
