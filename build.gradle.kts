// 版本组合是按"互相兼容"选的，不是按"最新"选的：
//   AGP 8.13.2 是 AGP 8.x 末班车，最低要求 Gradle 8.13，最高支持 API 36.1
//   Kotlin 2.2.21 + KSP 2.2.21-2.0.5 是旧版号格式下的最后一对，两者严格绑定
//
// 关于 KSP：从 2.3.0 起它已**不再与 Kotlin 版本绑定**（改成了独立的 2.x.y 版本号），
// 所以将来把 Kotlin 升到 2.3+/2.4 时，只需换成对应的 2.3.x 新格式 KSP 即可，
// 不必再去找"和 Kotlin 严格对应"的那一对。真正不能做的是把 Kotlin 单方面升到
// 2.3+ 却继续用 2.2.21-2.0.5 这种旧格式 KSP —— 那才会对不上。
plugins {
    id("com.android.application") version "8.13.2" apply false
    id("org.jetbrains.kotlin.android") version "2.2.21" apply false
    id("com.google.devtools.ksp") version "2.2.21-2.0.5" apply false
}
