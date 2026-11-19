# R8/ProGuard 规则：OkHttp/Okio 自带 consumer rules，这里只需兜底
-dontwarn okhttp3.**
-dontwarn okio.**
-dontwarn org.conscrypt.**
-dontwarn org.bouncycastle.**
-dontwarn org.openjsse.**
