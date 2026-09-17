package com.excp.podroid.ui.screens.setup

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.Settings
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts.RequestPermission
import androidx.compose.animation.core.animateDpAsState
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.windowsizeclass.WindowHeightSizeClass
import androidx.compose.material3.windowsizeclass.WindowSizeClass
import androidx.compose.runtime.Composable
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.excp.podroid.R
import com.excp.podroid.ui.components.AdaptiveContainer
import com.excp.podroid.ui.components.PodroidGhostButton
import com.excp.podroid.ui.components.PodroidListRow
import com.excp.podroid.ui.components.PodroidPrimaryButton
import com.excp.podroid.ui.components.PodroidSectionLabel
import com.excp.podroid.ui.components.PodroidSwitch
import com.excp.podroid.ui.components.VmBandwidthChips
import com.excp.podroid.ui.components.VmCpuChips
import com.excp.podroid.ui.components.VmRamChips
import com.excp.podroid.ui.components.VmStorageChips
import com.excp.podroid.ui.theme.PodroidTokens
import com.excp.podroid.util.DeviceResourcePolicy
import kotlinx.coroutines.launch

private const val DEFAULT_STORAGE_GB = 8

@Composable
fun SetupScreen(
    windowSizeClass: WindowSizeClass,
    onSetupComplete: () -> Unit,
    viewModel: SetupViewModel = hiltViewModel(),
) {
    val context = LocalContext.current
    // rememberSaveable preserves wizard choices through config changes (rotation, font-scale, etc.)
    // so a mid-wizard rotation doesn't silently reset storage to 8 GB (can be grown, never shrunk).
    var selectedGb by rememberSaveable { mutableIntStateOf(DEFAULT_STORAGE_GB) }
    var selectedRamMb by rememberSaveable { mutableIntStateOf(512) }
    var selectedCpus by rememberSaveable { mutableIntStateOf(2) }
    var selectedBandwidthMbps by rememberSaveable { mutableIntStateOf(0) }
    var loadBalanceEnabled by rememberSaveable { mutableStateOf(true) }
    var sshEnabled by rememberSaveable { mutableStateOf(true) }
    var storageAccessEnabled by rememberSaveable { mutableStateOf(false) }
    var hasStoragePermission by remember {
        mutableStateOf(
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                Environment.isExternalStorageManager()
            } else {
                ContextCompat.checkSelfPermission(
                    context, Manifest.permission.WRITE_EXTERNAL_STORAGE
                ) == PackageManager.PERMISSION_GRANTED
            }
        )
    }
    var usbPassthroughEnabled by rememberSaveable { mutableStateOf(false) }
    val usbPassthroughAvailable = remember { viewModel.usbPassthroughAvailable() }
    val setupComplete by viewModel.setupComplete.collectAsStateWithLifecycle()
    val pagerState = rememberPagerState(pageCount = { 4 })
    val scope = rememberCoroutineScope()

    fun applyLoadBalance() {
        val profile = DeviceResourcePolicy.balancedProfile(context)
        selectedRamMb = profile.ramMb
        selectedCpus = profile.cpus
        selectedGb = profile.storageGb
        selectedBandwidthMbps = profile.bandwidthMbps
    }

    LaunchedEffect(Unit) {
        if (loadBalanceEnabled) applyLoadBalance()
    }

    // Request the notification permission BEFORE navigating away; using
    // rememberLauncherForActivityResult registers it while the composable is still
    // alive, so the result actually arrives. ActivityCompat.requestPermissions
    // after onSetupComplete() fires against a transitioning Activity and is dropped
    // on some OEMs.
    val notifPermLauncher = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        rememberLauncherForActivityResult(RequestPermission()) { /* grant result: no-op */ }
    } else null

    LaunchedEffect(setupComplete) {
        if (setupComplete) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                if (ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS)
                    != PackageManager.PERMISSION_GRANTED
                ) {
                    notifPermLauncher?.launch(Manifest.permission.POST_NOTIFICATIONS)
                }
            }
            onSetupComplete()
        }
    }

    // System back steps the wizard back one page instead of exiting the app
    // (setup is the start destination, so an unhandled back would finish the
    // Activity mid-wizard). Disabled on page 0 so back there behaves normally.
    BackHandler(enabled = pagerState.currentPage > 0) {
        scope.launch { pagerState.animateScrollToPage(pagerState.currentPage - 1) }
    }

    // Re-sync storageAccessEnabled and hasStoragePermission against the actual OS
    // grant on every resume (the user may have granted or denied the
    // all-files-access screen we sent them to).
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME &&
                Build.VERSION.SDK_INT >= Build.VERSION_CODES.R
            ) {
                storageAccessEnabled = storageAccessEnabled && Environment.isExternalStorageManager()
                hasStoragePermission = Environment.isExternalStorageManager()
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    Scaffold { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding),
        ) {
            // Step progress bar
            LinearProgressIndicator(
                progress = { (pagerState.currentPage + 1) / 4f },
                modifier = Modifier.fillMaxWidth(),
                trackColor = MaterialTheme.colorScheme.surfaceVariant,
            )

            // Pages — swipe disabled; navigation is button-only
            HorizontalPager(
                state = pagerState,
                modifier = Modifier.weight(1f),
                userScrollEnabled = false,
            ) { page ->
                when (page) {
                    0 -> StoragePage(
                        windowSizeClass = windowSizeClass,
                        selectedGb = selectedGb,
                        loadBalanceEnabled = loadBalanceEnabled,
                        onSelect = { selectedGb = it },
                        onNext = { scope.launch { pagerState.animateScrollToPage(1) } },
                    )
                    1 -> VmConfigPage(
                        windowSizeClass = windowSizeClass,
                        storageGb = selectedGb,
                        ramMb = selectedRamMb,
                        cpus = selectedCpus,
                        bandwidthMbps = selectedBandwidthMbps,
                        loadBalanceEnabled = loadBalanceEnabled,
                        onLoadBalanceToggle = { enabled ->
                            loadBalanceEnabled = enabled
                            if (enabled) applyLoadBalance()
                        },
                        onRamChange = { selectedRamMb = it },
                        onCpusChange = { selectedCpus = it },
                        onBandwidthChange = { selectedBandwidthMbps = it },
                        sshEnabled = sshEnabled,
                        onSshToggle = { sshEnabled = it },
                        onBack = { scope.launch { pagerState.animateScrollToPage(0) } },
                        onNext = { scope.launch { pagerState.animateScrollToPage(2) } },
                    )
                    2 -> StorageAccessPage(
                        windowSizeClass = windowSizeClass,
                        storageAccessEnabled = storageAccessEnabled,
                        hasStoragePermission = hasStoragePermission,
                        onStoragePermissionChanged = { hasStoragePermission = it },
                        onStorageAccessToggle = { enabled ->
                            storageAccessEnabled = enabled
                            if (enabled &&
                                Build.VERSION.SDK_INT >= Build.VERSION_CODES.R &&
                                !Environment.isExternalStorageManager()
                            ) {
                                context.startActivity(
                                    Intent(
                                        Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
                                        Uri.parse("package:${context.packageName}"),
                                    )
                                )
                            }
                        },
                        onOpenStorageAccessSettings = {
                            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                                context.startActivity(
                                    Intent(
                                        Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
                                        Uri.parse("package:${context.packageName}"),
                                    )
                                )
                            }
                        },
                        onBack = { scope.launch { pagerState.animateScrollToPage(1) } },
                        onNext = { scope.launch { pagerState.animateScrollToPage(3) } },
                    )
                    3 -> UsbPassthroughPage(
                        windowSizeClass = windowSizeClass,
                        usbPassthroughEnabled = usbPassthroughEnabled,
                        available = usbPassthroughAvailable,
                        onUsbPassthroughToggle = { usbPassthroughEnabled = it },
                        onBack = { scope.launch { pagerState.animateScrollToPage(2) } },
                        onGetStarted = {
                            viewModel.completeSetup(
                                storageSizeGb = selectedGb,
                                vmRamMb = selectedRamMb,
                                vmCpus = selectedCpus,
                                sshEnabled = sshEnabled,
                                storageAccessEnabled = storageAccessEnabled,
                                usbPassthroughEnabled = usbPassthroughEnabled && usbPassthroughAvailable,
                                loadBalanceEnabled = loadBalanceEnabled,
                                bandwidthMbps = selectedBandwidthMbps,
                            )
                        },
                    )
                }
            }

            // Pill-shaped page indicator
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 16.dp),
                horizontalArrangement = Arrangement.Center,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                repeat(4) { index ->
                    val isSelected = pagerState.currentPage == index
                    val dotWidth by animateDpAsState(
                        targetValue = if (isSelected) 24.dp else 8.dp,
                        label = "dot_width",
                    )
                    Box(
                        modifier = Modifier
                            .padding(horizontal = 4.dp)
                            .height(8.dp)
                            .width(dotWidth)
                            .background(
                                color = if (isSelected)
                                    MaterialTheme.colorScheme.primary
                                else
                                    MaterialTheme.colorScheme.outlineVariant,
                                shape = CircleShape,
                            ),
                    )
                }
            }
        }
    }
}

