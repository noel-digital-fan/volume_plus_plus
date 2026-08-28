package com.volume_plus_plus.app.i18n

/**
 * Español — Spanish.
 *
 * Overrides only; anything left out falls back to the English text in [Strings]. `Volume++`,
 * `Shizuku` and `Android` are product names and stay as they are.
 */
object SpanishStrings : Strings() {

    // ── vocabulario común ───────────────────────────────────────────────────────────────────────

    override val cancel get() = "Cancelar"
    override val save get() = "Guardar"
    override val done get() = "Listo"
    override val notDone get() = "Sin completar"
    override val back get() = "Atrás"
    override val dismiss get() = "Descartar"
    override val selected get() = "Seleccionado"
    override val settings get() = "Ajustes"
    override val tryAgain get() = "Reintentar"
    override val install get() = "Instalar"
    override val grant get() = "Conceder"
    override val openSettings get() = "Abrir ajustes"
    override val off get() = "Apagado"

    // ── tema ────────────────────────────────────────────────────────────────────────────────────

    override val theme get() = "Tema"
    override val themeLight get() = "Claro"
    override val themeDark get() = "Oscuro"
    override val themeSystem get() = "Predeterminado del sistema"

    // ── idioma ──────────────────────────────────────────────────────────────────────────────────

    override val language get() = "Idioma"
    override val languageSystem get() = "Predeterminado del sistema"

    // ── navegación ──────────────────────────────────────────────────────────────────────────────

    override val tabVolume get() = "Volumen"
    override val tabMixing get() = "Mezcla"
    override val tabOverlay get() = "Panel"

    // ── pestaña Volumen ─────────────────────────────────────────────────────────────────────────

    override val volumeTitle get() = "Volumen"
    override val volumeSubtitle get() = "Controla cada canal de sonido"

    override val streamMedia get() = "Multimedia"
    override val streamCall get() = "Llamada"
    override val streamRing get() = "Timbre"
    override val streamNotification get() = "Notificaciones"
    override val streamAlarm get() = "Alarma"

    override val dndBlocking get() =
        "No molestar lo está bloqueando. Concede el acceso para cambiarlo."

    // ── pestaña Mezcla ──────────────────────────────────────────────────────────────────────────

    override val mixingTitle get() = "Mezcla de audio"
    override val mixingSubtitle get() = "Permite que dos o más apps suenen a la vez"
    override val mixingSearchApps get() = "Buscar apps"
    override val mixingHideSystemApps get() = "Ocultar apps del sistema"

    override fun mixingCouldntUpdate(app: String) = "No se pudo actualizar $app"

    override val mixingWarning get() =
        "Con la mezcla de audio activada, algunas apps pueden bloquearse, repetir anuncios o perder " +
            "sus controles de pausa o reanudación. Si una app se comporta mal, desactiva su " +
            "interruptor para volver a la normalidad."

    override val mixingDisabledTitle get() = "La mezcla de audio está desactivada"
    override val mixingDisabledBody get() =
        "Para usar la mezcla de audio, desactiva «Usar el control de volumen del sistema» en los " +
            "ajustes del panel."
    override val mixingGoToOverlaySettings get() = "Ir a los ajustes del panel"

    // ── lista de configuración ──────────────────────────────────────────────────────────────────

    override val setupIntroRooted get() =
        "La mezcla de audio necesita un asistente con privilegios. Este dispositivo parece tener " +
            "root, así que el modo root es la vía rápida; si no, completa los tres pasos de Shizuku."
    override val setupIntroShizuku get() =
        "La mezcla de audio funciona a través de Shizuku. Completa estos tres pasos y se desbloquea."

    override fun setupStep(number: Int, title: String) = "$number. $title"

    override val setupShizukuInstalled get() = "Shizuku instalado"
    override val setupShizukuNotInstalled get() = "Shizuku no está instalado"
    override val setupShizukuInstallDetail get() =
        "Ejecuta el asistente con privilegios que Volume++ necesita para cambiar el foco de audio."

    override val setupServiceRunning get() = "Servicio de Shizuku en marcha"
    override val setupServiceNotRunning get() = "El servicio de Shizuku no está en marcha"
    override val setupServiceStartDetail get() =
        "Inícialo desde Shizuku, mediante depuración inalámbrica o ADB."
    override val setupServerUnusableDetail get() =
        "Sigue en marcha un servicio de Shizuku sobrante de una instalación anterior, y por eso " +
            "Shizuku dice que no lo está. Deténlo y vuelve a iniciar Shizuku."
    override val setupRestartShizuku get() = "Reiniciar Shizuku"
    override val setupSetUpNow get() = "Configurar ahora"

    override val setupAccessGranted get() = "Acceso concedido a Volume++"
    override val setupGrantAccessTitle get() = "Concede acceso a Volume++"
    override val setupAccessDetail get() =
        "Permite que Volume++ controle el foco de audio de otras apps."
    override val setupConnectFailedDetail get() =
        "Shizuku no inició su servicio con privilegios."
    override val setupConnectingDetail get() = "Iniciando el servicio con privilegios de Shizuku…"
    override val setupGrantAccess get() = "Conceder acceso"

