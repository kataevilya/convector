# HTML2APK Converter

Локальный конвертер HTML/CSS/JS → установочный `.apk` **прямо на смартфоне**, без интернета,
без ПК, без Android Studio и без Gradle на телефоне.

Сборка APK на устройстве работает не через компиляцию, а через **патчинг подготовленного
APK-контейнера**:

1. `assets/template.apk` (минимальное WebView-приложение, собирается модулем `:template-webview`)
   распаковывается в память;
2. веб-файлы кладутся в `assets/web/`;
3. бинарный `AndroidManifest.xml` (AXML) патчится: `package`, `versionCode`, `versionName`,
   `android:screenOrientation`, `android:hardwareAccelerated`;
4. `resources.arsc` патчится: строка ресурса `app_name` (название приложения);
5. иконки `res/mipmap-*/ic_launcher.png` (mdpi…xxxhdpi) генерируются из выбранной картинки;
6. ZIP пересобирается с выравниванием (`resources.arsc` — 4 байта, `.so` — 4096 байт, без сжатия);
7. APK подписывается схемами **v1 + v2** библиотекой `com.android.tools.build:apksig`;
   ключ RSA-2048 + самоподписанный сертификат генерируется на телефоне при первом запуске.

## Структура

```
.
├── .github/workflows/build-converter.yml   # CI: шаблон -> приложение -> artifact + release
├── app/                                    # само приложение-конвертер (Compose, Material 3)
│   └── src/main/java/com/html2apk/converter/
│       ├── core/AppBuilder.kt              # оркестратор сборки APK
│       ├── core/binary/Bytes.kt            # little-endian чтение/запись
│       ├── core/binary/StringPoolChunk.kt  # RES_STRING_POOL: разбор и пересборка
│       ├── core/binary/AxmlPatcher.kt      # патчер бинарного AndroidManifest.xml
│       ├── core/binary/ArscPatcher.kt      # патчер resources.arsc (app_name)
│       ├── core/zip/ApkWriter.kt           # zipalign + сборка ZIP
│       ├── core/sign/                      # DER, X.509, PKCS#12, apksig v1+v2
│       ├── core/WebAssets.kt               # HTML/ZIP -> assets/web
│       └── ui/                             # Jetpack Compose UI (тёмная тема)
├── template-webview/                       # WebView-шаблон, из него получается template.apk
└── gradle/libs.versions.toml
```

## Как получить готовый APK

1. Загрузите все файлы в свой репозиторий на GitHub (ветка `main`).
2. Откройте вкладку **Actions** → workflow **Build HTML2APK Converter** → **Run workflow**
   (или просто сделайте push в `main`).
3. Через ~5 минут заберите APK:
   * **Actions** → последний запуск → блок **Artifacts** → `HTML2APK-Converter`;
   * либо **Releases** → `v1.0.N` → `HTML2APK-Converter.apk`.
4. Скачайте APK на телефон, разрешите установку из этого источника — и пользуйтесь.

## Локальная сборка (необязательно)

```bash
gradle :app:assembleRelease   # нужен JDK 17 + Android SDK 34
```

`gradle :app:assembleRelease` сам сначала соберёт `:template-webview` и положит его APK
в `app/build/generated/templateAssets/template.apk`.
