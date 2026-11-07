# Slick

An Apple TV-style home screen for Fire TV sticks, plus the lists used to strip Amazon's ads and services.

- A dock of the apps you pick, on frosted glass, with no ads or sponsored rows
- The backdrop shows what's trending on the focused app (TMDB); Up opens it wide, OK plays it in its app
  (Watchmode title links), and anything not on Netflix, Disney+ or Prime Video opens in Stremio
- Picks per audience (Kids, Family, Adults) on a day/evening schedule
- Search any movie or show (TMDB); titles for the current audience come first, and OK opens it like a pick
- Light glass by day, dark after sunset; clock, Wi-Fi strength, Settings
- Fire OS won't hand Home to another app, so an accessibility service sends Amazon's home screen back to Slick

## Build

Needs a JDK 17, Android SDK platform 34 and build-tools 36 (no Gradle).

```bash
cp launcher/config.example.properties launcher/config.properties   # fill in the keys and your location
sh launcher/build.sh
```

## Install on a Fire TV

Turn on ADB debugging on the TV (Settings > My Fire TV > Developer options), then:

```bash
adb connect <tv-ip>:5555
adb install -r launcher/build/slick.apk
adb shell pm grant tv.slick android.permission.WRITE_SECURE_SETTINGS
adb shell am start -n tv.slick/.HomeActivity
```

The permission lets Slick switch its Home guard back on after updates and reboots (Fire OS turns it off).

## Debloat

`debloat/*.txt` are packages turned off with `pm disable-user` (nothing is uninstalled). Based on
[cleanyourtv](https://github.com/Gathin23/cleanyourtv)'s list for the Fire TV Stick 4K Max (2nd gen).

```bash
TV=<tv-ip>:5555 sh debloat/apply.sh disable debloat/1-ads.txt   # undo: enable
TV=<tv-ip>:5555 sh debloat/verify.sh
```
