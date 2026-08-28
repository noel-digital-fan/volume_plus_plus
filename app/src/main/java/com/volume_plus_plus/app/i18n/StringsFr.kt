package com.volume_plus_plus.app.i18n

/**
 * Français — French.
 *
 * Overrides only; anything left out falls back to the English text in [Strings]. `Volume++`,
 * `Shizuku` and `Android` are product names and stay as they are.
 */
object FrenchStrings : Strings() {

    // ── vocabulaire commun ──────────────────────────────────────────────────────────────────────

    override val cancel get() = "Annuler"
    override val save get() = "Enregistrer"
    override val done get() = "Terminé"
    override val notDone get() = "Non terminé"
    override val back get() = "Retour"
    override val dismiss get() = "Ignorer"
    override val selected get() = "Sélectionné"
    override val settings get() = "Paramètres"
    override val tryAgain get() = "Réessayer"
    override val install get() = "Installer"
    override val grant get() = "Autoriser"
    override val openSettings get() = "Ouvrir les paramètres"
    override val off get() = "Désactivé"

    // ── thème ───────────────────────────────────────────────────────────────────────────────────

    override val theme get() = "Thème"
    override val themeLight get() = "Clair"
    override val themeDark get() = "Sombre"
    override val themeSystem get() = "Paramètre du système"

    // ── langue ──────────────────────────────────────────────────────────────────────────────────

    override val language get() = "Langue"
    override val languageSystem get() = "Paramètre du système"

    // ── navigation ──────────────────────────────────────────────────────────────────────────────

    override val tabVolume get() = "Volume"
    override val tabMixing get() = "Mixage"
    override val tabOverlay get() = "Panneau"

    // ── onglet Volume ───────────────────────────────────────────────────────────────────────────

    override val volumeTitle get() = "Volume"
    override val volumeSubtitle get() = "Réglez chaque canal audio"

    override val streamMedia get() = "Multimédia"
    override val streamCall get() = "Appel"
    override val streamRing get() = "Sonnerie"
    override val streamNotification get() = "Notifications"
    override val streamAlarm get() = "Alarme"

    override val dndBlocking get() =
        "Le mode Ne pas déranger bloque ce réglage. Autorisez l'accès pour le modifier."

    // ── onglet Mixage ───────────────────────────────────────────────────────────────────────────

    override val mixingTitle get() = "Mixage audio"
    override val mixingSubtitle get() = "Laissez deux applis ou plus jouer du son en même temps"
    override val mixingSearchApps get() = "Rechercher des applis"
    override val mixingHideSystemApps get() = "Masquer les applis système"

    override fun mixingCouldntUpdate(app: String) = "Impossible de mettre à jour $app"

    override val mixingWarning get() =
        "Avec le mixage audio activé, certaines applis peuvent se figer, rejouer des publicités ou " +
            "perdre leurs commandes de pause et de reprise. Si une appli se comporte mal, " +
            "désactivez son interrupteur pour revenir à la normale."

    override val mixingDisabledTitle get() = "Le mixage audio est désactivé"
    override val mixingDisabledBody get() =
        "Pour utiliser le mixage audio, désactivez « Utiliser le réglage du volume du système » dans " +
            "les paramètres du panneau."
    override val mixingGoToOverlaySettings get() = "Aller aux paramètres du panneau"

    // ── liste de configuration ──────────────────────────────────────────────────────────────────

    override val setupIntroRooted get() =
        "Le mixage audio nécessite un assistant privilégié. Cet appareil semble rooté : le mode " +
            "root est donc la voie rapide — sinon, effectuez les trois étapes Shizuku ci-dessous."
    override val setupIntroShizuku get() =
        "Le mixage audio passe par Shizuku. Effectuez ces trois étapes et il se débloque."

    override fun setupStep(number: Int, title: String) = "$number. $title"

