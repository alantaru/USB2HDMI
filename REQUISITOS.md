# Requisitos do Projeto: USB2HDMI Android App

## 1. Introdução

Este documento detalha os Requisitos Funcionais (RF) e Não Funcionais (RNF) para o aplicativo Android USB2HDMI, conforme delineado no `MANIFESTO.md`. O objetivo é fornecer uma especificação clara e precisa para guiar o design, desenvolvimento e teste do aplicativo.

## 2. Requisitos Funcionais (RF)

Os Requisitos Funcionais descrevem o que o sistema deve fazer.

*   **RF001:** O aplicativo **DEVE** detectar automaticamente a conexão física do adaptador USB-C H'Maston ao dispositivo Motorola Edge 30.
*   **RF002:** O aplicativo **DEVE** detectar automaticamente a desconexão física do adaptador USB-C H'Maston do dispositivo.
*   **RF003:** O aplicativo **DEVE** detectar automaticamente a conexão de um monitor HDMI ao adaptador H'Maston quando este estiver conectado ao dispositivo.
*   **RF004:** O aplicativo **DEVE** detectar automaticamente a desconexão de um monitor HDMI do adaptador H'Maston.
*   **RF005:** O aplicativo **DEVE** exibir o status atual da conexão em tempo real na interface principal. Os status possíveis são:
    *   `Desconectado`: Nenhum adaptador detectado.
    *   `Adaptador Conectado`: Adaptador detectado, mas sem monitor HDMI.
    *   `Pronto para Transmitir`: Adaptador e monitor HDMI detectados.
    *   `Transmitindo`: Espelhamento/extensão de tela ativo.
    *   `Erro`: Falha na detecção ou na transmissão (com detalhes, se possível).
*   **RF006:** O aplicativo **DEVE** fornecer um controle (ex: botão ou switch) para iniciar manualmente a transmissão (espelhamento/extensão) para o monitor HDMI quando o status for `Pronto para Transmitir`.
*   **RF007:** O aplicativo **DEVE** fornecer um controle para parar manualmente a transmissão quando o status for `Transmitindo`.
*   **RF008:** O aplicativo **DEVE** solicitar a permissão `MediaProjection` ao usuário antes de iniciar a primeira transmissão.
*   **RF009:** O aplicativo **DEVE** exibir a resolução e a taxa de atualização atuais da tela externa quando a transmissão estiver ativa.
*   **RF010:** O aplicativo **DEVE** (se tecnicamente viável através das APIs do Android) permitir ao usuário selecionar uma resolução de saída suportada pelo monitor e pelo adaptador.
*   **RF011:** O aplicativo **DEVE** fornecer feedback visual claro ao usuário sobre o estado da operação (ex: indicadores de carregamento, mensagens de sucesso/erro).
*   **RF012:** O aplicativo **DEVE** registrar eventos básicos em um log interno para fins de diagnóstico. Eventos a serem registrados incluem:
    *   Conexão/Desconexão do Adaptador.
    *   Conexão/Desconexão do Monitor.
    *   Início/Fim da Transmissão.
    *   Seleção de Resolução (se aplicável).
    *   Erros encontrados.
*   **RF013:** O aplicativo **DEVE** persistir o estado da última transmissão (ativa/inativa) para tentar restabelecê-la automaticamente (ou oferecer a opção) na próxima vez que as condições forem atendidas (opcional, a confirmar viabilidade).

## 3. Requisitos Não Funcionais (RNF)

Os Requisitos Não Funcionais descrevem como o sistema deve operar, definindo suas qualidades.

*   **RNF001 (Usabilidade):** A interface do usuário **DEVE** ser simples, intuitiva e seguir as diretrizes de design do Material Design 3. As operações principais (conectar/desconectar) **DEVERÃO** ser acessíveis com o mínimo de interações.
*   **RNF002 (Performance):** A latência ponta-a-ponta (dispositivo para monitor) **DEVE** ser minimizada, idealmente abaixo de 100ms para uma experiência fluida.
*   **RNF003 (Performance):** O consumo de CPU e bateria pelo aplicativo durante a transmissão **DEVE** ser otimizado para não impactar significativamente a autonomia do dispositivo.
*   **RNF004 (Performance):** O tempo de inicialização do aplicativo **DEVE** ser rápido (idealmente < 2 segundos).
*   **RNF005 (Estabilidade):** O aplicativo **NÃO DEVE** travar ou fechar inesperadamente durante a operação normal.
*   **RNF006 (Estabilidade):** O aplicativo **DEVE** tratar adequadamente erros de conexão, desconexão abrupta ou falhas na API `MediaProjection`, informando o usuário de forma clara.
*   **RNF007 (Compatibilidade):** O aplicativo **DEVE** funcionar corretamente no Motorola Edge 30 com Android 14 e o adaptador USB-C para HDMI H'Maston especificado.
*   **RNF008 (Compatibilidade):** O aplicativo **DEVE** verificar e solicitar as permissões necessárias do Android (`FOREGROUND_SERVICE`, `SYSTEM_ALERT_WINDOW` se necessário para overlays, etc.) de forma clara.
*   **RNF009 (Segurança):** O aplicativo **NÃO DEVE** solicitar permissões além das estritamente necessárias para sua funcionalidade principal.
*   **RNF010 (Segurança):** O aplicativo **NÃO DEVE** coletar ou transmitir dados pessoais do usuário. O log de diagnóstico (RF012) **DEVE** ser armazenado localmente e não conter informações sensíveis.
*   **RNF011 (Manutenibilidade):** O código-fonte **DEVE** ser escrito em Kotlin, seguir as diretrizes de estilo oficiais do Kotlin e Android, e ser bem documentado.
*   **RNF012 (Manutenibilidade):** A arquitetura do aplicativo **DEVE** seguir o padrão MVVM ou MVI para garantir separação de responsabilidades e facilitar a manutenção e evolução.
*   **RNF013 (Testabilidade):** Componentes críticos do aplicativo (ViewModels, Repositories, etc.) **DEVEM** ser projetados para permitir testes unitários. Casos de uso principais **DEVEM** ser cobertos por testes de integração ou instrumentados.