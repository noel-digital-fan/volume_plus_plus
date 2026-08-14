package com.volume_plus_plus.app.i18n

/**
 * Português — Portuguese. Covers every `pt` variant, so the wording is kept to what reads naturally
 * in both Brazil and Portugal.
 *
 * Overrides only; anything left out falls back to the English text in [Strings]. `Volume++`,
 * `Shizuku` and `Android` are product names and stay as they are.
 */
object PortugueseStrings : Strings() {

    // ── vocabulário comum ───────────────────────────────────────────────────────────────────────

    override val cancel get() = "Cancelar"
    override val save get() = "Salvar"
    override val done get() = "Concluído"
    override val notDone get() = "Não concluído"
    override val back get() = "Voltar"
    override val dismiss get() = "Dispensar"
    override val selected get() = "Selecionado"
    override val settings get() = "Configurações"
    override val tryAgain get() = "Tentar novamente"
    override val install get() = "Instalar"
    override val grant get() = "Conceder"
    override val openSettings get() = "Abrir configurações"
    override val off get() = "Desligado"

    // ── tema ────────────────────────────────────────────────────────────────────────────────────

    override val theme get() = "Tema"
    override val themeLight get() = "Claro"
    override val themeDark get() = "Escuro"
    override val themeSystem get() = "Padrão do sistema"

    // ── idioma ──────────────────────────────────────────────────────────────────────────────────

    override val language get() = "Idioma"
    override val languageSystem get() = "Padrão do sistema"

    // ── navegação ───────────────────────────────────────────────────────────────────────────────

    override val tabVolume get() = "Volume"
    override val tabMixing get() = "Mixagem"
    override val tabOverlay get() = "Painel"

    // ── aba Volume ──────────────────────────────────────────────────────────────────────────────

    override val volumeTitle get() = "Volume"
    override val volumeSubtitle get() = "Controle cada canal de som"

    override val streamMedia get() = "Mídia"
    override val streamCall get() = "Chamada"
    override val streamRing get() = "Toque"
    override val streamNotification get() = "Notificações"
    override val streamAlarm get() = "Alarme"

    override val dndBlocking get() =
        "O modo Não perturbe está bloqueando isso. Conceda acesso para alterar."

    // ── aba Mixagem ─────────────────────────────────────────────────────────────────────────────

    override val mixingTitle get() = "Mixagem de áudio"
    override val mixingSubtitle get() = "Permita que dois ou mais apps toquem ao mesmo tempo"
    override val mixingSearchApps get() = "Pesquisar apps"
    override val mixingHideSystemApps get() = "Ocultar apps do sistema"

    override fun mixingCouldntUpdate(app: String) = "Não foi possível atualizar $app"

    override val mixingWarning get() =
        "Com a mixagem de áudio ativada, alguns apps podem travar, repetir anúncios ou perder os " +
            "controles de pausar e retomar. Se um app se comportar mal, desligue a chave dele para " +
            "voltar ao normal."

    override val mixingDisabledTitle get() = "A mixagem de áudio está desativada"
    override val mixingDisabledBody get() =
        "Para usar a mixagem de áudio, desative «Usar o controle de volume do sistema» nas " +
            "configurações do painel."
    override val mixingGoToOverlaySettings get() = "Ir às configurações do painel"

    // ── lista de configuração ───────────────────────────────────────────────────────────────────

    override val setupIntroRooted get() =
        "A mixagem de áudio precisa de um auxiliar privilegiado. Este dispositivo parece ter root, " +
            "então o modo root é o caminho rápido — ou conclua os três passos do Shizuku abaixo."
    override val setupIntroShizuku get() =
        "A mixagem de áudio funciona pelo Shizuku. Conclua estes três passos e ela é liberada."

    override fun setupStep(number: Int, title: String) = "$number. $title"

    override val setupShizukuInstalled get() = "Shizuku instalado"
    override val setupShizukuNotInstalled get() = "Shizuku não está instalado"
    override val setupShizukuInstallDetail get() =
        "Ele executa o auxiliar privilegiado de que o Volume++ precisa para mudar o foco de áudio."

    override val setupServiceRunning get() = "Serviço do Shizuku em execução"
    override val setupServiceNotRunning get() = "O serviço do Shizuku não está em execução"
    override val setupServiceStartDetail get() =
        "Inicie-o pelo Shizuku, por depuração sem fio ou ADB."
    override val setupServerUnusableDetail get() =
        "Um serviço do Shizuku remanescente de uma instalação antiga ainda está em execução, e é " +
            "por isso que o Shizuku diz que não está. Pare-o e inicie o Shizuku novamente."
    override val setupRestartShizuku get() = "Reiniciar o Shizuku"
    override val setupSetUpNow get() = "Configurar agora"

    override val setupAccessGranted get() = "Acesso concedido ao Volume++"
    override val setupGrantAccessTitle get() = "Conceda acesso ao Volume++"
    override val setupAccessDetail get() =
        "Permita que o Volume++ controle o foco de áudio de outros apps."
    override val setupConnectFailedDetail get() =
        "O Shizuku não iniciou seu serviço privilegiado."
    override val setupConnectingDetail get() = "Iniciando o serviço privilegiado do Shizuku…"
    override val setupGrantAccess get() = "Conceder acesso"