// ── Setup page scaffold ───────────────────────────────────────────────────────

@Composable
private fun SetupPageLayout(
    windowSizeClass: WindowSizeClass,
    stepLabel: String,
    title: String,
    description: String,
    bottomBar: @Composable () -> Unit,
    content: @Composable () -> Unit,
) {
    // Compact height = landscape phone or split-screen — go side-by-side so the
    // hero (step label + title + description) doesn't push the chips off-screen.
    val isCompactHeight = windowSizeClass.heightSizeClass == WindowHeightSizeClass.Compact
    AdaptiveContainer(
        windowSizeClass = windowSizeClass,
        modifier = Modifier.fillMaxSize(),
        maxWidth = if (isCompactHeight) 920 else 600,
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = PodroidTokens.Spacing.XL),
        ) {
            if (isCompactHeight) {
                Row(
                    modifier = Modifier
                        .weight(1f)
                        .verticalScroll(rememberScrollState()),
                ) {
                    Column(
                        modifier = Modifier
                            .weight(1f)
                            .padding(top = PodroidTokens.Spacing.XL, end = PodroidTokens.Spacing.XL),
                    ) {
                        PodroidSectionLabel(stepLabel)
                        Text(
                            text = title,
                            style = MaterialTheme.typography.displayLarge,
                            color = MaterialTheme.colorScheme.onSurface,
                        )
                        Spacer(Modifier.height(PodroidTokens.Spacing.SM))
                        Text(
                            text = description,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Column(
                        modifier = Modifier
                            .weight(1f)
                            .padding(top = PodroidTokens.Spacing.XL),
                    ) {
                        content()
                    }
                }
            } else {
                Column(
                    modifier = Modifier
                        .weight(1f)
                        .verticalScroll(rememberScrollState()),
                ) {
                    Spacer(Modifier.height(PodroidTokens.Spacing.XL))
                    PodroidSectionLabel(stepLabel)
                    Text(
                        text = title,
                        style = MaterialTheme.typography.displayLarge,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                    Spacer(Modifier.height(PodroidTokens.Spacing.SM))
                    Text(
                        text = description,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(PodroidTokens.Spacing.XL))
                    content()
                    Spacer(Modifier.height(PodroidTokens.Spacing.XL))
                }
            }
            bottomBar()
        }
    }
}

// ── Page 1: Storage ───────────────────────────────────────────────────────────

@Composable
private fun StoragePage(
    windowSizeClass: WindowSizeClass,
    selectedGb: Int,
    loadBalanceEnabled: Boolean,
    onSelect: (Int) -> Unit,
    onNext: () -> Unit,
) {
    SetupPageLayout(
        windowSizeClass = windowSizeClass,
        stepLabel  = stringResource(R.string.step_1_of_4),
        title      = stringResource(R.string.persistent_storage),
        description = stringResource(R.string.storage_description),
        bottomBar  = { SetupNextBar(onNext = onNext) },
    ) {
        VmStorageChips(
            currentGb = selectedGb,
            onChange = onSelect,
            enabled = !loadBalanceEnabled,
            showDivider = false,
        )
        if (loadBalanceEnabled) {
            Spacer(Modifier.height(PodroidTokens.Spacing.SM))
            Text(
                text = stringResource(R.string.load_balance_storage_hint),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

// ── Page 2: VM config + SSH ───────────────────────────────────────────────────

@Composable
private fun VmConfigPage(
    windowSizeClass: WindowSizeClass,
    storageGb: Int,
    ramMb: Int,
    cpus: Int,
    bandwidthMbps: Int,
    loadBalanceEnabled: Boolean,
    onLoadBalanceToggle: (Boolean) -> Unit,
    onRamChange: (Int) -> Unit,
    onCpusChange: (Int) -> Unit,
    onBandwidthChange: (Int) -> Unit,
    sshEnabled: Boolean,
    onSshToggle: (Boolean) -> Unit,
    onBack: () -> Unit,
    onNext: () -> Unit,
) {
    SetupPageLayout(
        windowSizeClass = windowSizeClass,
        stepLabel  = stringResource(R.string.step_2_of_4),
        title      = stringResource(R.string.configure_vm),
        description = stringResource(R.string.vm_config_description),
        bottomBar  = { SetupNavBar(onBack = onBack, onNext = onNext, nextLabel = stringResource(R.string.continue_label)) },
    ) {
        PodroidListRow(
            label = stringResource(R.string.load_balance),
            rightSlot = {
                PodroidSwitch(checked = loadBalanceEnabled, onCheckedChange = onLoadBalanceToggle)
            },
        )
        if (loadBalanceEnabled) {
            Text(
                text = stringResource(R.string.load_balance_description),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(bottom = PodroidTokens.Spacing.SM),
            )
        }

        PodroidSectionLabel(stringResource(R.string.resources))
        PodroidListRow(label = stringResource(R.string.storage), value = "$storageGb GB")
        VmRamChips(
            currentMb = ramMb,
            onChange = onRamChange,
            enabled = !loadBalanceEnabled,
            showDivider = false,
        )
        VmCpuChips(
            currentCpus = cpus,
            onChange = onCpusChange,
            enabled = !loadBalanceEnabled,
            showDivider = false,
        )
        VmBandwidthChips(
            currentMbps = bandwidthMbps,
            onChange = onBandwidthChange,
            enabled = !loadBalanceEnabled,
            showDivider = true,
        )

        PodroidSectionLabel(stringResource(R.string.network_label))
        PodroidListRow(
            label = stringResource(R.string.ssh_access),
            rightSlot = { PodroidSwitch(checked = sshEnabled, onCheckedChange = onSshToggle) },
            divider = false,
        )
        if (sshEnabled) {
            Spacer(Modifier.height(PodroidTokens.Spacing.XS))
            Text(
                text = stringResource(R.string.ssh_password_hint),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                fontFamily = PodroidTokens.mono(),
            )
        }
    }
}

// ── Page 3: Storage access ────────────────────────────────────────────────────

@Composable
private fun StorageAccessPage(
    windowSizeClass: WindowSizeClass,
    storageAccessEnabled: Boolean,
    hasStoragePermission: Boolean,
    onStoragePermissionChanged: (Boolean) -> Unit,
    onStorageAccessToggle: (Boolean) -> Unit,
    onOpenStorageAccessSettings: () -> Unit,
    onBack: () -> Unit,
    onNext: () -> Unit,
) {
    val canManageAllFiles = Build.VERSION.SDK_INT >= Build.VERSION_CODES.R
    val writeStoragePermLauncher = rememberLauncherForActivityResult(RequestPermission()) { granted ->
        onStoragePermissionChanged(granted)
    }

    SetupPageLayout(
        windowSizeClass = windowSizeClass,
        stepLabel  = stringResource(R.string.step_3_of_4),
        title      = stringResource(R.string.downloads_sharing),
        description = stringResource(R.string.storage_access_description),
        bottomBar  = { SetupNavBar(onBack = onBack, onNext = onNext, nextLabel = stringResource(R.string.continue_label)) },
    ) {
        PodroidSectionLabel(stringResource(R.string.sharing))
        PodroidListRow(
            label = stringResource(R.string.enable_downloads_sharing),
            rightSlot = { PodroidSwitch(checked = storageAccessEnabled, onCheckedChange = onStorageAccessToggle) },
        )
        if (!canManageAllFiles) {
            Spacer(Modifier.height(PodroidTokens.Spacing.SM))
            Text(
                text = stringResource(R.string.requires_android_11),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (storageAccessEnabled && !hasStoragePermission) {
            Spacer(Modifier.height(PodroidTokens.Spacing.LG))
            if (canManageAllFiles) {
                PodroidPrimaryButton(text = stringResource(R.string.grant_storage_access), onClick = onOpenStorageAccessSettings)
            } else {
                PodroidPrimaryButton(
                    text = stringResource(R.string.grant_storage_access),
                    onClick = { writeStoragePermLauncher.launch(Manifest.permission.WRITE_EXTERNAL_STORAGE) },
                )
            }
        }
        Spacer(Modifier.height(PodroidTokens.Spacing.LG))
        Text(
            text = stringResource(R.string.storage_access_warning),
            style = MaterialTheme.typography.bodyMedium,
            color = PodroidTokens.Amber,
        )
    }
}

// ── Page 4: USB passthrough ───────────────────────────────────────────────────

@Composable
private fun UsbPassthroughPage(
    windowSizeClass: WindowSizeClass,
    usbPassthroughEnabled: Boolean,
    available: Boolean,
    onUsbPassthroughToggle: (Boolean) -> Unit,
    onBack: () -> Unit,
    onGetStarted: () -> Unit,
) {
    SetupPageLayout(
        windowSizeClass = windowSizeClass,
        stepLabel  = stringResource(R.string.step_4_of_4),
        title      = stringResource(R.string.usb_passthrough),
        description = stringResource(R.string.usb_passthrough_description),
        bottomBar  = { SetupNavBar(onBack = onBack, onNext = onGetStarted, nextLabel = stringResource(R.string.get_started)) },
    ) {
        PodroidSectionLabel(stringResource(R.string.usb_devices_section))
        PodroidListRow(
            label = stringResource(R.string.enable_usb_passthrough),
            rightSlot = {
                PodroidSwitch(
                    checked = usbPassthroughEnabled && available,
                    onCheckedChange = onUsbPassthroughToggle,
                    enabled = available,
                )
            },
        )
        Spacer(Modifier.height(PodroidTokens.Spacing.SM))
        Text(
            text = if (available) {
                stringResource(R.string.usb_passthrough_available)
            } else {
                stringResource(R.string.usb_passthrough_unavailable)
            },
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

// ── Setup bottom bars ─────────────────────────────────────────────────────────

@Composable
private fun SetupNextBar(onNext: () -> Unit) {
    Column(modifier = Modifier.padding(vertical = PodroidTokens.Spacing.LG)) {
        PodroidPrimaryButton(text = stringResource(R.string.continue_label), onClick = onNext)
    }
}

@Composable
private fun SetupNavBar(
    onBack: () -> Unit,
    onNext: () -> Unit,
    nextLabel: String,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = PodroidTokens.Spacing.LG),
        horizontalArrangement = Arrangement.spacedBy(PodroidTokens.Spacing.SM),
    ) {
        PodroidGhostButton(text = stringResource(R.string.back), onClick = onBack, modifier = Modifier.weight(1f))
        PodroidPrimaryButton(text = nextLabel, onClick = onNext, modifier = Modifier.weight(2f))
    }
}
