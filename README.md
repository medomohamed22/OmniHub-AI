# AiWay Native Android 1.2

تطبيق Android Native بالكامل باستخدام Kotlin + Jetpack Compose، بدون WebView وبدون Backend خارجي لتسجيل ChatGPT.

## الجديد في 1.2

- تحسين تسجيل **Continue with ChatGPT** عبر OAuth + PKCE بالطريقة الرسمية لتطبيقات open-source/local.
- callback محلي على `127.0.0.1` مع listener يتحمل اتصالات Chromium الإضافية، ويفضل المنفذ `1455` ثم يستخدم منفذاً متاحاً عند الحاجة.
- بعد نجاح callback، صفحة محلية تؤكد النجاح وتحاول إعادة فتح AiWay تلقائياً عبر `aiway://oauth-complete`.
- استخدام Android Custom Tabs بدلاً من WebView لصفحة OpenAI الرسمية.
- اختيار موديل من الموديلات المتاحة فعلياً للحساب عبر `/v1/models`.
- أدوات قابلة للتفعيل/الإيقاف: قراءة الملفات، كتابة/إنشاء الملفات، حذف الملفات، والبحث في الويب عندما يدعمه الموديل والحساب.
- Light / Dark mode مع زر تبديل وحفظ الاختيار محلياً.
- إعادة تصميم الواجهة بأسلوب AiWay: أزرق/أبيض، بطاقات مستديرة، قائمة جانبية، شاشة ترحيب، ومحرر محادثة حديث.
- صفحة Usage داخل الإعدادات تربط إلى `https://chatgpt.com/settings/usage` لعرض الحصة الرسمية وإعادة التعيين.

## لماذا لا يعرض التطبيق نسبة 5 ساعات/الأسبوع كرقم داخلي؟

توثيق Sign in with ChatGPT الحالي يوجّه تطبيقات open-source إلى ChatGPT Settings → Usage لمراجعة الاستهلاك وإدارة حدود التطبيق. لا يوجد endpoint موثق في هذا المسار يعيد النسبة الدقيقة لنافذة 5 ساعات أو الأسبوع أو الـreset، لذلك AiWay لا يخمّن أرقاماً غير موثوقة ويعطي رابط الصفحة الرسمية.

## البناء على Codemagic

1. ارفع محتويات هذا المجلد إلى جذر مستودع GitHub.
2. أضف التطبيق إلى Codemagic.
3. اختر `codemagic.yaml`.
4. شغّل workflow: **AiWay Native Direct APK**.
5. حمّل APK من Artifacts.

لا تحتاج `BACKEND_URL` أو OpenAI API key أو client secret.

## تسجيل ChatGPT

من داخل التطبيق: الإعدادات والموديل → Continue with ChatGPT. ستفتح صفحة OpenAI الرسمية في Custom Tab. بعد الموافقة سيعيد OpenAI التوجيه إلى HTTP loopback على `127.0.0.1`. عندما ترى رسالة نجاح، AiWay يحاول الرجوع للتطبيق تلقائياً ويكمل token exchange والتحقق ثم يجلب الموديلات.

## أمان

- OAuth Authorization Code + PKCE.
- لا يوجد client secret داخل APK.
- access/refresh/id tokens تحفظ مشفرة بمفتاح Android Keystore.
- Responses API يستخدم `store=false` و`stream=true`.
- حذف الملفات أداة مغلقة افتراضياً.