    override val setupShizukuInstalled get() = "Shizuku installé"
    override val setupShizukuNotInstalled get() = "Shizuku n'est pas installé"
    override val setupShizukuInstallDetail get() =
        "Il exécute l'assistant privilégié dont Volume++ a besoin pour modifier le focus audio."

    override val setupServiceRunning get() = "Service Shizuku en cours d'exécution"
    override val setupServiceNotRunning get() = "Le service Shizuku n'est pas en cours d'exécution"
    override val setupServiceStartDetail get() =
        "Démarrez-le depuis Shizuku, via le débogage sans fil ou ADB."
    override val setupServerUnusableDetail get() =
        "Un service Shizuku résiduel d'une ancienne installation tourne encore, et c'est pourquoi " +
            "Shizuku affirme le contraire. Arrêtez-le puis relancez Shizuku."
    override val setupRestartShizuku get() = "Redémarrer Shizuku"
    override val setupSetUpNow get() = "Configurer maintenant"

    override val setupAccessGranted get() = "Accès accordé à Volume++"
    override val setupGrantAccessTitle get() = "Accordez l'accès à Volume++"
    override val setupAccessDetail get() =
        "Laissez Volume++ contrôler le focus audio des autres applis."
    override val setupConnectFailedDetail get() =
        "Shizuku n'a pas démarré son service privilégié."
    override val setupConnectingDetail get() = "Démarrage du service privilégié de Shizuku…"
    override val setupGrantAccess get() = "Accorder l'accès"

    // ── mode root ───────────────────────────────────────────────────────────────────────────────

    override val rootShortcutTitle get() = "Appareil rooté ?"
    override val rootShortcutBody get() =
        "Passez complètement Shizuku : accordez le root une fois et le mixage se débloque."
    override val rootUse get() = "Utiliser le mode root"
    override val rootRunning get() = "Le mixage audio fonctionne via le root."
    override val rootGranting get() = "Attribution de l'accès root…"
    override val rootGrantingDetail get() = "Approuvez la demande de superutilisateur pour terminer."
    override val rootRefused get() = "Accès root refusé"
    override val rootRefusedDetail get() =
        "Votre gestionnaire de superutilisateur a refusé la demande. Autorisez Volume++ dans " +
            "celui-ci, puis réessayez."
    override val rootHelperFailed get() = "L'assistant root n'a pas démarré"
    override val rootHelperFailedDetail get() =
        "Le root a été accordé, mais l'assistant privilégié n'a jamais répondu."
    override val rootUseShizukuInstead get() = "Utiliser Shizuku à la place"

    // ── onglet Panneau ──────────────────────────────────────────────────────────────────────────

    override val overlayTitle get() = "Panneau"
    override val overlaySubtitle get() = "Remplacez le panneau de volume du système"
    override val overlayIntro get() =
        "Appuyez sur les touches de volume n'importe où pour ouvrir le panneau de Volume++, avec un " +
            "curseur pour chaque appli en cours de lecture. Nécessite les trois autorisations " +
            "ci-dessous. Les curseurs par appli exigent en plus Shizuku actif et Android 13 ou " +
            "version ultérieure."

    override val overlayStepDrawOver get() = "Superposer aux autres applis"
    override val overlayStepAccessibility get() = "Activer le service d'accessibilité"
    override val overlayStepAccessibilityDetail get() =
        "Panneau Volume++ — nécessaire pour capter les touches de volume."
    override val overlayStepDnd get() = "Autoriser l'accès à Ne pas déranger"
    override val overlayStepDndDetail get() =
        "Nécessaire pour basculer la sonnerie en vibreur ou en silencieux depuis le panneau."

    override val overlaySystemPanelInUse get() =
        "Le panneau de volume d'Android est utilisé — celui de Volume++ reste désactivé."
    override val overlayReady get() = "Prêt — appuyez sur une touche de volume pour essayer."
    override val overlayIncomplete get() =
        "Effectuez les trois étapes ci-dessus pour activer le panneau."

    override val overlayUseSystemPanel get() = "Utiliser le réglage du volume du système"
    override val overlayUseSystemPanelDetail get() =
        "Laissez les touches de volume au panneau intégré d'Android plutôt qu'à celui de Volume++."

