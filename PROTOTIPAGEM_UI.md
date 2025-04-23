# Prototipagem da Interface do Usuário: USB2HDMI Android App

Este documento descreve a interface do usuário (UI) proposta para o aplicativo USB2HDMI, baseada nos requisitos e na modelagem UML. O objetivo é fornecer um guia textual para a implementação visual no Android Studio, seguindo as diretrizes do Material Design 3.

## 1. Tela Principal (MainActivity)

A tela principal será a única tela do aplicativo, apresentando todas as informações e controles necessários de forma concisa.

**Layout Geral:**

*   **AppBar (Barra Superior):**
    *   Título: "USB2HDMI Status" ou similar.
    *   (Opcional) Ícone de menu (três pontos) para acesso a Logs/Sobre.
*   **Corpo Principal (Verticalmente Organizado):**
    *   **Seção de Status:** Exibe o estado atual da conexão.
    *   **Seção de Informações da Tela Externa:** Visível apenas quando transmitindo.
    *   **Seção de Ação Principal:** Contém o botão para iniciar/parar.
    *   **Seção de Configuração (Opcional):** Para seleção de resolução, visível quando `Pronto para Transmitir` ou `Transmitindo`.
    *   **Seção de Mensagens/Erro:** Exibe feedback contextual.

**Componentes Detalhados:**

1.  **Indicador de Status da Conexão:**
    *   **Componente:** `TextView` proeminente e/ou um `ImageView` com ícone representativo.
    *   **Conteúdo:** Exibirá dinamicamente os textos definidos em RF005: "Desconectado", "Adaptador Conectado", "Pronto para Transmitir", "Transmitindo", "Erro".
    *   **Estilo:** Texto grande, cor pode mudar sutilmente com o status (ex: verde para transmitindo, vermelho para erro). Ícones do Material Symbols podem ser usados (ex: `link_off`, `usb`, `cast_connected`, `error`).

2.  **Informações da Tela Externa:**
    *   **Componente:** Um `CardView` ou `LinearLayout` contendo múltiplos `TextViews`.
    *   **Visibilidade:** Visível apenas quando `ConnectionStatus` for `TRANSMITTING`.
    *   **Conteúdo:**
        *   Label: "Resolução Externa:"
        *   Valor: (ex: "1920x1080") - Atualizado dinamicamente (RF009).
        *   Label: "Taxa de Atualização:"
        *   Valor: (ex: "60 Hz") - Atualizado dinamicamente (RF009).
    *   **Estilo:** Texto informativo, tamanho padrão.

3.  **Botão de Ação Principal:**
    *   **Componente:** `Button` (estilo `Filled` do Material 3).
    *   **Texto/Rótulo:** Muda dinamicamente:
        *   "Iniciar Transmissão" (quando status é `Pronto para Transmitir`).
        *   "Parar Transmissão" (quando status é `Transmitindo`).
    *   **Estado:**
        *   Habilitado: Apenas nos estados `Pronto para Transmitir` e `Transmitindo`.
        *   Desabilitado: Nos demais estados (`Desconectado`, `Adaptador Conectado`, `Erro`).
    *   **Ação:** Dispara as funções `startTransmissionRequest()` ou `stopTransmissionRequest()` no ViewModel (RF006, RF007).

4.  **Seleção de Resolução (Opcional/Condicional):**
    *   **Componente:** Um `Spinner` (Dropdown) ou `RadioGroup` dentro de um `CardView`.
    *   **Visibilidade:** Visível apenas quando `ConnectionStatus` for `Pronto para Transmitir` ou `Transmitindo` E houver mais de uma resolução suportada detectada.
    *   **Conteúdo:** Lista as resoluções disponíveis (obtidas do `ConnectionRepository`, RF010). Ex: "1920x1080 @ 60Hz", "1280x720 @ 60Hz".
    *   **Label:** "Selecionar Resolução:"
    *   **Ação:** Ao selecionar, chama `updateSelectedResolution()` no ViewModel. A mudança pode ter efeito imediato (se transmitindo) ou ser aplicada na próxima transmissão.

5.  **Área de Mensagens/Erro:**
    *   **Componente:** `TextView`.
    *   **Visibilidade:** Visível apenas quando houver uma mensagem de erro ou informativa relevante.
    *   **Conteúdo:** Exibe mensagens de erro retornadas pelo ViewModel (ex: "Falha ao obter permissão", "Monitor desconectado inesperadamente").
    *   **Estilo:** Cor de destaque para erros (vermelho).

## 2. Estados Visuais da Tela Principal

*   **Estado: Desconectado**
    *   Indicador de Status: "Desconectado" (ícone `link_off`).
    *   Informações Tela Externa: Oculto.
    *   Botão Ação: "Iniciar Transmissão" (Desabilitado).
    *   Seleção Resolução: Oculto.
    *   Mensagem: Nenhuma (ou "Conecte o adaptador USB-C").

*   **Estado: Adaptador Conectado**
    *   Indicador de Status: "Adaptador Conectado" (ícone `usb`).
    *   Informações Tela Externa: Oculto.
    *   Botão Ação: "Iniciar Transmissão" (Desabilitado).
    *   Seleção Resolução: Oculto.
    *   Mensagem: "Conecte um monitor HDMI ao adaptador".

*   **Estado: Pronto para Transmitir**
    *   Indicador de Status: "Pronto para Transmitir" (ícone `cast`).
    *   Informações Tela Externa: Oculto.
    *   Botão Ação: "Iniciar Transmissão" (Habilitado).
    *   Seleção Resolução: Visível (se múltiplas resoluções disponíveis).
    *   Mensagem: Nenhuma.

*   **Estado: Transmitindo**
    *   Indicador de Status: "Transmitindo" (ícone `cast_connected`, cor verde).
    *   Informações Tela Externa: Visível, exibindo resolução/taxa atuais.
    *   Botão Ação: "Parar Transmissão" (Habilitado).
    *   Seleção Resolução: Visível (se múltiplas resoluções disponíveis).
    *   Mensagem: Nenhuma.

*   **Estado: Erro**
    *   Indicador de Status: "Erro" (ícone `error`, cor vermelha).
    *   Informações Tela Externa: Oculto.
    *   Botão Ação: "Iniciar Transmissão" (Desabilitado).
    *   Seleção Resolução: Oculto.
    *   Mensagem: Exibe a mensagem de erro específica (ex: "Erro: Adaptador incompatível").

## 3. Diálogo de Permissão (MediaProjection)

*   **Acionamento:** Antes da primeira chamada a `startTransmissionRequest()` que requer a permissão.
*   **Aparência:** Diálogo padrão do sistema Android para `MediaProjection`.
*   **Texto:** Texto padrão do sistema, explicando que o aplicativo capturará o conteúdo da tela.
*   **Ações:** Botões "Cancelar" e "Iniciar agora".

## 4. Navegação (Opcional)

*   Se um menu for adicionado na AppBar:
    *   **Opção "Logs":** Navegaria para uma tela simples exibindo os logs registrados (RF012).
    *   **Opção "Sobre":** Exibiria informações básicas do aplicativo (versão, desenvolvedor).

---

Este protótipo textual fornece uma descrição clara da UI e suas variações de estado, servindo como um guia preciso para a implementação no Android Studio. A próxima etapa é a configuração do ambiente de desenvolvimento e o início da implementação do código base.