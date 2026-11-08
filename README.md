# TechRace Android V5.0.15 — USB OTG, Bluetooth e ajustes

Projeto Android em Kotlin baseado nos fontes originais fornecidos do software TechRace para Windows.
Versão 5.0.15, versionCode 66, pacote `br.com.anderson.techrace`.

## Mistura inicial abaixo de 10% na V5.0.15

Em Ajustes > Ajustar ECU > Partida à frio, a mistura inicial aceita de 0% até o limite máximo de correção.
A prévia e a confirmação informam o valor quantizado que será enviado: 2% vira 1,5625%; 3% vira 3,125%.
O passo permanece 1,5625 ponto percentual. O limite máximo continua entre 10% e 60%; a mistura manual não muda.
A gravação mantém ACK e releitura integral da EEPROM. A confirmação informa o valor armazenado;
aceitar a gravação não comprova que o firmware aplica essa correção durante a partida.
Valores abaixo de 10% ficam identificados como teste de compatibilidade.

## Acesso à exportação e identificação da V5.0.14

O botão **Exportar histórico PDF** fica fixo no topo de **Configurações**, fora da lista rolável,
com destaque vermelho. Permanece habilitado com zero amostras e mostra a quantidade de leituras.
Se não houver amostras, abre uma explicação sobre como iniciar o registro.
Configurações, Sobre, painel e PDF usam `BuildConfig.VERSION_NAME`.
O nome exibido pelo Android é gerado a partir do mesmo `versionName`, eliminando o antigo texto fixo V5.0.
Na V5.0.14, o código era **65**. A identificação acompanha a versão atual.

## Exportação PDF na V5.0.13

Em **Configurações > Exportar relatório PDF**, escolha **Resumo e gráficos** ou **Histórico completo**.
O resumo traz mínimo, média e máximo de correção (%), MAP (V), sonda (mV) e temperatura (°C),
além de gráficos pelo horário real das amostras. O relatório completo acrescenta a tabela cronológica
com data/hora e milissegundos, RPM, tempo de injeção, correção, MAP, sonda e temperatura.

- Usa o mesmo histórico do CSV: até as últimas 10.000 leituras válidas acumuladas enquanto o aplicativo está aberto. O histórico não persiste após encerrar o processo.
- O PDF captura as amostras disponíveis ao solicitar a exportação; novas leituras não alteram o arquivo em geração.
- Leituras reais e simulação são exportadas separadamente. PDFs de demonstração são identificados claramente.
- Temperatura ausente é exibida como `--`, sem convertê-la em zero ou incluí-la nas estatísticas. Lacunas acima de cinco segundos não são conectadas nos gráficos.
- Não converte MAP em pressão e não trata a correção como teor de etanol.
- Geração e gravação usam um executor separado da comunicação serial para não bloquear a interface.
- O Android pede a pasta e o nome do arquivo; PDF, CSV e TXT continuam disponíveis sem nova permissão de armazenamento.
- **Limpar histórico de leituras** limpa as amostras usadas tanto pelo CSV como pelo PDF.

## Tela ligada na V5.0.12

A tela permanece ligada enquanto o painel e os menus do TechRace estão visíveis,
evitando o desligamento automático durante a leitura ou programação. Ao sair do
aplicativo, o Android volta a controlar o tempo de tela normalmente. Isso não
mantém a conexão em segundo plano nem impede o bloqueio manual pelo botão físico.

## Indicadores de programação na V5.0.11

Após a central encerrar a calibração, o APK relê a EEPROM até quatro vezes,
com intervalo de 500 ms entre tentativas, para confirmar o indicador de MAP ou
Sonda/RPM. A telemetria só retoma após essa verificação. Respostas com CRC inválido,
timeout ou flags ainda antigos são consultadas novamente. O verde depende dos
bits persistidos lidos da central; o encerramento da calibração sozinho não o força.
Se o indicador continuar desativado, o APK informa a diferença. Se a EEPROM não
puder ser lida, os indicadores ficam neutros e o diagnóstico registra a falha.
Consultas de EEPROM ao conectar ou pelo menu também repetem falhas transitórias.

## Dashboard responsiva na V5.0.10

- A geometria usa toda a largura e altura disponíveis, sem centralizar uma tela de proporção fixa com faixas laterais.
- Cartões, gráfico, cabeçalho e navegação se reorganizam em retrato e paisagem; o conta-giros mantém escala uniforme e continua circular.
- Textos são medidos com a fonte real, ajustados à largura disponível e quebrados em até duas linhas nos cartões. Valores e minigráficos ocupam áreas separadas.
- As áreas de toque usam a mesma geometria do desenho e acompanham mudanças de resolução e rotação. A geometria é recalculada apenas no redimensionamento.
- Testes de geometria cobrem dez resoluções; renderização Android via Robolectric Native Graphics e toques são exercitados em 800×480, 1024×600, 1280×720, 1280×800, 1920×720, 768×1024 e 1080×2160, reutilizando a mesma View.
- As imagens de conferência são geradas em `app/build/dashboard-previews/`. A multimídia física de 9 polegadas ainda precisa ser conferida no aparelho.