    // ── modo root ───────────────────────────────────────────────────────────────────────────────

    override val rootShortcutTitle get() = "Dispositivo com root?"
    override val rootShortcutBody get() =
        "Dispense o Shizuku por completo: conceda root uma vez e a mixagem é liberada."
    override val rootUse get() = "Usar modo root"
    override val rootRunning get() = "A mixagem de áudio está funcionando por root."
    override val rootGranting get() = "Concedendo acesso root…"
    override val rootGrantingDetail get() = "Aprove a solicitação de superusuário para concluir."
    override val rootRefused get() = "Acesso root recusado"
    override val rootRefusedDetail get() =
        "Seu gerenciador de superusuário recusou a solicitação. Autorize o Volume++ nele e tente " +
            "novamente."
    override val rootHelperFailed get() = "O auxiliar root não iniciou"
    override val rootHelperFailedDetail get() =
        "O root foi concedido, mas o auxiliar privilegiado nunca respondeu."
    override val rootUseShizukuInstead get() = "Usar o Shizuku em vez disso"

    // ── aba Painel ──────────────────────────────────────────────────────────────────────────────

    override val overlayTitle get() = "Painel"
    override val overlaySubtitle get() = "Substitua o painel de volume do sistema"
    override val overlayIntro get() =
        "Pressione as teclas de volume em qualquer lugar para abrir o painel do Volume++ com um " +
            "controle para cada app que estiver tocando. Precisa das três permissões abaixo. Os " +
            "controles por app também exigem o Shizuku em execução e o Android 13 ou superior."

    override val overlayStepDrawOver get() = "Sobrepor a outros apps"
    override val overlayStepAccessibility get() = "Ativar o serviço de acessibilidade"
    override val overlayStepAccessibilityDetail get() =
        "Painel do Volume++ — necessário para capturar as teclas de volume."
    override val overlayStepDnd get() = "Permitir acesso ao Não perturbe"
    override val overlayStepDndDetail get() =
        "Necessário para mudar o toque para vibrar ou silencioso pelo painel."

    override val overlaySystemPanelInUse get() =
        "O painel de volume do Android está em uso — o painel do Volume++ fica desativado."
    override val overlayReady get() = "Pronto — pressione uma tecla de volume para testar."
    override val overlayIncomplete get() =
        "Conclua os três passos acima para ativar o painel."

    override val overlayUseSystemPanel get() = "Usar o controle de volume do sistema"
    override val overlayUseSystemPanelDetail get() =
        "Deixe as teclas de volume para o painel nativo do Android em vez do painel do Volume++."

    override val overlayStyle get() = "Estilo"
    override val overlayEdit get() = "Editar"

    override val overlayMotion get() = "Movimento"
    override val overlayMotionInfo get() =
        "Estas configurações ajustam a suavidade do painel enquanto você mantém a tecla de volume " +
            "pressionada. Deixe ambas em 100 % para manter o comportamento atual, ou ajuste-as se " +
            "quiser que o painel acompanhe mais rápido ou pare com mais suavidade."
    override val overlayHoldFollowSpeed get() = "Velocidade de acompanhamento"
    override val overlayHoldSettleSpeed get() = "Velocidade de assentamento"

    override val overlayHaptics get() = "Vibração"
    override val overlayHapticsInfo get() =
        "Resposta tátil opcional para passos de volume repetidos. O controle de intensidade mantém " +
            "a sensação padrão, mas você pode deixá-la mais leve ou mais forte."
    override val overlayStepHaptics get() = "Vibração por passo ao manter pressionado"
    override val overlayStepHapticsDetail get() =
        "Um toque leve em cada passo de volume repetido."
    override val overlayHapticIntensity get() = "Intensidade da vibração"

    override val overlayPreview get() = "Visualizar"
    override val overlayGrantToPreview get() = "Conceda a permissão para visualizar"

    // ── editor por estilo ───────────────────────────────────────────────────────────────────────

    override fun editStyleTitle(style: String) = "Editar $style"

    override fun editStyleIntro(style: String) =
        "Personalize este estilo separadamente. A posição e as cores são editadas à parte, e apenas " +
            "$style é alterado."

    override val editPosition get() = "Editar a posição"
    override val editPositionHint get() =
        "O painel real abre sobre a sua tela — arraste-o exatamente para onde quiser e depois salve " +
            "ou cancele na barra flutuante."
    override val editColours get() = "Editar as cores"
    override val editColoursHint get() =
        "O painel abre sobre a sua tela — toque em uma parte dele (ou em uma amostra), ajuste a cor " +
            "e depois salve ou cancele."

    override val editWhichLayout get() = "Qual orientação?"
    override val editWhichLayoutBody get() =
        "Retrato e paisagem são posicionados separadamente. A tela gira para a orientação que você " +
            "escolher, para que posicione o painel exatamente como ele vai aparecer."