    // ── modo root ───────────────────────────────────────────────────────────────────────────────

    override val rootShortcutTitle get() = "¿Dispositivo con root?"
    override val rootShortcutBody get() =
        "Sáltate Shizuku por completo: concede root una vez y la mezcla se desbloquea."
    override val rootUse get() = "Usar modo root"
    override val rootRunning get() = "La mezcla de audio funciona con root."
    override val rootGranting get() = "Concediendo acceso root…"
    override val rootGrantingDetail get() = "Aprueba la solicitud de superusuario para terminar."
    override val rootRefused get() = "Acceso root denegado"
    override val rootRefusedDetail get() =
        "Tu gestor de superusuario rechazó la solicitud. Autoriza Volume++ ahí y vuelve a intentarlo."
    override val rootHelperFailed get() = "El asistente root no se inició"
    override val rootHelperFailedDetail get() =
        "Se concedió el root, pero el asistente con privilegios nunca respondió."
    override val rootUseShizukuInstead get() = "Usar Shizuku en su lugar"

    // ── pestaña Panel ───────────────────────────────────────────────────────────────────────────

    override val overlayTitle get() = "Panel"
    override val overlaySubtitle get() = "Sustituye el panel de volumen del sistema"
    override val overlayIntro get() =
        "Pulsa las teclas de volumen en cualquier momento para abrir el panel de Volume++ con un " +
            "control para cada app que esté sonando. Necesita los tres permisos de abajo. Los " +
            "controles por app también requieren Shizuku en marcha y Android 13 o superior."

    override val overlayStepDrawOver get() = "Superponer sobre otras apps"
    override val overlayStepAccessibility get() = "Activar el servicio de accesibilidad"
    override val overlayStepAccessibilityDetail get() =
        "Panel de Volume++: necesario para detectar las teclas de volumen."
    override val overlayStepDnd get() = "Permitir el acceso a No molestar"
    override val overlayStepDndDetail get() =
        "Necesario para cambiar el timbre a vibración o silencio desde el panel."

    override val overlaySystemPanelInUse get() =
        "Se está usando el panel de volumen de Android: el panel de Volume++ permanece desactivado."
    override val overlayReady get() = "Listo: pulsa una tecla de volumen para probarlo."
    override val overlayIncomplete get() = "Completa los tres pasos de arriba para activar el panel."

    override val overlayUseSystemPanel get() = "Usar el control de volumen del sistema"
    override val overlayUseSystemPanelDetail get() =
        "Deja las teclas de volumen al panel integrado de Android en lugar de al de Volume++."

    override val overlayStyle get() = "Estilo"
    override val overlayEdit get() = "Editar"

    override val overlaySettingsOpensApp get() = "El botón de ajustes abre Volume++"
    override val overlaySettingsOpensAppDetail get() =
        "El botón AJUSTES / VER MÁS del panel abre Volume++ en lugar de los ajustes de " +
            "sonido de Android."

    override val overlayMotion get() = "Movimiento"
    override val overlayMotionInfo get() =
        "Estos ajustes escalan la suavidad del panel mientras mantienes pulsada la tecla de volumen. " +
            "Deja ambos al 100 % para conservar el comportamiento actual, o ajústalos si quieres que " +
            "el panel reaccione más rápido o se detenga con más suavidad."
    override val overlayHoldFollowSpeed get() = "Velocidad de seguimiento"
    override val overlayHoldSettleSpeed get() = "Velocidad de asentamiento"

    override val overlayHaptics get() = "Vibración"
    override val overlayHapticsInfo get() =
        "Respuesta táctil opcional para los pasos de volumen repetidos. El control de intensidad " +
            "mantiene la sensación predeterminada, pero puedes hacerla más suave o más fuerte."
    override val overlayStepHaptics get() = "Vibración por paso al mantener"
    override val overlayStepHapticsDetail get() =
        "Un toque suave en cada paso de volumen repetido."
    override val overlayHapticIntensity get() = "Intensidad de la vibración"

    override val overlayPreview get() = "Previsualizar"
    override val overlayGrantToPreview get() = "Concede el permiso para previsualizar"

    // ── editor por estilo ───────────────────────────────────────────────────────────────────────

    override fun editStyleTitle(style: String) = "Editar $style"

    override fun editStyleIntro(style: String) =
        "Personaliza este estilo por separado. La posición y los colores se editan aparte, y solo " +
            "se modifica $style."

    override val editPosition get() = "Editar la posición"
    override val editPositionHint get() =
        "El panel real se abre sobre tu pantalla: arrástralo exactamente donde lo quieras y luego " +
            "guarda o cancela en la barra flotante."
    override val editColours get() = "Editar los colores"
    override val editColoursHint get() =
        "El panel se abre sobre tu pantalla: toca una parte del panel (o una muestra), ajusta el " +
            "color y luego guarda o cancela."