## Identidade visual na V5.0.9

- Logo enviado pelo usuário no ícone do aplicativo, com versões para Android 7 e ícones adaptativos para Android 8 ou superior.
- Tela de entrada branca com logo completo, proporções preservadas e transição automática ao painel após 1,4 segundo.
- A tela de entrada cancela a navegação se o aplicativo passar ao segundo plano.

## Organização dos menus na V5.0.8

- **Ajustes:** Ajustar ECU e Registrar abastecimento.
- **Configurações:** conexão, demonstração, leitura, firmware, leitura e consulta de EEPROM, RAM experimental, fator RPM/intervalo e exportação/limpeza de CSV.
- As telas de programação e o painel foram preservados.

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
  - **Programar mistura manual** por barra de 0 a 100%, com digitação decimal;
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
- A calibração é acompanhada na **RAM 0x63, 2 bytes**, conforme `FlexProgram.cpp`:
  - Sonda/RPM: bit `0x40` ativo enquanto calibra;
  - MAP: bit `0x01` ativo enquanto calibra;
  - MAP etapa 2: com MAP ativo, bit `0x02` pede ligar carga;
  - sucesso exige observar o estado ativo e depois inativo; ausência de resposta ou limite de 90 s não é sucesso.
- O aviso de ligar ar-condicionado/carga surge automaticamente na segunda etapa. O botão “Carga ligada” apenas reconhece a orientação; não envia outro comando de início.
- A EEPROM continua sendo usada para ajustes persistidos, sem confundir suas flags com estados da RAM.
- Ações de programação aguardam a leitura serial em andamento. A telemetria fica pausada durante a calibração e retorna ao terminar.
- O seletor de início não é repetido cegamente quando o ACK se perde: o APK consulta a RAM antes de decidir se o início foi reconhecido. Se não for confirmado, informa a falha para permitir nova tentativa explícita.
- Leituras fragmentadas aguardam o quadro completo até o timeout; função, tamanho e CRC são verificados nas leituras de estado.
- Mistura: a barra usa passos visuais de 0,1 no nível relativo, mas **a central grava um byte inteiro**, com resolução de **1,5625 ponto percentual de correção**. Valores vizinhos podem gerar o mesmo byte. A confirmação usa o limite recém-lido da central e mostra o byte MIX e a correção efetiva; só então grava. Após gravar, relê MIX para conferir.
- Modo demonstração nunca transmite comandos.

## Validação da V5.0.14

A versão anterior à sincronização do nome do ícone passou por compilação e 69 testes.
O teste percorre Configurações > botão fixo de PDF > formato completo > seletor do Android > gravação binária.
Também verifica o botão habilitado sem dados e sua posição em um diálogo 800×480.
A publicação inclui uma verificação adicional do número da versão, nome do aplicativo e textos de Configurações/Sobre.

## Validação da V5.0.13

`testDebugUnitTest assembleDebug` concluído com sucesso: 68 testes, zero falhas/erros e nenhum ignorado.
Os testes cobrem mapeamento das unidades do CSV, ausência de temperatura, separação real/demo,
paginação até 10.000 amostras, ordenação e offsets do PDF, MIME do seletor e gravação binária integral.
O PDF de demonstração com 95 amostras foi aberto com um leitor independente e renderizado para
conferir resumo, gráficos e tabela em quatro páginas. Assinatura e versão do APK foram verificadas.

## Validação em bancada/veículo

Validação anterior da versão 5.0.10: `testDebugUnitTest assembleDebug` concluída com sucesso; 42 testes, zero falhas/erros, nenhum teste ignorado. Assinatura do APK verificada com `apksigner`; pacote `br.com.anderson.techrace`, versionCode 61.

Os testes automatizados cobrem estados do protocolo e quantização. Não houve teste com a central física; a sequência abaixo continua pendente de validação em bancada/veículo.

1. Com telemetria ativa, solicite Sonda/RPM uma vez; confira espera pela leitura atual, estado RAM `0x40` ativo e posterior encerramento.
2. Inicie MAP com motor em lenta estável e sem carga; confira estado `0x01`.
3. Quando o estado indicar `0x03`, confira o aviso automático para ligar carga. Ligue o ar-condicionado e mantenha a lenta estável.
4. Confira que sucesso só aparece após o bit ativo de MAP limpar. O encerramento manual também deve aguardar a consulta atual e verificar o estado da RAM.
5. Se houver timeout, confira que não aparece sucesso. Refaça a consulta ou encerre explicitamente.
6. Teste mistura decimal: confira correção efetiva na confirmação e releitura de MIX. Níveis relativos não são teor de etanol nem percentual direto de combustível adicional.
7. Desconecte/reconecte e teste USB OTG e Bluetooth SPP separadamente. Não interrompa alimentação durante gravação.

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
