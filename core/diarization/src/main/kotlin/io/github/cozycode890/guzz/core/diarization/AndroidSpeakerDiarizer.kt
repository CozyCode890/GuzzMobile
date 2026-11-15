package io.github.cozycode890.guzz.core.diarization

import android.content.Context
import io.github.cozycode890.guzz.core.speaker.ChunkedDiarization
import io.github.cozycode890.guzz.core.speaker.DiarizationJson
import io.github.cozycode890.guzz.core.speaker.DiarizationSettings
import io.github.cozycode890.guzz.core.speaker.DiarizeRequest
import io.github.cozycode890.guzz.core.speaker.SpeakerDiarizer

/**
 * Bản Android của [SpeakerDiarizer]: chia phần và nối người nói ([ChunkedDiarization]), mỗi phần chạy sherpa-onnx trong
 * process `:diarize` ([RemotePieceEngine]). Huỷ coroutine thì process `:diarize` bị giết và hàm trả về ngay.
 */
class AndroidSpeakerDiarizer(
    private val context: Context,
    private val settings: DiarizationSettings = DiarizationSettings(),
    private val log: (String) -> Unit = {},
) : SpeakerDiarizer {
    override suspend fun diarize(request: DiarizeRequest, onProgress: (Double) -> Unit): DiarizationJson {
        RemotePieceEngine(context.applicationContext).use { engine ->
            val chunked = ChunkedDiarization(engine, settings, log)
            val result = chunked.diarize(request, onProgress)
            chunked.cleanPieces(request)
            return result
        }
    }
}
