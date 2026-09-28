# Builds the Teachable Voice APK in a clean container.
#
#   docker build -t teachable-voice --build-arg FIREWORKS_API_KEY=fw_... .
#   docker run --rm -v "$PWD/out:/out" teachable-voice
#   -> out/teachable-voice.apk
#
# The key is optional at build time; without it the app still records and replays learned tasks
# on the fast path, and the language-model features (paraphrases, pop-ups, questions) stay off.
FROM eclipse-temurin:17-jdk

ENV ANDROID_SDK_ROOT=/opt/android-sdk
ENV PATH=$PATH:/opt/android-sdk/cmdline-tools/latest/bin:/opt/android-sdk/platform-tools

RUN apt-get update && apt-get install -y --no-install-recommends unzip wget ca-certificates \
    && rm -rf /var/lib/apt/lists/*

RUN mkdir -p $ANDROID_SDK_ROOT/cmdline-tools \
    && wget -q https://dl.google.com/android/repository/commandlinetools-linux-11076708_latest.zip -O /tmp/clt.zip \
    && unzip -q /tmp/clt.zip -d /tmp/clt \
    && mv /tmp/clt/cmdline-tools $ANDROID_SDK_ROOT/cmdline-tools/latest \
    && rm -rf /tmp/clt /tmp/clt.zip \
    && yes | sdkmanager --licenses > /dev/null \
    && sdkmanager "platform-tools" "platforms;android-35" "build-tools;35.0.0" > /dev/null

WORKDIR /src
COPY android/ /src/android/

ARG FIREWORKS_API_KEY=""
RUN printf '%s' "$FIREWORKS_API_KEY" > /src/.fireworks_key_submission \
    && cd /src/android \
    && echo "sdk.dir=$ANDROID_SDK_ROOT" > local.properties \
    && chmod +x gradlew \
    && ./gradlew assembleRelease --no-daemon --console=plain \
    && cp app/build/outputs/apk/release/app-release.apk /src/teachable-voice.apk \
    && rm -f /src/.fireworks_key_submission

CMD ["sh", "-c", "mkdir -p /out && cp /src/teachable-voice.apk /out/ && echo 'APK written to /out/teachable-voice.apk'"]
