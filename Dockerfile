# syntax=docker/dockerfile:1

# ---- build stage: BellSoft Liberica NIK (JDK 21, musl) -> musl-linked native binary ----
# NIK 25 matches the version that produced src/main/resources/.../reachability-metadata.json
# (older NIK 23 doesn't read that unified format -> the telegram-bot serializer would be
# missing at runtime). -PjdkVersion=25 compiles on the container's own JDK 25, so no second
# toolchain is provisioned; native-image also uses the container NIK 25 via JAVA_HOME.
# The container's system libc is musl, so nativeCompile yields a musl binary for the runtime
# base below. (A fully-static build hits Alpaquita gcc's default-PIE vs static-heap-relocation
# conflict; a dynamic musl binary avoids it and still runs non-root on the tiny musl base.)
FROM bellsoft/liberica-native-image-kit-container:jdk-25-nik-25.0.3-musl AS build
WORKDIR /app
COPY . .
RUN ./gradlew --no-daemon nativeCompile -PjdkVersion=25

# ---- runtime stage: tiny Alpaquita musl, non-root ----
FROM bellsoft/alpaquita-linux-base:stream-musl AS runtime

# The dynamic native binary links libz (musl libc is already in the base).
RUN apk add --no-cache zlib

# Non-root default user. The DB lives under /data, intended to be a bind-mounted host dir.
RUN adduser -D -u 10001 app && mkdir -p /data && chown app:app /data

COPY --from=build /app/build/native/nativeCompile/cfpbot /usr/local/bin/cfpbot

# DB_PATH points inside the mounted volume; override at runtime if you like.
ENV DB_PATH=/data/cfpbot \
    CHECK_HOUR=9

VOLUME ["/data"]
USER app
ENTRYPOINT ["/usr/local/bin/cfpbot"]