    override val overlayStyle get() = "Style"
    override val overlayEdit get() = "Modifier"

    override val overlaySettingsOpensApp get() = "Le bouton Paramètres ouvre Volume++"
    override val overlaySettingsOpensAppDetail get() =
        "Le bouton PARAMÈTRES / VOIR PLUS du panneau ouvre Volume++ au lieu des " +
            "paramètres de son d'Android."

    override val overlayMotion get() = "Mouvement"
    override val overlayMotionInfo get() =
        "Ces réglages ajustent la fluidité du panneau pendant que vous maintenez la touche de " +
            "volume. Laissez les deux à 100 % pour conserver le comportement actuel, ou modifiez-les " +
            "si vous voulez que le panneau suive plus vite ou s'immobilise plus doucement."
    override val overlayHoldFollowSpeed get() = "Vitesse de suivi"
    override val overlayHoldSettleSpeed get() = "Vitesse d'immobilisation"

    override val overlayHaptics get() = "Retour haptique"
    override val overlayHapticsInfo get() =
        "Retour tactile facultatif pour les pas de volume répétés. Le curseur d'intensité conserve " +
            "la sensation par défaut, mais vous pouvez la rendre plus légère ou plus forte."
    override val overlayStepHaptics get() = "Retour haptique à chaque pas"
    override val overlayStepHapticsDetail get() =
        "Une légère vibration à chaque pas de volume répété."
    override val overlayHapticIntensity get() = "Intensité du retour haptique"

    override val overlayPreview get() = "Aperçu"
    override val overlayGrantToPreview get() = "Autorisez la superposition pour l'aperçu"

    // ── éditeur par style ───────────────────────────────────────────────────────────────────────

    override fun editStyleTitle(style: String) = "Modifier $style"

    override fun editStyleIntro(style: String) =
        "Personnalisez ce style séparément. La position et les couleurs se modifient à part, et " +
            "seul $style est changé."

    override val editPosition get() = "Modifier la position"
    override val editPositionHint get() =
        "Le vrai panneau s'ouvre par-dessus votre écran — faites-le glisser exactement où vous le " +
            "voulez, puis enregistrez ou annulez depuis la barre flottante."
    override val editColours get() = "Modifier les couleurs"
    override val editColoursHint get() =
        "Le panneau s'ouvre par-dessus votre écran — touchez une de ses parties (ou un échantillon), " +
            "réglez la couleur, puis enregistrez ou annulez."

    override val editWhichLayout get() = "Quelle orientation ?"
    override val editWhichLayoutBody get() =
        "Le portrait et le paysage se positionnent séparément. L'écran pivote vers l'orientation " +
            "que vous choisissez, pour que vous placiez le panneau exactement tel qu'il apparaîtra."

    override val orientationPortrait get() = "Portrait"
    override val orientationLandscape get() = "Paysage"

    // ── éditeur à l'écran ───────────────────────────────────────────────────────────────────────

    override val liveEditPositionHint get() = "Faites glisser le panneau, ou saisissez X / Y (dp)"
    override val liveEditResetPosition get() = "Réinitialiser la position"
    override val liveEditColourHint get() =
        "Touchez le panneau ou un échantillon, puis réglez ou saisissez une couleur"
    override val liveEditUseDefault get() = "Valeur par défaut"
    override val liveEditPickFromScreen get() = "Prélever à l'écran"

    override val liveEditComponentMain get() = "Principal"
    override val liveEditComponentExpanded get() = "Étendu"
    override val liveEditComponentOutput get() = "Sortie"

    override val panelComponentMain get() = "Panneau principal"
    override val panelComponentExpanded get() = "Panneau étendu"
    override val panelComponentOutput get() = "Sortie audio"

    // ── échantillons de couleur ─────────────────────────────────────────────────────────────────

