#!/bin/sh
# Builds Slick without Gradle: aapt2 -> javac -> d8 -> zipalign -> apksigner.
set -eu
cd "$(dirname "$0")"

SDK="${ANDROID_HOME:-$HOME/Library/Android/sdk}"
BT="$SDK/build-tools/36.0.0"
JAR="$SDK/platforms/android-25/android.jar"
OUT=build

rm -rf "$OUT"
mkdir -p "$OUT/gen" "$OUT/classes" "$OUT/dex"

prop() { sed -n "s/^$1=//p" config.properties; }
for k in tmdb_token watchmode_key trakt_client_id; do
  [ -n "$(prop $k)" ] || { echo "config.properties needs $k" >&2; exit 1; }
done
mkdir -p "$OUT/gen/tv/slick"
cat > "$OUT/gen/tv/slick/Config.java" <<JAVA
package tv.slick;

final class Config {
    static final String TMDB_TOKEN = "$(prop tmdb_token)";
    static final String REGION = "$(prop region)";
    static final double LATITUDE = $(prop latitude);
    static final double LONGITUDE = $(prop longitude);
    static final String WATCHMODE_KEY = "$(prop watchmode_key)";
    static final String TRAKT_CLIENT_ID = "$(prop trakt_client_id)";
}
JAVA

"$BT/aapt2" compile --dir res -o "$OUT/res.zip"
"$BT/aapt2" link -I "$JAR" --manifest AndroidManifest.xml \
  --min-sdk-version 25 --target-sdk-version 30 --version-code 1 --version-name 1.0 \
  --java "$OUT/gen" -o "$OUT/unsigned.apk" "$OUT/res.zip"

javac -source 8 -target 8 -bootclasspath "$JAR:$BT/core-lambda-stubs.jar" -Xlint:-options -d "$OUT/classes" \
  $(find src "$OUT/gen" -name '*.java')
"$BT/d8" --min-api 25 --lib "$JAR" --output "$OUT/dex" $(find "$OUT/classes" -name '*.class')

(cd "$OUT/dex" && zip -q ../unsigned.apk classes.dex)
"$BT/zipalign" -f 4 "$OUT/unsigned.apk" "$OUT/aligned.apk"
"$BT/apksigner" sign --ks "$HOME/.android/debug.keystore" --ks-pass pass:android \
  --out "$OUT/slick.apk" "$OUT/aligned.apk"
echo "$OUT/slick.apk"
