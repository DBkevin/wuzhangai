# 只读节点：默认不开启混淆（isMinifyEnabled=false）。
# 若后续开启 R8，必须保留无障碍服务入口类，否则系统无法绑定服务。
-keep class com.google.android.accessibility.selecttospeak.SelectToSpeakService { *; }
-keep class com.tencent.mm.assistant.WeChatReadService { *; }

# OkHttp 相关（仅在使用 R8 时需要）
-dontwarn okhttp3.**
-dontwarn okio.**
