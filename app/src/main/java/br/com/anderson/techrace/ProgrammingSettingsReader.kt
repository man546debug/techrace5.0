package br.com.anderson.techrace

import java.util.concurrent.CancellationException

/** Refreshes persisted indicators; runtime completion alone never makes them green. */
object ProgrammingSettingsReader {
    data class Result(val settings: ModuleSettings?, val response: ByteArray, val error: String?)

    fun read(
        expectedMask: Int = 0,
        isCurrent: () -> Boolean,
        exchange: (ByteArray) -> ByteArray,
        pause: (Long) -> Unit = { Thread.sleep(it) }
    ): Result {
        require(expectedMask in 0..3)
        var latest = Result(null, byteArrayOf(), "EEPROM ainda não consultada")
        repeat(4) { attempt ->
            if (!isCurrent()) throw CancellationException()
            var response = byteArrayOf()
            latest = try {
                response = exchange(TechRaceProtocol.READ_SETTINGS)
                if (!isCurrent()) throw CancellationException()
                val data = TechRaceProtocol.extractResponse(response, 1, 25)
                    ?: error("Resposta de EEPROM inválida: tamanho, função ou CRC")
                val settings = ModuleSettings(data)
                val confirmed = (settings.raw[0].toInt() and expectedMask) == expectedMask
                Result(settings, response, if (confirmed) null else
                    "O indicador de programação ainda não foi confirmado na EEPROM da central")
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Result(null, response, e.message ?: "Falha ao ler a EEPROM")
            }
            if (latest.error == null) return latest
            if (attempt < 3) {
                if (!isCurrent()) throw CancellationException()
                pause(500L)
            }
        }
        return latest
    }
}
