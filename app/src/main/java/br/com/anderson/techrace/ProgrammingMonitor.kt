package br.com.anderson.techrace

/** FlexProgram.cpp reads ten bytes at 0x48; MAP is +3 and lambda is +4. */
data class ProgrammingSensors(val mapVoltage: Double, val lambdaMv: Int) {
    companion object {
        fun decode(payload: ByteArray): ProgrammingSensors {
            require(payload.size == 10) { "Telemetria de programação incompleta" }
            return ProgrammingSensors((payload[3].toInt() and 255) * 2.5 / 255.0,
                (payload[4].toInt() and 255) * 5000 / 255)
        }
    }
}

data class ProgrammingObservation(
    val flags: Int?, val sensors: ProgrammingSensors?, val statusError: String?
)

/** Both queries run in sequence on the serial executor, never on the UI thread. */
object ProgrammingMonitor {
    fun read(exchange: (ByteArray, Int) -> ByteArray): ProgrammingObservation {
        val sensors = try {
            val reply = exchange(TechRaceProtocol.READ_PROGRAMMING_SENSORS, 1)
            ProgrammingSensors.decode(TechRaceProtocol.extractResponse(reply, 0, 10)
                ?: error("Telemetria de programação inválida"))
        } catch (_: Exception) { null }
        var statusError: String? = null
        val flags = try {
            val reply = exchange(TechRaceProtocol.READ_PROGRAMMING_STATUS, 2)
            val payload = TechRaceProtocol.extractResponse(reply, 0, 2)
                ?: error("Estado RAM 0x63 inválido")
            payload[0].toInt() and 255
        } catch (e: Exception) {
            statusError = e.message ?: "Falha de comunicação"
            null
        }
        return ProgrammingObservation(flags, sensors, statusError)
    }
}
