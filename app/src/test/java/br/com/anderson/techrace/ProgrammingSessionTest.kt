package br.com.anderson.techrace

import org.junit.Assert.*
import org.junit.Test

class ProgrammingSessionTest {
    @Test fun mapNeedsLoadOnlyWhenCentralSetsRuntimeBit() {
        val session = ProgrammingSession(map = true)
        assertEquals(ProgrammingSession.Stage.RUNNING, session.update(0x01))
        assertEquals(ProgrammingSession.Stage.MAP_LOAD, session.update(0x03))
        assertEquals(ProgrammingSession.Stage.MAP_LOAD, session.update(0x03))
        assertEquals(ProgrammingSession.Stage.COMPLETE, session.update(0x00))
    }

    @Test fun rpmUsesBitFortyRatherThanEepromRpmBit() {
        val session = ProgrammingSession(map = false)
        assertEquals(ProgrammingSession.Stage.WAITING_ACTIVE, session.update(0x02))
        assertEquals(ProgrammingSession.Stage.RUNNING, session.update(0x40))
        assertEquals(ProgrammingSession.Stage.COMPLETE, session.update(0x02))
    }

    @Test fun inactiveOrOldEepromFlagsCannotProduceImmediateSuccess() {
        val session = ProgrammingSession(map = true)
        repeat(5) { assertEquals(ProgrammingSession.Stage.WAITING_ACTIVE, session.update(0x00)) }
        assertFalse(session.observedActive)
        assertEquals(ProgrammingSession.Stage.WAITING_ACTIVE, session.update(0x02))
    }

    @Test fun newAttemptDoesNotReusePreviousCompletion() {
        val previous = ProgrammingSession(map = true)
        previous.update(0x03)
        assertEquals(ProgrammingSession.Stage.COMPLETE, previous.update(0))
        assertEquals(ProgrammingSession.Stage.WAITING_ACTIVE, ProgrammingSession(true).update(0))
    }

    @Test fun programmingStatusQueryUsesRuntimeAddress() {
        val request = TechRaceProtocol.READ_PROGRAMMING_STATUS
        assertEquals(0, request[1].toInt())
        assertEquals(0x63, request[3].toInt())
        assertEquals(2, request[4].toInt())
        assertEquals(0, TechRaceProtocol.crc8(request).toInt())
    }
}
