# Industrial Circuit Studio — Phase 1

پروژه Native Android با Kotlin و Jetpack Compose برای شروع ساخت محیط طراحی مدار صنعتی.

## امکانات پیاده‌سازی‌شده در این فاز
- بوم سفید بدون Grid، با Zoom و Pan دو انگشتی
- افزودن قطعات پایه‌ی نمایشی از نوار ابزار
- انتخاب و جابه‌جایی قطعه
- چرخش و حذف قطعه انتخاب‌شده
- ترمینال‌های قابل انتخاب و اتصال Wire بین ترمینال‌ها
- نمایش مسیر سیم به‌صورت Orthogonal (۹۰ درجه)
- Undo / Redo برای عملیات اصلی
- ذخیره و بازکردن فایل JSON با پسوند `.fbd` از طریق Android Storage Access Framework
- مدل مستقل Component / Terminal / Wire و جداسازی اولیه‌ی Editor از UI

## باز کردن در GitHub و ساخت APK
1. کل محتویات ZIP را در یک مخزن GitHub قرار دهید.
2. مخزن را با Android Studio باز کنید.
3. از JDK 17 استفاده کنید.
4. صبر کنید Gradle Sync تمام شود.
5. از مسیر **Build > Build Bundle(s) / APK(s) > Build APK(s)** خروجی بگیرید.

یا مخزن را Push کنید؛ Workflow موجود در `.github/workflows/android.yml` با هر Push به `main` یا `master` پروژه را Build می‌کند و APK را به‌عنوان Artifact منتشر می‌کند.

## نکته مهم درباره دامنه
این خروجی، Phase 1 قابل توسعه است؛ هنوز موتور حل شبکه الکتریکی، محاسبه ولتاژ/جریان، رفتار واقعی کنتاکتور، Realistic Mode، حفاظت‌ها، تایمرها، Star/Delta، Fault Detection و Export PDF در آن پیاده‌سازی نشده‌اند. دکمه Run عمداً شبیه‌سازی واقعی را ادعا نمی‌کند. این موارد باید در فازهای بعدی و با تست مهندسی اضافه شوند.

## ساختار
- `domain/model`: مدل پروژه، قطعه، ترمینال و سیم
- `editor`: وضعیت و عملیات ویرایش و مسیریابی اولیه
- `data`: سریال‌سازی JSON
- `ui`: صفحه اصلی و بوم Compose