    override val colourBackground get() = "Arrière-plan"
    override val colourProgress get() = "Curseur / progression"
    override val colourTrack get() = "Rail du curseur"
    override val colourIcon get() = "Icônes"
    override val colourAccent get() = "Accent / boutons"
    override val colourText get() = "Texte"
    override val colourSecondary get() = "Surface secondaire"
    override val colourMediaIcon get() = "Icône de note de musique"
    override val colourModeIcon get() = "Icône du mode actif"
    override val colourOverflow get() = "Bouton à trois points"
    override val colourDot get() = "Point de volume"
    override val colourOutputSurface get() = "Carte de sortie"
    override val colourDoneBg get() = "Bouton Terminé"
    override val colourDoneText get() = "Texte de Terminé"
    override val colourTitle get() = "Titre"

    override val colourOutputCard get() = "Fond de la carte"
    override val colourOutputSlider get() = "Remplissage du curseur"
    override val colourOutputSliderTrack get() = "Rail du curseur"
    override val colourOutputIcon get() = "Icône"
    override val colourOutputText get() = "Texte"
    override val colourOutputDot get() = "Point de volume"
    override val colourOutputConnect get() = "Surface de connexion"
    override val colourOutputDone get() = "Bouton Terminé"
    override val colourOutputDoneText get() = "Texte de Terminé"

    // ── pipette ─────────────────────────────────────────────────────────────────────────────────

    override val eyedropperPick get() = "Prélever une couleur"
    override val eyedropperUseColour get() = "Utiliser la couleur"

    override fun eyedropperDragToPick(hex: String) = "Faites glisser pour prélever  ·  $hex"

    override val eyedropperNeedsPermission get() =
        "L'autorisation de capture d'écran est nécessaire pour prélever une couleur"
    override val eyedropperCaptureFailed get() = "Impossible de capturer l'écran"
    override val eyedropperBlocked get() =
        "Cet écran bloque les captures : il n'y a rien à prélever"

    override val eyedropperChannelName get() = "Pipette à couleurs"
    override val eyedropperChannelDescription get() =
        "Affiché pendant que la pipette attend que vous choisissiez une couleur."
    override val eyedropperNotificationTitle get() = "Prélèvement d'une couleur"
    override val eyedropperNotificationText get() =
        "Ouvrez l'appli ou la page dont vous voulez la couleur, puis touchez Prélever une couleur."

    // ── le panneau de volume ────────────────────────────────────────────────────────────────────

    override val panelTitleSoundVibration get() = "Son et vibration"
    override val panelTitleVolume get() = "Volume"
    override val panelTitleSound get() = "Son"

    override val panelRowMedia get() = "Volume multimédia"
    override val panelRowCall get() = "Volume des appels"
    override val panelRowRing get() = "Volume de la sonnerie"
    override val panelRowNotification get() = "Volume des notifications"
    override val panelRowNotificationShort get() = "Notifications"
    override val panelRowRingNotification get() = "Volume de la sonnerie et des notifications"
    override val panelRowAlarm get() = "Volume de l'alarme"

    override val panelNotificationUnavailable get() =
        "Indisponible car la sonnerie est coupée"

    override val panelSeeMore get() = "VOIR PLUS"
    override val panelDoneCaps get() = "TERMINÉ"
    override val panelSettings get() = "Paramètres"
    override val panelDone get() = "Terminé"

    override val panelAlarmsOnly get() = "Alarmes uniquement"
    override val panelAlarmsOnlyDetail get() = "Jusqu'à ce que vous désactiviez Ne pas déranger"
    override val panelTurnOffNow get() = "DÉSACTIVER MAINTENANT"

    override val panelAudioWillPlayOn get() = "L'audio sera lu sur"
    override val panelConnectADevice get() = "Connecter un appareil"
    override val outputThisPhone get() = "Ce téléphone"
    override val outputWiredHeadphones get() = "Écouteurs filaires"
    override val outputUsbHeadphones get() = "Écouteurs USB"
    override val outputHeadphones get() = "Écouteurs"
}
