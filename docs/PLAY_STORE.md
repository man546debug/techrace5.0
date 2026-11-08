# TechRace Flex ECU — distribuição 5.0.16

Pacote: `br.com.anderson.techrace`; versão 5.0.16; código 67; mínimo Android 7 (API 24); destino API 36.

## Arquivos

- APK release assinado: instalação direta, sem depuração habilitada.
- AAB release assinado: envio pelo Play Console. AAB não é instalado diretamente no celular.
- Backup de assinatura: chave privada, certificado público e senha. Guarde separadamente; não publique no GitHub nem distribua aos clientes.
- A automação do GitHub gera somente pacotes release sem assinatura; a assinatura final ocorre fora do repositório.

O certificado permanente é novo. Instalações antigas assinadas com chaves de debug diferentes podem exigir desinstalação antes da instalação, apagando os ajustes locais. Exporte os dados e registre os ajustes antes. As próximas versões distribuídas diretamente devem reutilizar esta chave.

## Play Console

1. Criar ou selecionar o aplicativo com este identificador de pacote.
2. Definir o Play App Signing. Para permitir atualizações entre APK direto e Play, as versões precisam compartilhar a mesma chave de assinatura do aplicativo. Se a Google gerar outra chave de aplicativo, o APK direto e o aplicativo da loja terão certificados diferentes.
3. Enviar o AAB assinado primeiro para teste interno e conferir instalação, conexão USB/Bluetooth, partida, menus e exportação em aparelhos reais.
4. Preencher contato de suporte, política de privacidade pública, segurança dos dados, classificação etária, anúncios e acesso ao app. Informar como testar o modo demonstração e que os recursos de comunicação exigem um módulo compatível.
5. Incluir nome, descrição, ícone, imagens e capturas de tela. Cumprir os requisitos de testes e verificação exibidos para a conta antes de solicitar produção.

## Base técnica para segurança dos dados e privacidade

O código desta versão não declara permissão INTERNET e não contém serviços de anúncios, análise de uso ou conta de usuário. A comunicação é local por USB OTG ou Bluetooth SPP. O histórico de telemetria fica em memória enquanto o processo está aberto; configurações e dados de abastecimento ficam nas preferências locais. O usuário escolhe onde salvar PDF/CSV/TXT pelo seletor do Android. O manifesto permite backup do Android, que depende das configurações do dispositivo/conta. Bluetooth pode exigir localização até Android 11 para busca de dispositivos; o app não usa esse acesso para rastrear a posição.

O responsável pela publicação deve revisar essas informações, informar nome e contato reais e disponibilizar uma política de privacidade própria antes de enviar à loja. A publicação não foi realizada nesta etapa.

Referências oficiais:

- https://support.google.com/googleplay/android-developer/answer/11926878
- https://developer.android.com/studio/publish/app-signing
- https://support.google.com/googleplay/android-developer/answer/10787469
