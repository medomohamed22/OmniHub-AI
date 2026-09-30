# AiWay Android — Codemagic ready

هذا المستودع جاهز للبناء على **Codemagic** كتطبيق Android WebView لنسخة AiWay المنشورة على Vercel.

## لماذا التطبيق يعتمد على رابط Vercel؟
AiWay يستخدم API server-side وWebSocket وGitHub OAuth وCodex داخل `/api/*`. لذلك التطبيق يعرض النسخة المنشورة داخل WebView بدل نسخ HTML محليًا داخل APK، حتى تبقى هذه الوظائف شغالة.

## الاستخدام على Codemagic
1. ارفع **محتويات هذا المجلد كما هي** إلى مستودع GitHub، وتأكد أن `codemagic.yaml` موجود في جذر المستودع.
2. في Codemagic اختر **Add application** ثم اربط مستودع GitHub.
3. اختر الإعداد باستخدام `codemagic.yaml`.
4. من **Environment variables** أضف متغيرًا باسم `APP_URL` وقيمته رابط Vercel الحقيقي، مثال: `https://your-project.vercel.app`.
5. شغّل workflow باسم **AiWay Android APK**.
6. بعد نجاح البناء ستجد `app-debug.apk` في قسم **Artifacts**.

> يجب أن يبدأ `APP_URL` بـ `https://`.

## ما تم تجهيزه لـ Codemagic؟
- `codemagic.yaml` في جذر المستودع.
- JDK 17.
- Android SDK يتم ربطه تلقائيًا عبر `ANDROID_SDK_ROOT`.
- Gradle 8.9 مثبت بشكل ثابت من خلال سكربت `gradlew`، وهو متوافق مع Android Gradle Plugin 8.7.x.
- Cache لـ Gradle لتسريع البنايات التالية.
- APK debug يتم حفظه تلقائيًا كـ Artifact.
- `versionCode` يأخذ رقم Build الخاص بـ Codemagic تلقائيًا عند توفره.
- رابط الموقع `APP_URL` يُحقن وقت البناء، فلا تحتاج لتعديل `strings.xml` في كل مرة.

## البناء محليًا
يتطلب JDK 17 وAndroid SDK ثم:

```bash
./gradlew assembleDebug
```

الناتج:

```text
app/build/outputs/apk/debug/app-debug.apk
```

## إصدار Google Play
الـ workflow الحالي ينتج Debug APK للاختبار والتثبيت المباشر. للنشر على Google Play يجب إضافة Android keystore في Codemagic وربطه ببناء `bundleRelease` لإخراج AAB موقّع.

## ملفات الموقع
المجلد `web-source/` يحتوي نسخة من سورس الموقع للرجوع إليها، لكنه ليس الجزء الذي يتم تجميعه داخل APK. التطبيق يتصل مباشرة بعنوان `APP_URL`.
