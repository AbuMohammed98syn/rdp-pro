# 🏪 نشر RDP Pro على Google Play Store

## المتطلبات
- حساب Google Play Console ($25 مرة واحدة)
- keystore.jks موقَّع
- App Bundle (.aab)

## الخطوات

### 1. إنشاء Keystore
```bash
keytool -genkey -v \
  -keystore rdp-pro-keystore.jks \
  -keyalg RSA -keysize 2048 \
  -validity 10000 \
  -alias rdppro
```

### 2. إعداد GitHub Secrets
في Settings → Secrets → Actions أضف:
| Secret | القيمة |
|--------|--------|
| `KEYSTORE_BASE64` | `base64 -i rdp-pro-keystore.jks` |
| `KEYSTORE_PASS` | كلمة مرور الـ keystore |
| `KEY_ALIAS` | `rdppro` |
| `KEY_PASS` | كلمة مرور المفتاح |

### 3. بناء AAB
```bash
git tag v5.0.0
git push origin v5.0.0
# GitHub Actions يبني ويرفع تلقائياً ✓
```

### 4. Play Console
1. أنشئ تطبيقاً جديداً
2. Internal Testing → ارفع الـ .aab
3. أضف Store listing (اسم، وصف، صور)
4. Content Rating → أكمل الاستبيان
5. نشر!

## Store Listing

**الاسم**: RDP Pro — Remote Desktop

**الوصف القصير** (80 حرف):
تحكم باحق الحاسوب من هاتفك — شاشة حية، ماوس، كيبورد، ملفات، تيرمنال

**الوصف الكامل**:
RDP Pro هو تطبيق احترافي للتحكم عن بُعد يتيح لك الوصول الكامل لحاسوبك من هاتفك الأندرويد.

الميزات:
• بث الشاشة الحي بدقة عالية
• تحكم كامل بالماوس (3 أوضاع) والكيبورد
• مدير الملفات — رفع وتحميل
• تيرمنال مع 14 أمر سريع
• Wake-on-LAN لإيقاظ الحاسوب
• دردشة أثناء الجلسة
• تسجيل الجلسة
• Macro Recorder
• مزامنة الملفات
• خدمة خلفية مستمرة
• HTTPS + 2FA + IP Whitelist
• Audit Log + صلاحيات مرنة
• اتصالات متعددة

**الفئة**: أدوات (Tools)
**التصنيف العمري**: +3 (للجميع)
**السعر**: مجاني

## Screenshots المطلوبة (6.5" + 5.5")
- شاشة الاتصال
- لوحة التحكم مع Charts
- الشاشة الحية
- Terminal
- Whiteboard
- Security Dashboard
