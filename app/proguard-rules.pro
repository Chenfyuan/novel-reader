# 墨阅 R8 规则
-keepattributes SourceFile,LineNumberTable,Signature,*Annotation*,InnerClasses,EnclosingMethod
-renamesourcefileattribute SourceFile
# Room：实体 / DAO / 数据库生成类
-keep class * extends androidx.room.RoomDatabase { <init>(); }
-keep @androidx.room.Entity class * { *; }
-keep @androidx.room.Dao interface * { *; }
# 应用数据模型（备份 / 同步 JSON 字段按名称读写）
-keep class com.tavi.novelreader.data.** { *; }
# jsoup：可选依赖 re2j
-dontwarn com.google.re2j.**
-keep class org.jsoup.** { *; }
# media3 会话服务（Manifest 引用）
-keep class * extends androidx.media3.session.MediaSessionService
-dontwarn androidx.media3.**
# MaterialKolor / kotlinx
-dontwarn com.materialkolor.**
-dontwarn kotlinx.**
-dontwarn org.jetbrains.annotations.**
# pdfbox-android：可选的 JPEG2000 解码器；按名称加载的资源与类
-dontwarn com.gemalto.jp2.**
-dontwarn org.bouncycastle.**
-keep class com.tom_roush.pdfbox.** { *; }
-keep class com.tom_roush.fontbox.** { *; }
# Glance 小组件接收器（Manifest 引用）
-keep class * extends androidx.glance.appwidget.GlanceAppWidgetReceiver
