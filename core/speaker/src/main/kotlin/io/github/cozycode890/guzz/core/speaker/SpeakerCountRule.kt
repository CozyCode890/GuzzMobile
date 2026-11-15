package io.github.cozycode890.guzz.core.speaker

/**
 * Tách giọng một phần: [numClusters] `> 0` = số cụm cố định, `≤ 0` = phân cụm bằng ngưỡng. Bản Android chạy sherpa-onnx
 * trong process `:diarize` và dùng lại tệp `phan_NN.json` đã có.
 */
fun interface PieceRunner {
    suspend fun run(index: Int, numClusters: Int): Piece
}

/**
 * Số người nói mong muốn (D72, D80): [min]..[max], `0` = không giới hạn phía đó. Biết chính xác số người
 * (`num_speakers` > 0) thì `min = max = num_speakers`.
 */
data class SpeakerBounds(val min: Int, val max: Int) {
    companion object {
        /** Như cài đặt `[SPEAKERS]` của desktop: `num_speakers` > 0 thắng `min_speakers` / `max_speakers`. */
        fun of(numSpeakers: Int, minSpeakers: Int, maxSpeakers: Int): SpeakerBounds =
            if (numSpeakers > 0) SpeakerBounds(numSpeakers, numSpeakers) else SpeakerBounds(minSpeakers, maxSpeakers)
    }
}

/**
 * Luật min–max của D72 quanh việc tách giọng từng phần và nối người nói. sherpa-onnx không có kiểu min–max như
 * pyannote, chỉ có ngưỡng hoặc số cụm cố định:
 *  1. mọi phần phân cụm bằng ngưỡng; phần nào ra > max cụm thì phân cụm lại phần đó với số cụm = max;
 *  2. nối người nói; tổng < min người (và có người nói) thì phân cụm lại mọi phần với số cụm = min rồi nối lại;
 *  3. tổng > max người thì gộp người nói ít thời gian nhất vào người gần nhất ([PieceLinker.mergeToMax]).
 * Port của spikes/diarization/run_sherpa.py (nhánh chia phần, `--min-speakers` / `--max-speakers`).
 */
object SpeakerCountRule {
    class Outcome(val turns: List<LabeledTurn>, val link: PieceLinker.Result, val notes: List<String>)

    suspend fun run(pieceCount: Int, bounds: SpeakerBounds, threshold: Double, runner: PieceRunner): Outcome {
        val notes = ArrayList<String>()
        val pieces = MutableList(pieceCount) { runner.run(it, 0) }
        if (bounds.max > 0) {
            for (k in pieces.indices) {
                val n = pieces[k].labels.size
                if (n > bounds.max) {
                    notes += "phần ${k + 1}: $n cụm > tối đa ${bounds.max}, phân cụm lại"
                    pieces[k] = runner.run(k, bounds.max)
                }
            }
        }
        var link = PieceLinker.link(pieces, threshold)
        val linked = PieceLinker.speakerCount(link.turns)
        if (bounds.min > 0 && link.turns.isNotEmpty() && linked < bounds.min) {
            notes += "$linked người < tối thiểu ${bounds.min}, phân cụm lại mọi phần"
            for (k in pieces.indices) pieces[k] = runner.run(k, bounds.min)
            link = PieceLinker.link(pieces, threshold)
        }
        var turns = link.turns
        if (bounds.max > 0) {
            val before = PieceLinker.speakerCount(turns)
            turns = PieceLinker.mergeToMax(turns, link.globalEmb, bounds.max)
            if (before > bounds.max) notes += "$before người > tối đa ${bounds.max}, gộp người ít thời gian nhất"
        }
        return Outcome(turns, link, notes)
    }
}
