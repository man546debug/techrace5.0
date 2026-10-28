package br.com.anderson.techrace

import java.util.Locale

/** Only fresh, validated telemetry belongs on the calibration screen. */
object MapCalibrationDisplay {
    fun sensors(data: ProgrammingSensors?): String = if (data == null) {
        "MAP: -- V\nSonda: -- mV\nAguardando leitura válida dos sensores."
    } else {
        String.format(Locale.getDefault(),
            "MAP: %.2f V\nSonda: %d mV\nLeitura real da central", data.mapVoltage, data.lambdaMv)
    }

    fun instructions(stage: ProgrammingSession.Stage?): String = when (stage) {
        ProgrammingSession.Stage.MAP_LOAD ->
            "Etapa 2 — motor sob carga\nLigue o ar-condicionado ou, em carro automático, selecione D com o veículo imobilizado e o freio acionado. Mantenha a marcha lenta estável.\nAguarde: a central detecta a carga e conclui automaticamente; não é necessário iniciar novamente."
        ProgrammingSession.Stage.COMPLETE -> "Calibração MAP concluída e confirmada pela central."
        else -> "Etapa 1 — marcha lenta sem carga\nDeixe o motor em marcha lenta estável, com o ar-condicionado desligado e sem outras cargas. Aguarde a central solicitar a etapa de carga."
    }
}
