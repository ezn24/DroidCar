package io.github.shaonialife.cardeck.legal

import io.github.shaonialife.cardeck.R

/**
 * 免责声明正文：文案在 strings.xml（简中 / 繁中 / 英文，`disclaimer_s*`），这里只登记分节顺序。
 * 改任何一种语言的正文都要同步 [DisclaimerPrefs.CURRENT_VERSION] +1。
 *
 * 写作要点：
 * - **先讲范围与风险，再讲法律条款**——用户最需要知道的是「这东西测过什么、
 *   什么时候会出事」，把免责条款放最后更符合阅读习惯，也更容易真正被读到；
 * - 如实写清测试机型与已知限制（Android 14 以下没有「应用横屏」、行驶中断连的
 *   具体后果），空洞的免责声明没有意义；
 * - 明确写出依赖的系统权限（Shizuku shell、通知使用权），这是用户决定是否
 *   授权的前提；
 * - 明确非官方、无关联，避免让用户误认为与 Apple / 车厂有授权关系。
 *
 * 正文刻意**不用 Markdown 记号**（`**`、`#` 等）：这里直接进 TextView，
 * 那些符号会原样显示。分节标题由 UI 单独加粗。
 */
internal object DisclaimerContent {
    /** 分节：标题加粗显示，正文常规（字符串资源 id）。 */
    data class Section(
        val title: Int,
        val body: Int,
    )

    val sections: List<Section> =
        listOf(
            Section(R.string.disclaimer_s1_title, R.string.disclaimer_s1_body),
            Section(R.string.disclaimer_s2_title, R.string.disclaimer_s2_body),
            Section(R.string.disclaimer_s3_title, R.string.disclaimer_s3_body),
            Section(R.string.disclaimer_s4_title, R.string.disclaimer_s4_body),
            Section(R.string.disclaimer_s5_title, R.string.disclaimer_s5_body),
            Section(R.string.disclaimer_s6_title, R.string.disclaimer_s6_body),
        )
}
