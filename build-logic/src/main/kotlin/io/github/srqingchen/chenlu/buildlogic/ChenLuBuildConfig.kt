package io.github.srqingchen.chenlu.buildlogic

object ChenLuBuildConfig {
    // 2026-09 的 androidx 一代（core 1.19 / lifecycle 2.11 / Compose 1.12）要求 compileSdk 37；
    // targetSdk 仍为 36（Play/国内商店基线）
    const val compileSdk = 37
    const val minSdk = 26
    const val targetSdk = 36
}
