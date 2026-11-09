# Slick

An Apple TV-style home screen for Fire TV sticks, plus the lists used to strip Amazon's ads and services.

- A dock of the apps you pick, on frosted glass, with no ads or sponsored rows
- The backdrop shows what's trending and newly arrived on the focused app (TMDB, Watchmode); Up opens it
  wide, OK plays it in its app, and anything not on Netflix, Disney+ or Prime Video opens in Stremio.
  Picks only show for apps installed on that TV.
- Picks per audience (Kids, Family, Adults) on a day/evening schedule, with Halloween and Christmas picks
  in season
- Adults picks from Trakt (optional, one sign-in per TV in Settings): new seasons of shows you watch,
  your next episode, your watchlist
- Search any movie or show (TMDB); titles for the current audience come first
- Light glass by day, dark after sunset; clock, Wi-Fi strength, Settings
- Home Assistant: a drawer of light, sound and climate tiles, opened from the house button or with ☰ over
  whatever is playing (it keeps playing); a doorbell ring shows the door cameras in a corner
- Fire OS won't hand Home to another app, so Slick sends Amazon's home screen back to itself

## Build

Needs JDK 17, and from the Android SDK: platform 25 (Android 7.1) and build-tools 36. No Gradle.

```bash
sdkmanager "platforms;android-25" "build-tools;36.0.0"
cp launcher/config.example.properties launcher/config.properties   # fill in your keys and location
sh launcher/build.sh
```

Keys: a [TMDB](https://www.themoviedb.org/settings/api) read access token, a
[Watchmode](https://api.watchmode.com) API key, and a [Trakt](https://trakt.tv/oauth/applications) app's
client ID (redirect URI `urn:ietf:wg:oauth:2.0:oob`). They end up inside the APK: share the code, not
your build.

## Install on a Fire TV

Turn on ADB debugging on the TV (Settings > My Fire TV > Developer options), then:

```bash
adb connect <tv-ip>:5555
adb install -r launcher/build/slick.apk
adb shell pm grant tv.slick android.permission.WRITE_SECURE_SETTINGS
adb shell am start -n tv.slick/.HomeActivity
```

The permission lets Slick switch its Home guard (an accessibility service) back on after updates and
reboots: Fire OS turns it off.

### Older sticks (Fire OS 6, Android 7.1)

Fire OS 6 won't run third-party accessibility services, so there Slick watches which screen is in front
instead, and covers Amazon's home screen with black for the few seconds Android 7.1 holds app starts after
Home. It needs two more permissions:

```bash
adb shell appops set tv.slick GET_USAGE_STATS allow
adb shell appops set tv.slick SYSTEM_ALERT_WINDOW allow
```

The ☰ drawer over other apps and the doorbell card need the accessibility service, so they don't work on
Fire OS 6; the drawer still opens from Slick's house button.

## Home Assistant

The drawer has sound and climate up top, then lights, then the door cameras. OK toggles a light (hold OK
to dim it); on sound and climate, OK starts adjusting: Left/Right change the volume or target temperature,
Up/Down the climate mode. OK on a camera shows the cameras big; Back closes them. Slick only ever controls
lights, climate, scripts and speaker volume; locks, alarms, garage doors and covers are refused in code.

Add `ha_url` and `ha_token` (a long-lived token of a non-admin Home Assistant user) to
`launcher/config.properties`, list the TV's devices in a `launcher/home-<room>.devices` file
(`entity_id=Name` per line, git-ignored), then:

```bash
sh launcher/home-setup.sh <tv-ip> launcher/home-<room>.devices
```

The token reaches the TV only over adb, never the APK. Change a TV's devices later in Settings > Home
controls.

For the doorbell, set `ha_doorbell` to the ring sensor, then each camera as `small|large` (a light stream
for the corner, a sharp one for the big view), separated by `;`:

```
ha_doorbell=binary_sensor.front_door_ring;camera.door_fluent|camera.door_clear
```

When the sensor turns on, the cameras show in a corner over anything; OK enlarges them, Back closes them.
To try it without ringing: `adb shell am broadcast -a tv.slick.RING`.

## Debloat

`debloat/*.txt` are packages turned off with `pm disable-user` (nothing is uninstalled). Based on
[cleanyourtv](https://github.com/Gathin23/cleanyourtv)'s list for the Fire TV Stick 4K Max (2nd gen).

```bash
TV=<tv-ip>:5555 sh debloat/apply.sh disable debloat/1-ads.txt   # undo: enable
TV=<tv-ip>:5555 sh debloat/verify.sh
```
