// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.settings

import android.Manifest
import android.content.Context
import android.content.Intent
import android.database.ContentObserver
import android.os.Handler
import android.os.Looper
import android.provider.Settings as AndroidSettings
import android.widget.Toast
import android.view.inputmethod.InputMethodManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.core.content.edit
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import helium314.keyboard.latin.R
import helium314.keyboard.latin.permissions.PermissionsUtil
import helium314.keyboard.latin.settings.Defaults
import helium314.keyboard.latin.settings.Settings
import helium314.keyboard.latin.utils.JniUtils
import helium314.keyboard.latin.utils.Theme
import helium314.keyboard.latin.utils.UncachedInputMethodManagerUtils
import helium314.keyboard.latin.utils.prefs
import helium314.keyboard.latin.utils.previewDark
import helium314.keyboard.latin.voice.AiProvider
import helium314.keyboard.latin.voice.SecretStore
import helium314.keyboard.latin.voice.apiKeyPrefKey
import helium314.keyboard.latin.voice.defaultApiKey
import helium314.keyboard.latin.voice.defaultSttModel
import helium314.keyboard.latin.voice.parseExpectedLanguages
import helium314.keyboard.latin.voice.supportsSttSlug
import helium314.keyboard.latin.voice.supportsTextFixSlug
import helium314.keyboard.latin.voice.supportsVoiceSlug
import helium314.keyboard.settings.dialogs.ConfirmationDialog
import helium314.keyboard.settings.dialogs.ListPickerDialog
import helium314.keyboard.settings.dialogs.TextInputDialog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
fun WelcomeWizard(
    close: () -> Unit,
    finish: () -> Unit
) {
    val welcomeStep = 0
    val enableStep = 1
    val switchStep = 2
    val providerStep = 3
    val apiKeyStep = 4
    val languageStep = 5
    val voiceStep = 6
    val doneStep = 7
    val totalSetupSteps = 7
    val ctx = LocalContext.current
    val imm = ctx.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
    var aiSetupSkipped by rememberSaveable { mutableStateOf(false) }
    fun isAiProviderReady(): Boolean {
        val prefs = ctx.prefs()
        val provider = AiProvider.fromPref(prefs.getString(Settings.PREF_AI_PROVIDER, Defaults.PREF_AI_PROVIDER))
        return SecretStore.getApiKey(ctx, provider.apiKeyPrefKey(), provider.defaultApiKey()).isNotBlank()
                && prefs.getBoolean(Settings.PREF_VOICE_INPUT_ENABLED, Defaults.PREF_VOICE_INPUT_ENABLED)
                && PermissionsUtil.checkAllPermissionsGranted(ctx, Manifest.permission.RECORD_AUDIO)
    }
    fun determineStep(): Int = when {
        !UncachedInputMethodManagerUtils.isThisImeEnabled(ctx, imm) -> welcomeStep
        !UncachedInputMethodManagerUtils.isThisImeCurrent(ctx, imm) -> switchStep
        isAiProviderReady() || aiSetupSkipped -> doneStep
        else -> providerStep
    }

    /**
     * Re-evaluates the step after the user has been outside the app.
     *
     * The IME enable/switch gates genuinely need re-checking (the user changes those in system
     * settings), and once they're satisfied we push forward out of those steps. But past that point
     * the user is driving: recomputing from scratch collapsed the API-key, language and voice steps
     * back to the provider step every single resume, because [isAiProviderReady] isn't true until
     * the whole flow is finished. Leaving to a browser to fetch an API key and coming back is the
     * common case, and it used to lose your place.
     */
    fun refreshStep(current: Int): Int = when {
        !UncachedInputMethodManagerUtils.isThisImeEnabled(ctx, imm) -> welcomeStep
        !UncachedInputMethodManagerUtils.isThisImeCurrent(ctx, imm) -> switchStep
        current <= switchStep -> determineStep()
        else -> current
    }
    var step by rememberSaveable { mutableIntStateOf(determineStep()) }
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(ctx, imm, lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                step = refreshStep(step)
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
        }
    }
    DisposableEffect(ctx, imm) {
        val inputMethodObserver = object : ContentObserver(Handler(Looper.getMainLooper())) {
            override fun onChange(selfChange: Boolean) {
                step = refreshStep(step)
            }
        }
        ctx.contentResolver.registerContentObserver(
            AndroidSettings.Secure.getUriFor(AndroidSettings.Secure.DEFAULT_INPUT_METHOD),
            false,
            inputMethodObserver
        )
        onDispose {
            ctx.contentResolver.unregisterContentObserver(inputMethodObserver)
        }
    }
    LaunchedEffect(step) {
        if (step == switchStep) {
            repeat(20) {
                val nextStep = determineStep()
                if (nextStep != switchStep) {
                    step = nextStep
                    return@LaunchedEffect
                }
                delay(500)
            }
        }
    }
    val useWideLayout = isWideScreen()
    val backgroundColor = Color(ContextCompat.getColor(ctx, R.color.setup_background))
    val stepBackgroundColor = MaterialTheme.colorScheme.surface
    val actionContainerColor = Color(ContextCompat.getColor(ctx, R.color.setup_step_background))
    val primaryActionColor = MaterialTheme.colorScheme.primary
    val primaryActionContentColor = Color.White
    val infoContainerColor = MaterialTheme.colorScheme.primaryContainer
    val infoContentColor = MaterialTheme.colorScheme.onPrimaryContainer
    val textColor = Color(ContextCompat.getColor(ctx, R.color.setup_text_action))
    val textColorDim = textColor.copy(alpha = 0.55f)
    val titleColor = Color(ContextCompat.getColor(ctx, R.color.setup_text_title))
    val appName = stringResource(ctx.applicationInfo.labelRes)
    @Composable fun bigText() {
        val resource = if (step == welcomeStep) R.string.setup_welcome_title else R.string.setup_steps_title
        Column(Modifier.padding(bottom = 28.dp)) {
            Text(
                stringResource(resource, appName),
                style = MaterialTheme.typography.headlineLarge,
                textAlign = if (useWideLayout) TextAlign.Start else TextAlign.Center,
                color = titleColor,
            )
            if (JniUtils.sHaveGestureLib)
                Text(
                    stringResource(R.string.setup_welcome_additional_description),
                    style = MaterialTheme.typography.bodyLarge,
                    textAlign = if (useWideLayout) TextAlign.Start else TextAlign.Center,
                    color = titleColor,
                    modifier = Modifier.fillMaxWidth()
                )
        }
    }
    @Composable
    fun ProgressHeader(currentStep: Int) {
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            (1..totalSetupSteps).forEach {
                val isSelected = currentStep == it
                Surface(
                    shape = CircleShape,
                    color = if (isSelected) primaryActionColor else actionContainerColor.copy(alpha = 0.5f),
                    contentColor = if (isSelected) primaryActionContentColor else textColorDim,
                    modifier = Modifier.size(28.dp)
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Text(
                            it.toString(),
                            style = MaterialTheme.typography.labelMedium,
                            color = if (isSelected) primaryActionContentColor else textColorDim
                        )
                    }
                }
            }
        }
        Spacer(Modifier.height(14.dp))
        LinearProgressIndicator(
            progress = { currentStep / totalSetupSteps.toFloat() },
            modifier = Modifier.fillMaxWidth(),
            color = primaryActionColor,
            trackColor = actionContainerColor.copy(alpha = 0.35f)
        )
        Spacer(Modifier.height(20.dp))
    }
    @Composable
    fun PrimaryAction(actionText: String, icon: Painter, action: () -> Unit) {
        Button(
            onClick = action,
            shape = RoundedCornerShape(20.dp),
            colors = ButtonDefaults.buttonColors(
                containerColor = primaryActionColor,
                contentColor = primaryActionContentColor
            ),
            modifier = Modifier.fillMaxWidth()
        ) {
            Icon(
                icon,
                null,
                Modifier.padding(end = 8.dp).size(22.dp),
                tint = primaryActionContentColor
            )
            Text(
                actionText,
                Modifier.weight(1f),
                color = primaryActionContentColor,
                textAlign = TextAlign.Center
            )
        }
    }
    @Composable
    fun SecondaryAction(actionText: String, icon: Painter? = null, action: () -> Unit) {
        OutlinedButton(
            onClick = action,
            shape = RoundedCornerShape(20.dp),
            border = BorderStroke(1.dp, primaryActionColor.copy(alpha = 0.45f)),
            colors = ButtonDefaults.outlinedButtonColors(contentColor = primaryActionColor),
            modifier = Modifier.fillMaxWidth()
        ) {
            if (icon != null) Icon(icon, null, Modifier.padding(end = 8.dp).size(20.dp))
            Text(actionText, Modifier.weight(1f), textAlign = TextAlign.Center)
        }
    }
    @Composable
    fun WizardPage(
        currentStep: Int,
        title: String,
        instruction: String,
        icon: Painter,
        primaryText: String,
        primaryAction: () -> Unit,
        secondaryText: String? = null,
        secondaryAction: (() -> Unit)? = null,
    ) {
        Surface(
            shape = RoundedCornerShape(28.dp),
            color = stepBackgroundColor,
            tonalElevation = 2.dp,
            shadowElevation = 2.dp,
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(Modifier.padding(24.dp)) {
                ProgressHeader(currentStep)
                Surface(
                    shape = RoundedCornerShape(20.dp),
                    color = infoContainerColor,
                    contentColor = infoContentColor,
                    modifier = Modifier.size(56.dp)
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Icon(icon, null, Modifier.size(30.dp))
                    }
                }
                Spacer(Modifier.height(18.dp))
                Text(title, style = MaterialTheme.typography.headlineSmall.merge(color = titleColor))
                Spacer(Modifier.height(8.dp))
                Text(instruction, style = MaterialTheme.typography.bodyLarge.merge(color = textColor))
                Spacer(Modifier.height(24.dp))
                PrimaryAction(primaryText, icon, primaryAction)
                if (secondaryText != null && secondaryAction != null) {
                    Spacer(Modifier.height(10.dp))
                    SecondaryAction(secondaryText, null, secondaryAction)
                }
            }
        }
    }
    @Composable fun steps() {
        if (step == welcomeStep)
            Step0(
                actionContainerColor = actionContainerColor,
                primaryActionColor = primaryActionColor,
                primaryActionContentColor = primaryActionContentColor,
                onClick = { step = enableStep }
            )
        else
            Column {
                val launcher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) {
                    step = determineStep()
                }
                if (step == enableStep) {
                    WizardPage(
                        currentStep = step,
                        title = stringResource(R.string.setup_step1_title, appName),
                        instruction = stringResource(R.string.setup_step1_instruction, appName),
                        icon = painterResource(R.drawable.ic_setup_key),
                        primaryText = stringResource(R.string.setup_step1_action),
                        primaryAction = {
                        val intent = Intent()
                        intent.action = AndroidSettings.ACTION_INPUT_METHOD_SETTINGS
                        intent.addCategory(Intent.CATEGORY_DEFAULT)
                        launcher.launch(intent)
                        }
                    )
                } else if (step == switchStep) {
                    WizardPage(
                        currentStep = step,
                        title = stringResource(R.string.setup_step2_title, appName),
                        instruction = stringResource(R.string.setup_step2_instruction, appName),
                        icon = painterResource(R.drawable.ic_setup_select),
                        primaryText = stringResource(R.string.setup_step2_action),
                        primaryAction = imm::showInputMethodPicker,
                        secondaryText = stringResource(R.string.setup_continue_action),
                        secondaryAction = { step = determineStep() }
                    )
                } else if (step in providerStep..voiceStep) {
                    AiProviderSetupStep(
                        step = step,
                        voiceStep = voiceStep,
                        stepBackgroundColor = stepBackgroundColor,
                        infoContainerColor = infoContainerColor,
                        infoContentColor = infoContentColor,
                        textColor = textColor,
                        textColorDim = textColorDim,
                        titleColor = titleColor,
                        progressHeader = { ProgressHeader(it) },
                        primaryAction = { actionText, icon, action -> PrimaryAction(actionText, icon, action) },
                        secondaryAction = { actionText, icon, action -> SecondaryAction(actionText, icon, action) },
                        onProviderConfigured = { step = apiKeyStep },
                        onApiKeyConfigured = { step = languageStep },
                        onLanguageConfigured = { step = voiceStep },
                        onVoiceConfigured = { step = doneStep },
                        onSkip = {
                            if (step == voiceStep) aiSetupSkipped = true
                            step = when (step) {
                                providerStep -> apiKeyStep
                                apiKeyStep -> languageStep
                                languageStep -> voiceStep
                                else -> doneStep
                            }
                        },
                        onOpenVoiceSettings = {
                            close()
                            SettingsDestination.navigateTo(SettingsDestination.Voice)
                        },
                    )
                } else { // doneStep
                    WizardPage(
                        currentStep = step,
                        title = stringResource(R.string.setup_step3_title),
                        instruction = stringResource(R.string.setup_step3_instruction, appName),
                        icon = painterResource(R.drawable.ic_setup_check),
                        primaryText = stringResource(R.string.setup_finish_action),
                        primaryAction = finish,
                        secondaryText = stringResource(R.string.setup_step3_action),
                        secondaryAction = close
                    )
                }
            }
    }
    Surface(color = backgroundColor) {
        CompositionLocalProvider(
            LocalContentColor provides textColor,
            LocalTextStyle provides MaterialTheme.typography.titleLarge.merge(color = textColor),
        ) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .safeDrawingPadding()
                    .padding(24.dp),
                contentAlignment = Alignment.Center
            ) {
                if (useWideLayout)
                    Row(
                        Modifier
                            .verticalScroll(rememberScrollState())
                            .fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(32.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Box(Modifier.weight(0.4f)) {
                            bigText()
                        }
                        Box(Modifier.weight(0.6f)) {
                            steps()
                        }
                    }
                else
                    Column(Modifier.verticalScroll(rememberScrollState())) {
                        bigText()
                        steps()
                    }
            }
        }
    }
}

