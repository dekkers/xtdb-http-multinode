FROM clojure:temurin-11-lein-bullseye-slim AS build
COPY . /code
WORKDIR /code
RUN lein uberjar

FROM eclipse-temurin:11-jre
ENV MALLOC_ARENA_MAX=2
ENV JAVA_TOOL_OPTIONS="-Xms128M -Xmx512M -XX:MaxDirectMemorySize=512M -XX:+ExitOnOutOfMemoryError"
VOLUME /var/lib/xtdb
EXPOSE 3000
WORKDIR /app
ENTRYPOINT ["/app/entrypoint.sh"]

RUN --mount=type=cache,target=/var/cache/apt \
  apt-get update \
  && apt-get -y upgrade \
  && apt-get install -y --no-install-recommends rocksdb-tools \
  && rm -rf /var/lib/apt/lists/*

COPY entrypoint.sh .
COPY --from=build /code/target/*uberjar/*-standalone.jar ./app.jar
CMD ["java", "-jar", "app.jar"]
