# CLAUDE.md — Aeronautics Schematics (aeroschematics)

Контекст для Claude Code. Отвечай пользователю на русском.

**Всегда давай ссылку на скачивание jar** после сборки: собери `./gradlew releaseJar` (jar копируется в
`aeroschematics/release/`), закоммить jar, запушь ветку и дай ссылку
`https://github.com/Iksanbro/Radar-gradle/raw/<ветка>/aeroschematics/release/aeroschematics-<версия>.jar`;
дополнительно отправь сам файл в чат. При изменениях мода повышай `mod_version` в `gradle.properties`.

## Проверка перед выпуском
- `./gradlew build` — юнит-тесты (санитайзер NBT, пересчёт ссылок, порядок печати).
- `./gradlew runGameTestServer` — игровые тесты на сервере с Create, Sable/Aeronautics, Flight Control,
  Create Big Cannons, Firepower Components, Drive-By-Wire (моды качаются с Modrinth автоматически).

Устройство мода — в `README.md`.
