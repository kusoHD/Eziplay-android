# Eziplay Android

Veb-sayt (index.html) WebView ichida ochiladigan Android ilova. Yangilanishlar GitHub Releases orqali keladi.

## Bir martalik sozlash

1. `app/build.gradle` ichida `GITHUB_REPO` ni o'z repongizga o'zgartiring, masalan `"ali/eziplay-android"`. Repo **public** bo'lishi kerak.
2. Imzo kaliti yarating (bir marta, faylni xavfsiz saqlang — keyin o'zgartirib bo'lmaydi):
   ```
   keytool -genkeypair -v -keystore eziplay.jks -alias eziplay -keyalg RSA -keysize 2048 -validity 10000
   ```
3. Kalitni base64 ga aylantiring:
   - Linux/Mac: `base64 -w0 eziplay.jks`
   - Windows PowerShell: `[Convert]::ToBase64String([IO.File]::ReadAllBytes("eziplay.jks"))`
4. GitHub repo → **Settings → Secrets and variables → Actions** da 4 ta secret qo'shing:
   - `KEYSTORE_BASE64` — 3-qadamdagi matn
   - `KEYSTORE_PASSWORD` — kalit paroli
   - `KEY_ALIAS` — `eziplay`
   - `KEY_PASSWORD` — kalit paroli (odatda o'sha)

Eslatma: avval debug APK bilan o'rnatilgan bo'lsa, bir marta o'chirib, yangi (release) APKni o'rnating. Keyin yangilanishlar ustiga o'rnatiladi.

## Yangi versiya chiqarish

1. `app/build.gradle` da `versionCode` (butun son, har safar oshiring) va `versionName` (masalan `1.2.29`) ni oshiring.
2. `main` branchga push qiling. GitHub Actions APK quradi va `v1.2.29` tagli Release yaratadi.
3. Ilova ochilganda yangi versiya topilsa, "Yangilash" oynasi chiqadi. "Yangilash" bosilsa APK GitHub'dan yuklanadi va o'rnatish oynasi ochiladi.

Muhim: `app/src/main/assets/index.html` ni o'zgartirganda, o'sha fayl ustidan yangi nusxa yozing. Versiyani oshirmasangiz, foydalanuvchilarga yangilanish ko'rinmaydi.