@Composable
private fun AiProviderSetupStep(
    step: Int,
    voiceStep: Int,
    stepBackgroundColor: Color,
    infoContainerColor: Color,
    infoContentColor: Color,
    textColor: Color,
    textColorDim: Color,
    titleColor: Color,
    progressHeader: @Composable (Int) -> Unit,
    primaryAction: @Composable (String, Painter, () -> Unit) -> Unit,
    secondaryAction: @Composable (String, Painter?, () -> Unit) -> Unit,
    onProviderConfigured: () -> Unit,
    onApiKeyConfigured: () -> Unit,
    onLanguageConfigured: () -> Unit,
    onVoiceConfigured: () -> Unit,
    onSkip: () -> Unit,
    onOpenVoiceSettings: () -> Unit,
) {
    val ctx = LocalContext.current
    val prefs = ctx.prefs()
    var showApiKeyDialog by rememberSaveable { mutableStateOf(false) }
    var showProviderDialog by rememberSaveable { mutableStateOf(false) }
    var showLanguageDialog by rememberSaveable { mutableStateOf(false) }
    var showMicRationale by rememberSaveable { mutableStateOf(false) }
    var providerPref by rememberSaveable {
        mutableStateOf(prefs.getString(Settings.PREF_AI_PROVIDER, Defaults.PREF_AI_PROVIDER) ?: Defaults.PREF_AI_PROVIDER)
    }
    val selectedProvider = AiProvider.fromPref(providerPref)
    var apiKeySet by remember {
        mutableStateOf(SecretStore.getApiKey(ctx, selectedProvider.apiKeyPrefKey(), selectedProvider.defaultApiKey()).isNotBlank())
    }
    var micGranted by remember {
        mutableStateOf(PermissionsUtil.checkAllPermissionsGranted(ctx, Manifest.permission.RECORD_AUDIO))
    }
    var voiceEnabled by remember {
        mutableStateOf(prefs.getBoolean(Settings.PREF_VOICE_INPUT_ENABLED, Defaults.PREF_VOICE_INPUT_ENABLED))
    }
    var expectedLanguagesRaw by rememberSaveable {
        mutableStateOf(
            prefs.getString(
                Settings.PREF_VOICE_EXPECTED_LANGUAGES,
                Defaults.PREF_VOICE_EXPECTED_LANGUAGES
            ) ?: Defaults.PREF_VOICE_EXPECTED_LANGUAGES
        )
    }
    val expectedLanguages = parseExpectedLanguages(expectedLanguagesRaw).joinToString(", ")
    val secureStorageMessage = stringResource(R.string.voice_error_secure_storage_unavailable)
    val permissionDeniedMessage = stringResource(R.string.voice_error_no_permission)
    fun providerName(provider: AiProvider): String = when (provider) {
        AiProvider.OPENROUTER -> ctx.getString(R.string.ai_provider_openrouter)
        AiProvider.PAYPERQ -> ctx.getString(R.string.ai_provider_payperq)
    }
    fun refreshApiKeyState(provider: AiProvider = selectedProvider) {
        apiKeySet = SecretStore.getApiKey(ctx, provider.apiKeyPrefKey(), provider.defaultApiKey()).isNotBlank()
    }
    fun selectProvider(provider: AiProvider) {
        providerPref = provider.prefValue
        // Only reset model selections the new provider cannot serve — same policy as VoiceScreen.
        // Unconditionally resetting here would wipe a deliberate model choice just because the
        // user re-ran setup (or re-picked the same provider).
        val currentVoice = prefs.getString(Settings.PREF_VOICE_MODEL, Defaults.PREF_VOICE_MODEL)
            ?: Defaults.PREF_VOICE_MODEL
        val currentStt = prefs.getString(Settings.PREF_VOICE_STT_MODEL, Defaults.PREF_VOICE_STT_MODEL)
            ?: Defaults.PREF_VOICE_STT_MODEL
        val currentTextFix = prefs.getString(Settings.PREF_TEXT_FIX_MODEL, Defaults.PREF_TEXT_FIX_MODEL)
            ?: Defaults.PREF_TEXT_FIX_MODEL
        val currentPolish = prefs.getString(Settings.PREF_VOICE_POLISH_MODEL, Defaults.PREF_VOICE_POLISH_MODEL)
            ?: Defaults.PREF_VOICE_POLISH_MODEL
        prefs.edit {
            putString(Settings.PREF_AI_PROVIDER, provider.prefValue)
            if (!provider.supportsVoiceSlug(currentVoice)) {
                putString(Settings.PREF_VOICE_MODEL, Defaults.PREF_VOICE_MODEL)
            }
            if (!provider.supportsSttSlug(currentStt)) {
                putString(Settings.PREF_VOICE_STT_MODEL, provider.defaultSttModel())
            }
            if (!provider.supportsTextFixSlug(currentTextFix)) {
                putString(Settings.PREF_TEXT_FIX_MODEL, Defaults.PREF_TEXT_FIX_MODEL)
            }
            if (!provider.supportsTextFixSlug(currentPolish)) {
                putString(Settings.PREF_VOICE_POLISH_MODEL, Defaults.PREF_VOICE_POLISH_MODEL)
            }
        }
        refreshApiKeyState(provider)
    }
    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        micGranted = granted
        if (granted) {
            prefs.edit { putBoolean(Settings.PREF_VOICE_INPUT_ENABLED, true) }
            voiceEnabled = true
            onVoiceConfigured()
        } else {
            Toast.makeText(ctx, permissionDeniedMessage, Toast.LENGTH_SHORT).show()
        }
    }

    if (showApiKeyDialog) {
        val scope = rememberCoroutineScope()
        TextInputDialog(
            onDismissRequest = { showApiKeyDialog = false },
            onConfirmed = {
                val key = it.trim()
                // Optimistic state; the encrypted-prefs write is a disk commit plus a KeyStore
                // round trip and must not run on the UI thread (see VoiceScreen.saveApiKey).
                apiKeySet = key.isNotBlank()
                scope.launch {
                    val failed = withContext(Dispatchers.IO) {
                        runCatching { SecretStore.setApiKey(ctx, selectedProvider.apiKeyPrefKey(), key) }.isFailure
                    }
                    if (failed) {
                        val stillSet = withContext(Dispatchers.IO) {
                            SecretStore.getApiKey(ctx, selectedProvider.apiKeyPrefKey(), selectedProvider.defaultApiKey()).isNotBlank()
                        }
                        Toast.makeText(ctx, R.string.voice_error_secure_storage_unavailable, Toast.LENGTH_SHORT).show()
                        apiKeySet = stillSet
                    }
                }
                showApiKeyDialog = false
                if (key.isNotBlank()) onApiKeyConfigured()
            },
            // Deliberately not prefilled with the stored key (see VoiceScreen): loading the secret
            // into an editable field exposes it to anyone who can open the setup flow on a
            // configured device. Confirm stays disabled while the field is empty, so an
            // untouched dialog can't wipe a working key.
            initialText = "",
            title = {
                Text(
                    if (selectedProvider == AiProvider.PAYPERQ) {
                        stringResource(R.string.payperq_api_key)
                    } else {
                        stringResource(R.string.openrouter_api_key)
                    }
                )
            },
            singleLine = true,
            isPassword = true,
        )
    }
    if (showProviderDialog) {
        ListPickerDialog(
            onDismissRequest = { showProviderDialog = false },
            items = listOf(AiProvider.OPENROUTER, AiProvider.PAYPERQ),
            onItemSelected = {
                selectProvider(it)
                showProviderDialog = false
            },
            selectedItem = selectedProvider,
            title = { Text(stringResource(R.string.ai_provider)) },
            getItemName = { providerName(it) },
        )
    }
    if (showLanguageDialog) {
        TextInputDialog(
            onDismissRequest = { showLanguageDialog = false },
            onConfirmed = { value ->
                val languages = parseExpectedLanguages(value).joinToString(", ")
                prefs.edit { putString(Settings.PREF_VOICE_EXPECTED_LANGUAGES, languages) }
                expectedLanguagesRaw = languages
                showLanguageDialog = false
                onLanguageConfigured()
            },
            initialText = expectedLanguagesRaw,
            title = { Text(stringResource(R.string.voice_expected_languages)) },
            description = { Text(stringResource(R.string.voice_expected_languages_summary)) },
            singleLine = false,
            checkTextValid = { true },
        )
    }
    if (showMicRationale) {
        ConfirmationDialog(
            onDismissRequest = { showMicRationale = false },
            onConfirmed = {
                showMicRationale = false
                permissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
            },
            title = { Text(stringResource(R.string.voice_mic_rationale_title)) },
            content = { Text(stringResource(R.string.voice_mic_rationale_message)) },
            confirmButtonText = stringResource(R.string.voice_mic_rationale_confirm),
        )
    }

    Surface(
        shape = RoundedCornerShape(28.dp),
        color = stepBackgroundColor,
        tonalElevation = 2.dp,
        shadowElevation = 2.dp,
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(Modifier.padding(24.dp)) {
            progressHeader(step)
            Surface(
                shape = RoundedCornerShape(20.dp),
                color = infoContainerColor,
                contentColor = infoContentColor,
                modifier = Modifier.size(56.dp)
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(
                        painterResource(
                            if (step == voiceStep) R.drawable.sym_keyboard_voice_rounded else R.drawable.ic_settings_preferences
                        ),
                        null,
                        Modifier.size(30.dp)
                    )
                }
            }
            Spacer(Modifier.height(18.dp))
            when (step) {
                3 -> {
                    Text(
                        stringResource(R.string.setup_ai_provider_choice_title),
                        style = MaterialTheme.typography.headlineSmall.merge(color = titleColor)
                    )
                    Spacer(Modifier.height(8.dp))
                    Text(
                        stringResource(R.string.setup_ai_provider_choice_instruction),
                        style = MaterialTheme.typography.bodyLarge.merge(color = textColor)
                    )
                    Spacer(Modifier.height(12.dp))
                    Text(
                        stringResource(R.string.setup_ai_provider_current_provider, providerName(selectedProvider)),
                        style = MaterialTheme.typography.bodyMedium.merge(color = textColorDim)
                    )
                    Spacer(Modifier.height(24.dp))
                    primaryAction(
                        stringResource(R.string.setup_ai_provider_select, providerName(selectedProvider)),
                        painterResource(R.drawable.ic_settings_preferences)
                    ) {
                        showProviderDialog = true
                    }
                    Spacer(Modifier.height(10.dp))
                    secondaryAction(
                        stringResource(R.string.setup_continue_action),
                        painterResource(R.drawable.ic_setup_check),
                        onProviderConfigured
                    )
                }
                4 -> {
                    Text(
                        stringResource(R.string.setup_ai_provider_key_title),
                        style = MaterialTheme.typography.headlineSmall.merge(color = titleColor)
                    )
                    Spacer(Modifier.height(8.dp))
                    Text(
                        stringResource(R.string.setup_ai_provider_key_instruction, providerName(selectedProvider)),
                        style = MaterialTheme.typography.bodyLarge.merge(color = textColor)
                    )
                    Spacer(Modifier.height(12.dp))
                    Text(
                        stringResource(
                            R.string.setup_ai_provider_key_status,
                            if (apiKeySet) stringResource(R.string.setup_status_done) else stringResource(R.string.setup_status_missing)
                        ),
                        style = MaterialTheme.typography.bodyMedium.merge(color = textColorDim)
                    )
                    Spacer(Modifier.height(24.dp))
                    primaryAction(
                        if (apiKeySet) {
                            stringResource(R.string.setup_ai_provider_update_key, providerName(selectedProvider))
                        } else {
                            stringResource(R.string.setup_ai_provider_add_key, providerName(selectedProvider))
                        },
                        painterResource(R.drawable.ic_settings_preferences)
                    ) {
                        if (!SecretStore.isSecureStorageAvailable(ctx)) {
                            Toast.makeText(ctx, secureStorageMessage, Toast.LENGTH_SHORT).show()
                        } else {
                            showApiKeyDialog = true
                        }
                    }
                    if (apiKeySet) {
                        Spacer(Modifier.height(10.dp))
                        secondaryAction(
                            stringResource(R.string.setup_continue_action),
                            painterResource(R.drawable.ic_setup_check),
                            onApiKeyConfigured
                        )
                    }
                }
                5 -> {
                    Text(
                        stringResource(R.string.setup_ai_provider_language_title),
                        style = MaterialTheme.typography.headlineSmall.merge(color = titleColor)
                    )
                    Spacer(Modifier.height(8.dp))
                    Text(
                        stringResource(R.string.setup_ai_provider_language_instruction),
                        style = MaterialTheme.typography.bodyLarge.merge(color = textColor)
                    )
                    Spacer(Modifier.height(12.dp))
                    Text(
                        if (expectedLanguages.isBlank()) {
                            stringResource(R.string.setup_ai_provider_language_not_set)
                        } else {
                            stringResource(R.string.setup_ai_provider_language_status, expectedLanguages)
                        },
                        style = MaterialTheme.typography.bodyMedium.merge(color = textColorDim)
                    )
                    Spacer(Modifier.height(24.dp))
                    primaryAction(
                        stringResource(R.string.setup_ai_provider_language_action),
                        painterResource(R.drawable.ic_settings_preferences)
                    ) {
                        showLanguageDialog = true
                    }
                    Spacer(Modifier.height(10.dp))
                    secondaryAction(
                        stringResource(R.string.setup_continue_action),
                        painterResource(R.drawable.ic_setup_check),
                        onLanguageConfigured
                    )
                }
                else -> {
                    Text(
                        stringResource(R.string.setup_ai_provider_voice_title),
                        style = MaterialTheme.typography.headlineSmall.merge(color = titleColor)
                    )
                    Spacer(Modifier.height(8.dp))
                    Text(
                        stringResource(R.string.setup_ai_provider_voice_instruction),
                        style = MaterialTheme.typography.bodyLarge.merge(color = textColor)
                    )
                    Spacer(Modifier.height(12.dp))
                    Text(
                        stringResource(
                            R.string.setup_ai_provider_voice_status,
                            if (micGranted) stringResource(R.string.setup_status_done) else stringResource(R.string.setup_status_missing),
                            if (voiceEnabled) stringResource(R.string.setup_status_done) else stringResource(R.string.setup_status_missing),
                        ),
                        style = MaterialTheme.typography.bodyMedium.merge(color = textColorDim)
                    )
                    Spacer(Modifier.height(24.dp))
                    primaryAction(
                        if (micGranted && voiceEnabled) stringResource(R.string.setup_ai_provider_voice_ready) else stringResource(R.string.setup_ai_provider_enable_voice),
                        painterResource(R.drawable.sym_keyboard_voice_rounded)
                    ) {
                        if (!SecretStore.isSecureStorageAvailable(ctx)) {
                            Toast.makeText(ctx, secureStorageMessage, Toast.LENGTH_SHORT).show()
                            return@primaryAction
                        }
                        if (micGranted) {
                            prefs.edit { putBoolean(Settings.PREF_VOICE_INPUT_ENABLED, true) }
                            voiceEnabled = true
                            onVoiceConfigured()
                        } else {
                            showMicRationale = true
                        }
                    }
                    Spacer(Modifier.height(10.dp))
                    secondaryAction(
                        stringResource(R.string.setup_ai_provider_voice_settings),
                        painterResource(R.drawable.ic_settings_default),
                        onOpenVoiceSettings
                    )
                }
            }
            Spacer(Modifier.height(4.dp))
            TextButton(
                onClick = onSkip,
                colors = ButtonDefaults.textButtonColors(contentColor = textColorDim),
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(stringResource(R.string.setup_ai_provider_skip))
            }
        }
    }
}

