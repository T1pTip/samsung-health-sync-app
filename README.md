# Health Sync

Minimal Android app: reads Steps + Distance + Calories from Health Connect
(synced from Samsung Health) and upserts daily totals into Supabase.
The Health Hub PWA reads from Supabase.

## Build (no Android Studio needed)
1. Create the GitHub repo and push (see commands below).
2. GitHub Actions builds the APK automatically on push.
3. Open the repo -> Actions tab -> latest run -> Artifacts -> download "app-debug".
4. Unzip -> app-debug.apk -> transfer to phone -> install (allow "unknown apps").

## Supabase
Run supabase_setup.sql once in the Supabase SQL editor.

## Use
1. On the phone, open Health Connect once and confirm Samsung Health is syncing
   Steps / Distance / Calories.
2. Open Health Sync -> tap "Sync now" -> grant the Health Connect permissions.
3. It writes the last 7 days into the health_daily table.

## Stack
AGP 8.7.2 / Gradle 8.9 / JDK 17 / Kotlin 1.9.24 /
androidx.health.connect:connect-client 1.1.0 / minSdk 28 / compileSdk 34
