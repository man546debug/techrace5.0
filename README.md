# TechRace Android V5.0.1 — USB OTG, Bluetooth e ajustes

Projeto Android em Kotlin baseado nos fontes originais fornecidos do software TechRace para Windows.
Versão 5.0.1, versionCode 52, pacote `br.com.anderson.techrace`.

## Recursos da V5.0

### Layout responsivo
- Rotação automática entre retrato e paisagem.
- Painel dedicado para paisagem, sem esticar o conta-giros.
- Escala uniforme para celulares, tablets e modo de tela dividida.
- A sessão USB é preservada durante a rotação da tela.

- Mantém a leitura USB OTG em 19200 8N1 e acrescenta Bluetooth Classic SPP para leitores seriais pareados.
- O tipo de conexão é escolhido em **Configurações**; USB OTG continua disponível.
- Mantém monitor, gráficos, EEPROM, diagnóstico, modo demonstração e exportação CSV/TXT.
- A primeira opção de **Ajustes** abre diretamente os parâmetros da ECU: tempo de injeção e temperatura da partida a frio, mistura inicial, partida externa e limite pela lenta; aquecimento e injeção extra; correção e temperaturas; aceleração rápida; RPM alvo; lambda lenta, tempo e valor da sonda e opção WideBand.
- Nova aba **PROGRAMAÇÃO** com telas para:
  - **Programar Sonda / RPM**;
  - **Programar Sensor MAP**;
  - **Programar mistura manual** nos níveis de 0%, 25%, 50%, 75% e 100%;
  - **Encerrar / resetar modo de programação**;
- acompanhar o estado das operações.
- Programação implementada diretamente a partir de `Program.cpp`:
  - seletor `0` = encerrar/resetar;
  - seletor `1` = Sonda/RPM;
  - seletor `2` = MAP.
- O comando confirmado é `CMD 13`, endereço `1`, comprimento `1`.
- A mistura manual replica `Program.cpp`: lê o limite de correção da EEPROM `0x04`, calcula o nível escolhido e grava um byte em `MIX`, endereço EEPROM `0x03`, com `CMD 4`. O APK relê o endereço `0x03` e confirma que o valor foi gravado.
- Os níveis são relativos ao limite de correção configurado, como no programa Windows. Os extremos preservam os ajustes do EXE (0% usa o valor-base 1; 100% usa o limite menos 1). A operação não altera as flags automático/manual.
- Quadros calculados pelo mesmo CRC-8 do software original:
  - Sonda/RPM: `F3 0D 00 01 01 01 C9`;
  - MAP: `F3 0D 00 01 01 02 C0`;
  - encerrar/resetar: `F3 0D 00 01 01 00 CE`.
- Durante a programação, a leitura contínua é pausada para evitar concorrência na porta serial.
- O APK consulta a EEPROM e mostra as flags associadas pelo `Principal.cpp`:
  - bit `0x02` = Sonda/RPM;
  - bit `0x01` = MAP.
- O status deixa explícito que uma flag já ativa pode representar uma calibração anterior.
- Botão **Finalizar / encerrar** envia o seletor `0` antes de voltar à telemetria.
- Modo demonstração permite visualizar as telas, mas nunca transmite comandos.

## Limite conhecido
`Program.cpp` abre `FlexProgramForm` depois de iniciar Sonda/RPM ou MAP, mas o arquivo `FlexProgram.cpp` não foi fornecido. O APK preserva os comandos de início e encerramento presentes nos arquivos recebidos.

## Compilar no GitHub
1. Extraia este ZIP na raiz do repositório.
2. A raiz deve conter `app/`, `build.gradle.kts`, `settings.gradle.kts`, `gradle.properties` e `.github/workflows/build-apk.yml`.
3. Faça commit em `main` ou `master`, ou abra **Actions > Gerar APK TechRace 5.0 > Run workflow**.
4. O workflow executa testes unitários antes de gerar o APK.
5. Baixe o artefato **TechRace-V5.0-APK** e extraia `app-debug.apk`.

Requisitos do workflow: Java 17, Gradle 8.7, Android Gradle Plugin 8.5.2, Kotlin 1.9.24, compileSdk/targetSdk 34, minSdk 24 e `usb-serial-for-android:3.8.1`.

## Protocolo implementado
Estrutura transmitida conforme `Porta_serial.cpp`:

`F3 | CMD | ADDR_H | ADDR_L | LEN | DATA... | CRC`

CRC-8: polinômio `0x07`, valor inicial `0x00`, MSB first. O software original aceita uma resposta quando há bytes recebidos e o CRC residual do quadro recebido é zero. O APK reproduz essa regra nos comandos e mantém validação estrita de função/tamanho nas leituras conhecidas.

A EEPROM é lida com função `1`, endereço `1`, 25 bytes. Os ajustes editáveis gravam essa área com função `4`, preservam os campos não editados e conferem a gravação por releitura. A mistura manual grava somente o byte `MIX` no endereço `0x03`.

## Primeiro teste no carro/módulo
1. Teste primeiro o modo demonstração para conferir as novas telas.
2. Em modo real, conecte o módulo e confirme que a versão do firmware é lida.
3. Leia a EEPROM antes de programar e anote as flags MAP/Sonda-RPM.
4. Entre em **PROGRAMAÇÃO > Programar Sonda / RPM** e compare TX/RX com o software de PC.
5. Finalize usando **Finalizar / encerrar**.
6. Repita para **Sensor MAP**.
7. Se alguma etapa falhar, abra **DIAGNÓSTICO** e registre TX, RX e mensagem de erro.

Não desligue a alimentação nem remova o USB durante uma operação de programação. Como `FlexProgram.cpp` ainda não está disponível, valide a sequência de condições do motor comparando com o software original de PC antes de considerar a rotina totalmente reproduzida.