@Composable
fun Step0(
    actionContainerColor: Color,
    primaryActionColor: Color,
    primaryActionContentColor: Color,
    onClick: () -> Unit
) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Surface(
            shape = RoundedCornerShape(32.dp),
            color = actionContainerColor.copy(alpha = 0.35f),
            modifier = Modifier.fillMaxWidth()
        ) {
            Box(Modifier.padding(24.dp), contentAlignment = Alignment.Center) {
                Image(painterResource(R.drawable.setup_welcome_image), null)
            }
        }
        Spacer(Modifier.height(18.dp))
        Button(
            onClick = onClick,
            shape = RoundedCornerShape(20.dp),
            colors = ButtonDefaults.buttonColors(
                containerColor = primaryActionColor,
                contentColor = primaryActionContentColor
            ),
            modifier = Modifier.fillMaxWidth()
        ) {
            Text(
                stringResource(R.string.setup_start_action),
                color = primaryActionContentColor
            )
        }
    }
}

@Preview
@Composable
private fun Preview() {
    Theme(previewDark) {
        Surface {
            WelcomeWizard({}) {  }
        }
    }
}

@Preview(
    // content cut off on real device, but not here... great?
    device = "spec:orientation=landscape,width=400dp,height=780dp"
)
@Composable
private fun WidePreview() {
    Theme(previewDark) {
        Surface {
            WelcomeWizard({}) {  }
        }
    }
}
