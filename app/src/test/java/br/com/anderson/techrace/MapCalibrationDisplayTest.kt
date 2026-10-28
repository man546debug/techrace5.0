package br.com.anderson.techrace

import org.junit.Assert.*
import org.junit.Test

class MapCalibrationDisplayTest {
    @Test fun instructionsFollowTheCentralRatherThanElapsedTime() {
        val session = ProgrammingSession(true)
        assertTrue(MapCalibrationDisplay.instructions(session.update(1)).contains("sem carga"))
        val load = MapCalibrationDisplay.instructions(session.update(3))
        assertTrue(load.contains("ar-condicionado"))
        assertTrue(load.contains("selecione D"))
        assertTrue(load.contains("freio acionado"))
        assertTrue(MapCalibrationDisplay.instructions(session.update(0)).contains("confirmada pela central"))
    }

    @Test fun showsDecodedSensorValuesAndClearsThemOnMissingSample() {
        val payload = byteArrayOf(0, 0, 0, 0xFF.toByte(), 0xFF.toByte(), 0, 0, 0, 0, 0)
        val text = MapCalibrationDisplay.sensors(ProgrammingSensors.decode(payload))
        assertTrue(text.contains("5000 mV"))
        assertTrue(text.contains("Leitura real"))
        val missing = MapCalibrationDisplay.sensors(null)
        assertTrue(missing.contains("MAP: -- V"))
        assertTrue(missing.contains("Sonda: -- mV"))
        assertFalse(missing.contains("5000"))
    }
}