    override val editWhichLayout get() = "¿Qué orientación?"
    override val editWhichLayoutBody get() =
        "El retrato y el paisaje se posicionan por separado. La pantalla gira a la orientación que " +
            "elijas para que coloques el panel tal como aparecerá."

    override val orientationPortrait get() = "Retrato"
    override val orientationLandscape get() = "Paisaje"

    // ── editor en pantalla ──────────────────────────────────────────────────────────────────────

    override val liveEditPositionHint get() = "Arrastra el panel o escribe X / Y (dp)"
    override val liveEditResetPosition get() = "Restablecer la posición"
    override val liveEditColourHint get() =
        "Toca el panel o una muestra y luego ajusta o escribe un color"
    override val liveEditUseDefault get() = "Usar el predeterminado"
    override val liveEditPickFromScreen get() = "Elegir de la pantalla"

    override val liveEditComponentMain get() = "Principal"
    override val liveEditComponentExpanded get() = "Ampliado"
    override val liveEditComponentOutput get() = "Salida"

    override val panelComponentMain get() = "Panel principal"
    override val panelComponentExpanded get() = "Panel ampliado"
    override val panelComponentOutput get() = "Salida de audio"

    // ── muestras de color ───────────────────────────────────────────────────────────────────────

    override val colourBackground get() = "Fondo"
    override val colourProgress get() = "Control / progreso"
    override val colourTrack get() = "Guía del control"
    override val colourIcon get() = "Iconos"
    override val colourAccent get() = "Acento / botones"
    override val colourText get() = "Texto"
    override val colourSecondary get() = "Superficie secundaria"
    override val colourMediaIcon get() = "Icono de nota musical"
    override val colourModeIcon get() = "Icono del modo activo"
    override val colourOverflow get() = "Botón de tres puntos"
    override val colourDot get() = "Punto de volumen"
    override val colourOutputSurface get() = "Tarjeta de salida"
    override val colourDoneBg get() = "Botón Listo"
    override val colourDoneText get() = "Texto de Listo"
    override val colourTitle get() = "Título"

    override val colourOutputCard get() = "Fondo de la tarjeta"
    override val colourOutputSlider get() = "Relleno del control"
    override val colourOutputSliderTrack get() = "Guía del control"
    override val colourOutputIcon get() = "Icono"
    override val colourOutputText get() = "Texto"
    override val colourOutputDot get() = "Punto de volumen"
    override val colourOutputConnect get() = "Superficie de conexión"
    override val colourOutputDone get() = "Botón Listo"
    override val colourOutputDoneText get() = "Texto de Listo"

    // ── cuentagotas ─────────────────────────────────────────────────────────────────────────────

    override val eyedropperPick get() = "Elegir color"
    override val eyedropperUseColour get() = "Usar el color"

    override fun eyedropperDragToPick(hex: String) = "Arrastra para elegir  ·  $hex"

    override val eyedropperNeedsPermission get() =
        "Se necesita permiso de captura de pantalla para elegir un color"
    override val eyedropperCaptureFailed get() = "No se pudo capturar la pantalla"
    override val eyedropperBlocked get() =
        "Esa pantalla no permite capturas, así que no hay nada de donde elegir"

    override val eyedropperChannelName get() = "Selector de color"
    override val eyedropperChannelDescription get() =
        "Se muestra mientras el selector de color de pantalla espera tu elección."
    override val eyedropperNotificationTitle get() = "Eligiendo un color"
    override val eyedropperNotificationText get() =
        "Abre la app o la página de la que quieras el color y luego toca Elegir color."

    // ── el panel de volumen ─────────────────────────────────────────────────────────────────────

    override val panelTitleSoundVibration get() = "Sonido y vibración"
    override val panelTitleVolume get() = "Volumen"
    override val panelTitleSound get() = "Sonido"

    override val panelRowMedia get() = "Volumen multimedia"
    override val panelRowCall get() = "Volumen de llamada"
    override val panelRowRing get() = "Volumen del timbre"
    override val panelRowNotification get() = "Volumen de notificaciones"
    override val panelRowNotificationShort get() = "Notificaciones"
    override val panelRowRingNotification get() = "Volumen de timbre y notificaciones"
    override val panelRowAlarm get() = "Volumen de alarma"

    override val panelNotificationUnavailable get() =
        "No disponible porque el timbre está silenciado"

    override val panelSeeMore get() = "VER MÁS"
    override val panelDoneCaps get() = "LISTO"
    override val panelSettings get() = "Ajustes"
    override val panelDone get() = "Listo"

    override val panelAlarmsOnly get() = "Solo alarmas"
    override val panelAlarmsOnlyDetail get() = "Hasta que desactives No molestar"
    override val panelTurnOffNow get() = "DESACTIVAR AHORA"

    override val panelAudioWillPlayOn get() = "El audio se reproducirá en"
    override val panelConnectADevice get() = "Conectar un dispositivo"
    override val outputThisPhone get() = "Este teléfono"
    override val outputWiredHeadphones get() = "Auriculares con cable"
    override val outputUsbHeadphones get() = "Auriculares USB"
    override val outputHeadphones get() = "Auriculares"
}
