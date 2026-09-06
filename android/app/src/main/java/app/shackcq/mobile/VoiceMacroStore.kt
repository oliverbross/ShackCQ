package app.shackcq.mobile

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import java.io.File
import java.io.FileOutputStream
import java.nio.file.Files
import java.nio.file.StandardCopyOption

class VoiceMacroStore(private val context: Context, private val labels: () -> List<String>) {
    private val directory = File(context.filesDir, "voice-macros")
    var slots by mutableStateOf(emptyList<VoiceMacroSlot>()); private set

    init {
        directory.mkdirs()
        directory.listFiles { file -> file.name.endsWith(".tmp") }?.forEach(File::delete)
        refresh()
    }

    fun refresh() {
        slots = List(VOICE_MACRO_COUNT) { index ->
            val label = sanitizeVoiceMacroLabel(labels().getOrNull(index).orEmpty(), index)
            runCatching {
                val pcm = read(index)
                VoiceMacroSlot(index, label, true, pcm.durationMillis, waveform = waveformPeaks(pcm.samples))
            }.getOrElse { VoiceMacroSlot(index, label, false) }
        }
    }

    fun read(index: Int): CanonicalVoicePcm {
        require(index in 0 until VOICE_MACRO_COUNT) { "Voice macro slot is out of range" }
        val parsed = parsePcmWave(file(index).readBytes())
        require(parsed.sampleRate == VOICE_SAMPLE_RATE && parsed.channels == 1) { "Voice macro is not in the canonical format" }
        require(parsed.samples.isNotEmpty() && parsed.samples.size <= VOICE_SAMPLE_RATE * VOICE_MAX_SECONDS) { "Voice macro duration is invalid" }
        return CanonicalVoicePcm(parsed.samples)
    }

    fun save(index: Int, pcm: CanonicalVoicePcm) {
        require(index in 0 until VOICE_MACRO_COUNT) { "Voice macro slot is out of range" }
        require(pcm.sampleRate == VOICE_SAMPLE_RATE && pcm.samples.isNotEmpty() &&
            pcm.samples.size <= VOICE_SAMPLE_RATE * VOICE_MAX_SECONDS) { "Voice macro audio is invalid" }
        directory.mkdirs()
        val temporary = File(directory, "slot-$index-${System.nanoTime()}.tmp")
        try {
            FileOutputStream(temporary).use { output ->
                output.write(writeCanonicalWave(pcm)); output.flush(); output.fd.sync()
            }
            Files.move(temporary.toPath(), file(index).toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
        } finally { temporary.delete() }
        refresh()
    }

    fun import(index: Int, bytes: ByteArray) = save(index, importVoiceWave(bytes))

    fun delete(index: Int) {
        require(index in 0 until VOICE_MACRO_COUNT) { "Voice macro slot is out of range" }
        file(index).delete(); refresh()
    }

    fun hasRecording(index: Int): Boolean = index in 0 until VOICE_MACRO_COUNT && file(index).isFile &&
        slots.getOrNull(index)?.exists == true

    fun path(index: Int): String = file(index).absolutePath

    private fun file(index: Int) = File(directory, "slot-$index.wav")
}
