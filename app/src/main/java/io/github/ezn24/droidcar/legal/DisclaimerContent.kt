package io.github.ezn24.droidcar.legal

import io.github.ezn24.droidcar.R

/**
 * Disclaimer body: the text lives in strings.xml (zh-Hans / zh-Hant / English, `disclaimer_s*`); only the section order is registered here.
 * Changing the body in any language requires bumping [DisclaimerPrefs.CURRENT_VERSION] by 1.
 *
 * Writing guidelines:
 * - **State scope and risks first, legal terms after**: what users most need to know is what was tested
 *   and when things can go wrong; putting the disclaimer clauses last matches reading habits and gets them actually read;
 * - State the tested device models and known limitations truthfully (no "app landscape" below Android 14, the
 *   concrete consequences of a disconnect while driving); an empty disclaimer is meaningless;
 * - State the system permissions relied on (Shizuku shell, notification access) explicitly; users need them to decide
 *   whether to grant;
 * - State clearly that it is unofficial and unaffiliated, so users do not assume authorization from Apple / car makers.
 *
 * The body deliberately **avoids Markdown markers** (`**`, `#` etc.): it goes straight into a TextView,
 * where such symbols would show literally. Section titles are bolded separately by the UI.
 */
internal object DisclaimerContent {
    /** Section: title shown bold, body normal (string resource ids). */
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
