package br.com.anderson.techrace

/** Runtime flags from FlexProgram.cpp, RAM 0x63 (not EEPROM option flags). */
class ProgrammingSession(val map: Boolean) {
    enum class Stage { WAITING_ACTIVE, RUNNING, MAP_LOAD, COMPLETE }
    var observedActive = false
        private set

    fun update(flags: Int): Stage {
        val active = flags and (if (map) 0x01 else 0x40) != 0
        if (active) observedActive = true
        return when {
            !active && observedActive -> Stage.COMPLETE
            !active -> Stage.WAITING_ACTIVE
            map && flags and 0x02 != 0 -> Stage.MAP_LOAD
            else -> Stage.RUNNING
        }
    }
}
