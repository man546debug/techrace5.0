# Revisão da programação Sonda/RPM e MAP

Comparação estática com os arquivos fornecidos pelo usuário em 28/10/2026:
`Program.cpp`, `FlexProgram.cpp`, `Porta_serial.cpp`, `Principal.cpp`,
`Principal (1).cpp`, `RAM FLEXV10_0.xls`, `Comunicação PC.xls` e
`FlexExportacaov3_2.exe`. O executável não foi executado.
Os dois arquivos Principal têm conteúdo idêntico (SHA-256
`ccf6c70a236b62accf7548c773e1fec297d8f594a39a3aca9e2921fc3098b79c`).

## Comandos e estados confirmados

| Operação | Função | Endereço | Dados |
| --- | --- | --- | --- |
| Iniciar Sonda/RPM | 13 | 1 | 01 |
| Iniciar MAP | 13 | 1 | 02 |
| Encerrar | 13 | 1 | 00 |
| Ler sensores durante programação | 0 | 0x48 | 10 bytes |
| Consultar estado | 0 | 0x63 | 2 bytes |

O primeiro byte da resposta RAM 0x63 usa bit 0x40 para Sonda/RPM,
bit 0x01 para MAP ativo e bit 0x02 para solicitar carga durante MAP.
O segundo byte é RAM 0x64; não se usa como substituto desses bits.
A solicitação de carga é uma orientação ao operador: não envia outro início.

`FlexProgram.cpp::Timer1Timer` lê sensores antes do estado, em ambos os modos:
MAP = byte 3 * 2,5 / 255 V; sonda = byte 4 * 5000 / 255 mV, com divisão inteira.
Esses bytes correspondem a RAM 0x4B e 0x4C, como confirma a planilha de RAM.
A leitura geral do painel em 0x43 contém os mesmos canais nas posições 8 e 9;
a revisão anterior não usava canais diferentes, mas agora a calibração segue
a consulta específica do Windows. O painel geral continua usando 0x43.

`Porta_serial.cpp` monta F3, função, endereço high/low, quantidade, dados e
CRC-8 (polinômio 0x07, inicial zero). Até leituras enviam quantidade bytes de
dados no pedido. O APK mantém validação de função, comprimento e CRC na resposta.

## Interface original e comportamento revisado

Os recursos DFM do EXE contêm o formulário `TProgramFlexForm` e as mensagens:
- `Ligar Ar-Condicionado !!` (Caption no offset 0xA2297).
- `Colocar Transmissão Automática em Drive !!` (Caption no offset 0xA23B9).

O Timer1 está configurado para 500 ms. O C++ alterna a visibilidade dessas mensagens
quando a central mantém o bit 0x02 ativo. No APK, a orientação permanece legível,
acompanhada de MAP e sonda atualizados; a etapa inicial pede lenta sem carga.
O botão de reconhecimento da carga não transmite comando. A atualização é
agendada a cada 500 ms após as consultas, portanto atrasos seriais ampliam o intervalo.
A tela original também possui temperatura; esta revisão mantém o foco em MAP/sonda,
sem introduzir a fórmula antiga de temperatura da calibração no painel do APK.
A tela original de calibração não contém um mostrador de RPM numérico.

O APK mostra sensores nos dois modos, limpa valores sem amostra válida e consulta
estado mesmo quando a telemetria falha. As consultas usam a mesma fila serial.
O sucesso depende de ter observado o bit de calibração ativo e depois inativo.
O Windows simplesmente encerra quando encontra o bit inativo; o APK conserva
sua proteção contra falso sucesso antes de um início confirmado. Timeout e falha
de comunicação não são sucesso. Início não é repetido automaticamente se o ACK
se perde; consulta-se o estado. A leitura geral fica pausada durante a operação.

As descrições da EEPROM na planilha não coincidem integralmente com os indicadores
de `Principal.cpp` (por exemplo, bit 0x02). A revisão não reinterpreta flags de
configuração como flags de execução e não altera esses campos sem evidência do firmware.
Os fontes de firmware da central não foram fornecidos: condições internas de
estabilidade, limites e gravação automática não podem ser deduzidos integralmente
do aplicativo Windows. Não se inventam limiares de MAP, sonda ou RPM no APK.

## Validação

45 testes JVM passaram, executados com Kotlin 1.9.24 e JUnit 4.13.2 fora do Gradle
Android: ProtocolTest, ModuleQueryTest, ProgrammingSessionTest,
MapCalibrationDisplayTest e ProgrammingMonitorTest. Incluem quadros, CRC, offsets,
bytes sem sinal, transições e isolamento de falhas entre sensores e estado.
Não são testes de interface Android ou da central física.

A compilação Android e os testes Robolectric dependem de acesso a `jitpack.io`
para `usb-serial-for-android:3.8.1`. Sem essa liberação não se considera o APK
compilado nem a interface validada. A bancada deve confirmar ambos os modos,
passagem para carga, conclusão, cancelamento e desconexão em USB/Bluetooth.
