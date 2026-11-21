package io.github.ezn24.cardeck.neo.replay

import org.json.JSONObject
import java.io.File

/**
 * Car-model replay fixture loader (JSONL). The directory is gitignored by default -- on a fresh clone with no fixtures
 * the tests skip automatically (see the directory scan in each Replay test); once extracted locally it becomes a regression gate.
 */
class CarFixture(
    val name: String,
    val car: String,
    val source: String,
) {
    class BtRound(
        val index: Int,
        val outcome: String,
        val durationMs: Long,
        val rx: ByteArray,
        val recordedTx: ByteArray,
        /** Trailing partial frames truncated by the RAW budget (already trimmed at extraction). */
        val rxTruncated: Boolean,
        /** The complete identification frame (0x1D01) survives -- only true allows full assertions on the identification phase. */
        val rxHasIdent: Boolean,
    )

    val btRounds = ArrayList<BtRound>()
    val tunnelRx = ArrayList<Pair<Int, ByteArray>>() // message ID -> CSM body
    val tunnelTxIds = ArrayList<Int>()

    companion object {
        fun loadDirectory(): List<CarFixture> {
            // src/test/resources is on the classpath during Gradle test runs; derive the source directory from the location
            // (dynamic fixtures are not packed into the jar, reading from the filesystem is the most direct)
            val roots =
                listOf(
                    File("src/test/resources/carplay/fixtures"),
                    File("app/src/test/resources/carplay/fixtures"),
                )
            val dir = roots.firstOrNull { it.isDirectory } ?: return emptyList()
            return dir
                .listFiles { f -> f.extension == "jsonl" }
                .orEmpty()
                .sortedBy { it.name }
                .mapNotNull { file ->
                    runCatching { parse(file) }.getOrElse {
                        println("REPLAY: fixture parse failed ${file.name}: $it")
                        null
                    }
                }
        }

        private fun parse(file: File): CarFixture {
            val lines = file.readLines().filter { it.isNotBlank() }
            val meta = JSONObject(lines.first())
            val fixture =
                CarFixture(
                    name = file.nameWithoutExtension,
                    car = meta.getJSONObject("meta").optString("car", "unknown"),
                    source = meta.getJSONObject("meta").optString("source", file.name),
                )
            lines.drop(1).forEach { line ->
                val obj = JSONObject(line)
                when (obj.optString("layer")) {
                    "bt" ->
                        fixture.btRounds.add(
                            BtRound(
                                index = obj.getInt("round"),
                                outcome = obj.optString("outcome", "identified"),
                                durationMs = obj.optLong("durationMs", 0),
                                rx = hex(obj.getString("rxHex")),
                                recordedTx = hex(obj.optString("txHex", "")),
                                rxTruncated = obj.optBoolean("rxTruncated", false),
                                rxHasIdent = obj.optBoolean("rxHasIdent", false),
                            ),
                        )

                    "tunnel" ->
                        if (obj.optString("dir") == "rx") {
                            fixture.tunnelRx.add(obj.getInt("id") to hex(obj.getString("hex")))
                        } else {
                            fixture.tunnelTxIds.add(obj.getInt("id"))
                        }
                }
            }
            return fixture
        }

        private fun hex(text: String): ByteArray =
            ByteArray(text.length / 2) { i ->
                text.substring(i * 2, i * 2 + 2).toInt(16).toByte()
            }
    }
}
