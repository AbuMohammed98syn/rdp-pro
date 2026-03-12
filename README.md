# 🖥️ RDP Pro v4.0

> تطبيق Android احترافي للتحكم الكامل بالحاسوب عن بُعد — بواجهة عربية أصيلة

[![Build APK](https://github.com/AbuMohammed98syn/rdp-pro/actions/workflows/build-apk.yml/badge.svg)](https://github.com/AbuMohammed98syn/rdp-pro/actions/workflows/build-apk.yml)
[![Version](https://img.shields.io/badge/version-4.0.0-blue)](https://github.com/AbuMohammed98syn/rdp-pro/releases)
[![License](https://img.shields.io/badge/license-MIT-green)](LICENSE)

## ✨ الجديد في v4.0

| الميزة | الوصف |
|--------|-------|
| 🔌 Wake-on-LAN | تشغيل الحاسوب النائم عبر Magic Packet |
| 💬 Chat | دردشة نصية أثناء جلسة التحكم |
| ⏺ Session Recording | تسجيل الجلسة كفيديو MJPEG |
| 🔑 Temp Tokens | رموز وصول مؤقتة بصلاحية زمنية |
| ⚡ Delta Compression | ضغط تفاضلي للشاشة — أسرع بـ 40% |
| 🔄 Smart Reconnect | إعادة اتصال تلقائي Exponential Backoff |
| 🖱️ Floating Cursor | مؤشر عائم يظهر موضع الفأرة |

## 🚀 التثبيت السريع

### 1. Backend (الحاسوب)
```bash
pip install fastapi uvicorn pillow psutil pyautogui
python backend/main.py
```

### 2. Android App
- حمّل APK من [Releases](https://github.com/AbuMohammed98syn/rdp-pro/releases)
- فعّل "مصادر غير معروفة"
- ثبّت وشغّل

### 3. الاتصال
```
IP: 192.168.1.x
Port: 8000
Token: rdppro-secret-2024
```

## 📱 المتطلبات

- **Android**: 7.0+ (API 24)
- **Python**: 3.9+
- **شبكة**: WiFi محلية (أو Internet)

## 🏗️ البناء التلقائي

كل `push` على `main` يبني APK تلقائياً عبر GitHub Actions:

```bash
git add .
git commit -m "feat: new feature"
git push origin main
# → APK جاهز في Actions → Artifacts
```

لإصدار رسمي:
```bash
git tag v4.0.0
git push origin v4.0.0
# → Release ينشأ تلقائياً مع APK
```

## 📂 هيكل المشروع

```
rdp-pro/
├── app/
│   └── src/main/
│       ├── java/com/rdppro/rdpro/
│       │   ├── RdpService.kt       ← الشبكة + WoL + Chat
│       │   ├── ConnectActivity.kt  ← الاتصال + WoL UI
│       │   ├── MainActivity.kt     ← التنقل الرئيسي
│       │   ├── ScreenFragment.kt   ← الشاشة + Chat + Recording
│       │   ├── DashboardFragment.kt← إحصائيات النظام
│       │   ├── FilesFragment.kt    ← إدارة الملفات
│       │   ├── TerminalFragment.kt ← PowerShell/CMD
│       │   └── ...
│       └── res/layout/             ← ملفات XML
├── backend/
│   └── main.py                    ← FastAPI v4.0
├── website/
│   └── index.html                 ← موقع التطبيق
└── .github/workflows/
    └── build-apk.yml              ← CI/CD
```

## 🔌 Wake-on-LAN Setup

1. فعّل WoL في BIOS الحاسوب
2. احصل على MAC Address: `ipconfig /all` (Windows)
3. أدخل MAC في التطبيق + اضغط "إيقاظ"

## 🤝 المساهمة

PRs مرحّب بها! راجع [ROADMAP.md](ROADMAP.md) للخطة المستقبلية.

---

**صنع بـ ❤️ للمجتمع العربي**
