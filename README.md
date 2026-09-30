# AiWay Native Android — Direct ChatGPT / OpenAI

تطبيق Android Native حقيقي مبني بـ Kotlin + Jetpack Compose. لا يستخدم WebView ولا يحتاج Backend خارجي لتسجيل ChatGPT أو تشغيل المساعد.

## المعمارية

```text
Android App
  ├─ Sign in with ChatGPT (OAuth Authorization Code + PKCE)
  │    └─ loopback callback: http://127.0.0.1:<port>/auth/callback
  ├─ Android Keystore → access / refresh / ID tokens
  ├─ https://api.openai.com/v1/models
  ├─ https://api.openai.com/v1/responses (store=false, stream=true)
  │    └─ workspace tools are executed locally on the phone
  └─ GitHub REST API directly
```

## لماذا لا يوجد Backend؟

OpenAI تدعم للتطبيقات المفتوحة المصدر والمحلية تسجيلًا ديناميكيًا بـ `dynamic_agent_client`. التطبيق لا يحتاج client secret. بعد موافقة المستخدم يحصل على OAuth access token بصلاحية `chatgpt.tokens.use.direct` ويرسل الطلبات مباشرة إلى Responses API.

## تسجيل ChatGPT

1. افتح الإعدادات داخل التطبيق.
2. اضغط **Continue with ChatGPT**.
3. يفتح المتصفح الرسمي لـ OpenAI.
4. بعد الموافقة يعيد OpenAI المتصفح إلى listener محلي على `127.0.0.1` يعمل داخل التطبيق أثناء عملية تسجيل الدخول.
5. التطبيق يتحقق من `state` وPKCE و`nonce`، ويتحقق من توقيع `id_token` (RS256) باستخدام OpenAI JWKS.
6. التوكنات تُخزن مشفرة بـ Android Keystore ويُستخدم refresh token تلقائيًا قبل انتهاء access token.

لا يوجد API key ولا client secret داخل APK.

## تشغيل المساعد

- التطبيق يجلب قائمة الموديلات المتاحة للحساب من `/v1/models`.
- يرسل طلبات إلى `/v1/responses` بـ `store=false` و`stream=true`.
- أدوات `workspace` (list/read/write/delete) تنفذ محليًا داخل التطبيق، لذلك الموديل يعدّل ملفات المشروع الفعلية على الهاتف بدون سيرفر وسيط.

## Codemagic

ارفع محتويات هذا المجلد إلى جذر مستودع GitHub، ثم اربطه بـ Codemagic. لا تحتاج أي Environment Variables خاصة بـ OpenAI.

Workflow:

```text
AiWay Native Direct APK
```

Artifact:

```text
app/build/outputs/apk/debug/app-debug.apk
```

## GitHub

GitHub يعمل مباشرة من التطبيق باستخدام Fine-grained Personal Access Token، ويُخزن التوكن مشفرًا بـ Android Keystore.

## ملاحظات

- هذا المشروع يعتمد على ميزة Sign in with ChatGPT / ChatGPT plan usage الخاصة بالتطبيقات المفتوحة المصدر والمحلية، وقد تكون خاضعة لقيود preview أو سياسات الحساب/workspace.
- التطبيق لا يستخدم endpoints داخلية لـ ChatGPT؛ يستخدم فقط `auth.openai.com` و`api.openai.com/v1`.
- تسجيل الخروج داخل التطبيق يمسح بيانات الاعتماد محليًا. يمكن للمستخدم أيضًا إدارة/إلغاء وصول التطبيق من إعدادات ChatGPT.