    override val orientationPortrait get() = "Retrato"
    override val orientationLandscape get() = "Paisagem"

    // ── editor na tela ──────────────────────────────────────────────────────────────────────────

    override val liveEditPositionHint get() = "Arraste o painel ou digite X / Y (dp)"
    override val liveEditResetPosition get() = "Redefinir a posição"
    override val liveEditColourHint get() =
        "Toque no painel ou em uma amostra e depois ajuste ou digite uma cor"
    override val liveEditUseDefault get() = "Usar o padrão"
    override val liveEditPickFromScreen get() = "Escolher da tela"

    override val liveEditComponentMain get() = "Principal"
    override val liveEditComponentExpanded get() = "Expandido"
    override val liveEditComponentOutput get() = "Saída"

    override val panelComponentMain get() = "Painel principal"
    override val panelComponentExpanded get() = "Painel expandido"
    override val panelComponentOutput get() = "Saída de áudio"

    // ── amostras de cor ─────────────────────────────────────────────────────────────────────────

    override val colourBackground get() = "Plano de fundo"
    override val colourProgress get() = "Controle / progresso"
    override val colourTrack get() = "Trilha do controle"
    override val colourIcon get() = "Ícones"
    override val colourAccent get() = "Destaque / botões"
    override val colourText get() = "Texto"
    override val colourSecondary get() = "Superfície secundária"
    override val colourMediaIcon get() = "Ícone de nota musical"
    override val colourModeIcon get() = "Ícone do modo ativo"
    override val colourOverflow get() = "Botão de três pontos"
    override val colourDot get() = "Ponto de volume"
    override val colourOutputSurface get() = "Cartão de saída"
    override val colourDoneBg get() = "Botão Concluído"
    override val colourDoneText get() = "Texto de Concluído"
    override val colourTitle get() = "Título"

    override val colourOutputCard get() = "Fundo do cartão"
    override val colourOutputSlider get() = "Preenchimento do controle"
    override val colourOutputSliderTrack get() = "Trilha do controle"
    override val colourOutputIcon get() = "Ícone"
    override val colourOutputText get() = "Texto"
    override val colourOutputDot get() = "Ponto de volume"
    override val colourOutputConnect get() = "Superfície de conexão"
    override val colourOutputDone get() = "Botão Concluído"
    override val colourOutputDoneText get() = "Texto de Concluído"

    // ── conta-gotas ─────────────────────────────────────────────────────────────────────────────

    override val eyedropperPick get() = "Escolher cor"
    override val eyedropperUseColour get() = "Usar a cor"

    override fun eyedropperDragToPick(hex: String) = "Arraste para escolher  ·  $hex"

    override val eyedropperNeedsPermission get() =
        "É necessária a permissão de captura de tela para escolher uma cor"
    override val eyedropperCaptureFailed get() = "Não foi possível capturar a tela"
    override val eyedropperBlocked get() =
        "Essa tela bloqueia capturas, então não há nada de onde escolher"

    override val eyedropperChannelName get() = "Seletor de cores"
    override val eyedropperChannelDescription get() =
        "Exibido enquanto o seletor de cores da tela espera a sua escolha."
    override val eyedropperNotificationTitle get() = "Escolhendo uma cor"
    override val eyedropperNotificationText get() =
        "Abra o app ou a página da qual quer a cor e depois toque em Escolher cor."

    // ── o painel de volume ──────────────────────────────────────────────────────────────────────

    override val panelTitleSoundVibration get() = "Som e vibração"
    override val panelTitleVolume get() = "Volume"
    override val panelTitleSound get() = "Som"

    override val panelRowMedia get() = "Volume da mídia"
    override val panelRowCall get() = "Volume da chamada"
    override val panelRowRing get() = "Volume do toque"
    override val panelRowNotification get() = "Volume das notificações"
    override val panelRowNotificationShort get() = "Notificações"
    override val panelRowRingNotification get() = "Volume do toque e das notificações"
    override val panelRowAlarm get() = "Volume do alarme"

    override val panelNotificationUnavailable get() =
        "Indisponível porque o toque está silenciado"

    override val panelSeeMore get() = "VER MAIS"
    override val panelDoneCaps get() = "CONCLUÍDO"
    override val panelSettings get() = "Configurações"
    override val panelDone get() = "Concluído"

    override val panelAlarmsOnly get() = "Somente alarmes"
    override val panelAlarmsOnlyDetail get() = "Até você desativar o Não perturbe"
    override val panelTurnOffNow get() = "DESATIVAR AGORA"

    override val panelAudioWillPlayOn get() = "O áudio será reproduzido em"
    override val panelConnectADevice get() = "Conectar um dispositivo"
    override val outputThisPhone get() = "Este telefone"
    override val outputWiredHeadphones get() = "Fones com fio"
    override val outputUsbHeadphones get() = "Fones USB"
    override val outputHeadphones get() = "Fones de ouvido"
}
