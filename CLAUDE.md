# Claude Code Instructions

@AGENTS.md

## Bumping `telegram-bot` (Renovate PRs) — regenerate native-image metadata

The app ships as a GraalVM **native image**. `telegram-bot` resolves some serializers
reflectively, so a version bump can add a serializer that the hand-maintained
`src/main/resources/META-INF/native-image/cfpbot/reachability-metadata.json` doesn't cover.
Symptom: every send throws `SerializationException` at runtime — silent, since the native build
succeeds. (This is what broke 9.5.0 → 9.6.0: the `InlineKeyboardMarkup` serializer was missing.)

**On any `telegram-bot` bump, regenerate the metadata before merging:**

1. Record what the real send path needs, via the GraalVM tracing agent on a NIK JDK whose version
   matches the Dockerfile (`bellsoft/liberica-native-image-kit-container:jdk-25-nik-25.0.3-musl`
   → use a local `*.r25-nik` JDK, e.g. `~/.sdkman/candidates/java/25.0.3.r25-nik`):
   ```
   NIK=~/.sdkman/candidates/java/25.0.3.r25-nik
   CP=$(./gradlew -q printTestCp | tail -1)
   rm -rf build/agent-out
   "$NIK/bin/java" -agentlib:native-image-agent=config-output-dir=build/agent-out -cp "$CP" cfpbot.AgentDriverKt
   ```
   `cfpbot.AgentDriver` drives the real `TelegramNotifier` send/sendReminder/sendLocation against a
   mock engine (offline — no token, no network).
2. Diff the recorded vendeli serializer types against the committed metadata; add any missing
   `type` + `$Companion { serializer }` entries (copy an existing block, e.g. `msg.Message`):
   ```
   comm -23 <(grep -oE '"eu\.vendeli\.tgbot[^"]*"' build/agent-out/reachability-metadata.json | sort -u) \
            <(grep -oE '"eu\.vendeli\.tgbot[^"]*"' src/main/resources/META-INF/native-image/cfpbot/reachability-metadata.json | sort -u)
   ```
3. Verify: `./gradlew test` and `JAVA_HOME="$NIK" ./gradlew nativeCompile -PjdkVersion=25` (must
   build with no serializer/reflection warnings). `TelegramNotifierSendTest` also guards the send
   round-trip on the JVM.

## Secrets

- `.env` contains secrets including `BOT_TOKEN`. NEVER read, print, cat, or commit `.env` or its contents.
- To run the app with the token, load it in the shell without echoing it (e.g. `set -a; . ./.env; set +a`); never expand or display the value.
- `.env` is gitignored — keep it that way.
