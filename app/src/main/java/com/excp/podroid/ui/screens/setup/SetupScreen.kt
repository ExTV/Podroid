package com.excp.podroid.ui.screens.setup

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.Settings
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
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.FilterChip
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.excp.podroid.ui.components.AdaptiveContainer
import com.excp.podroid.ui.components.PodroidGhostButton
import com.excp.podroid.ui.components.PodroidListRow
import com.excp.podroid.ui.components.PodroidPrimaryButton
import com.excp.podroid.ui.components.PodroidSectionLabel
import com.excp.podroid.ui.components.PodroidSwitch
import com.excp.podroid.ui.components.PodroidChipColors
import com.excp.podroid.ui.theme.PodroidTokens
import kotlinx.coroutines.launch

private val storageSizes = listOf(2, 4, 8, 16, 32, 64)
private const val DEFAULT_STORAGE_GB = 8

@Composable
fun SetupScreen(
    windowSizeClass: WindowSizeClass,
    onSetupComplete: () -> Unit,
    viewModel: SetupViewModel = hiltViewModel(),
) {
    val context = LocalContext.current
    // rememberSaveable preserves wizard choices through config changes (rotation, font-scale, etc.)
    // so a mid-wizard rotation doesn't silently reset storage to 8 GB (not resizable later).
    var selectedGb by rememberSaveable { mutableIntStateOf(DEFAULT_STORAGE_GB) }
    var sshEnabled by rememberSaveable { mutableStateOf(true) }
    var storageAccessEnabled by rememberSaveable { mutableStateOf(false) }
    var usbPassthroughEnabled by rememberSaveable { mutableStateOf(false) }
    val usbPassthroughAvailable = remember { viewModel.usbPassthroughAvailable() }
    val setupComplete by viewModel.setupComplete.collectAsStateWithLifecycle()
    val pagerState = rememberPagerState(pageCount = { 4 })
    val scope = rememberCoroutineScope()

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

    // Re-sync storageAccessEnabled against the actual OS grant on every
    // resume (the user may have denied the all-files-access screen we sent them to).
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME &&
                Build.VERSION.SDK_INT >= Build.VERSION_CODES.R
            ) {
                storageAccessEnabled = storageAccessEnabled && Environment.isExternalStorageManager()
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
                        onSelect = { selectedGb = it },
                        onNext = { scope.launch { pagerState.animateScrollToPage(1) } },
                    )
                    1 -> VmConfigPage(
                        windowSizeClass = windowSizeClass,
                        sshEnabled = sshEnabled,
                        onSshToggle = { sshEnabled = it },
                        onBack = { scope.launch { pagerState.animateScrollToPage(0) } },
                        onNext = { scope.launch { pagerState.animateScrollToPage(2) } },
                    )
                    2 -> StorageAccessPage(
                        windowSizeClass = windowSizeClass,
                        storageAccessEnabled = storageAccessEnabled,
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
                                sshEnabled = sshEnabled,
                                storageAccessEnabled = storageAccessEnabled,
                                usbPassthroughEnabled = usbPassthroughEnabled && usbPassthroughAvailable,
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
    onSelect: (Int) -> Unit,
    onNext: () -> Unit,
) {
    SetupPageLayout(
        windowSizeClass = windowSizeClass,
        stepLabel  = "Step 1 of 3",
        title      = "Persistent Storage",
        description = "Storage for installed packages, container images, and your files. Cannot be resized later without resetting the VM.",
        bottomBar  = { SetupNextBar(onNext = onNext) },
    ) {
        Text(
            text = "$selectedGb GB",
            style = MaterialTheme.typography.displayLarge,
            color = MaterialTheme.colorScheme.primary,
        )
        Spacer(Modifier.height(PodroidTokens.Spacing.MD))
        StorageSizeChips(selectedGb, onSelect)
    }
}

// ── Page 2: VM config + SSH ───────────────────────────────────────────────────

@Composable
private fun VmConfigPage(
    windowSizeClass: WindowSizeClass,
    sshEnabled: Boolean,
    onSshToggle: (Boolean) -> Unit,
    onBack: () -> Unit,
    onNext: () -> Unit,
) {
    SetupPageLayout(
        windowSizeClass = windowSizeClass,
        stepLabel  = "Step 2 of 3",
        title      = "Configure your VM",
        description = "Defaults tuned for performance and battery. Change anytime in Settings.",
        bottomBar  = { SetupNavBar(onBack = onBack, onNext = onNext, nextLabel = "Continue") },
    ) {
        PodroidSectionLabel("Resources")
        PodroidListRow(label = "CPU cores", value = "2")
        PodroidListRow(label = "RAM",       value = "512 MB")

        PodroidSectionLabel("Network")
        PodroidListRow(
            label = "SSH access",
            rightSlot = { PodroidSwitch(checked = sshEnabled, onCheckedChange = onSshToggle) },
            divider = false,
        )
        if (sshEnabled) {
            Spacer(Modifier.height(PodroidTokens.Spacing.XS))
            Text(
                text = "ssh root@<phone-ip> -p 9922   (password: podroid)",
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
    onStorageAccessToggle: (Boolean) -> Unit,
    onOpenStorageAccessSettings: () -> Unit,
    onBack: () -> Unit,
    onNext: () -> Unit,
) {
    val canManageAllFiles = Build.VERSION.SDK_INT >= Build.VERSION_CODES.R
    val hasStoragePermission = !canManageAllFiles || Environment.isExternalStorageManager()

    SetupPageLayout(
        windowSizeClass = windowSizeClass,
        stepLabel  = "Step 3 of 4",
        title      = "Downloads Sharing",
        description = "Optional. Mount your Android Downloads folder into the VM over virtio-9p.",
        bottomBar  = { SetupNavBar(onBack = onBack, onNext = onNext, nextLabel = "Continue") },
    ) {
        PodroidSectionLabel("Sharing")
        PodroidListRow(
            label = "Enable Downloads sharing",
            rightSlot = { PodroidSwitch(checked = storageAccessEnabled, onCheckedChange = onStorageAccessToggle) },
        )
        if (!canManageAllFiles) {
            Spacer(Modifier.height(PodroidTokens.Spacing.SM))
            Text(
                text = "Requires Android 11+. On this device the toggle is for future-OS upgrades only.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (storageAccessEnabled && canManageAllFiles && !hasStoragePermission) {
            Spacer(Modifier.height(PodroidTokens.Spacing.LG))
            PodroidPrimaryButton(text = "Grant storage access", onClick = onOpenStorageAccessSettings)
        }
        Spacer(Modifier.height(PodroidTokens.Spacing.LG))
        Text(
            text = "Warning: on some devices this can crash the VM. Disable it if start fails.",
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
        stepLabel  = "Step 4 of 4",
        title      = "USB Passthrough",
        description = "Optional. Hot-plug external USB devices (Wi-Fi, storage, serial, etc.) " +
            "into the VM at runtime.",
        bottomBar  = { SetupNavBar(onBack = onBack, onNext = onGetStarted, nextLabel = "Get Started") },
    ) {
        PodroidSectionLabel("Devices")
        PodroidListRow(
            label = "Enable USB passthrough",
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
                "Each device asks for permission when you plug it in. Adds a USB " +
                    "controller to the VM, which costs a little emulation overhead, " +
                    "so leave it off if you don't need it. You can change this later in Settings."
            } else {
                "Not available on this backend: USB passthrough needs QEMU's control " +
                    "socket, which the AVF backend doesn't provide. You can change this " +
                    "later in Settings."
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
        PodroidPrimaryButton(text = "Continue", onClick = onNext)
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
        PodroidGhostButton(text = "Back", onClick = onBack, modifier = Modifier.weight(1f))
        PodroidPrimaryButton(text = nextLabel, onClick = onNext, modifier = Modifier.weight(2f))
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun StorageSizeChips(selectedGb: Int, onSelect: (Int) -> Unit) {
    FlowRow(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally),
        verticalArrangement = Arrangement.spacedBy(8.dp),
        maxItemsInEachRow = 3,
    ) {
        storageSizes.forEach { gb ->
            FilterChip(
                selected = gb == selectedGb,
                onClick = { onSelect(gb) },
                label = {
                    Text(
                        text = "$gb GB",
                        style = MaterialTheme.typography.labelLarge,
                        fontWeight = if (gb == selectedGb) FontWeight.Bold else FontWeight.Normal,
                    )
                },
                shape = RoundedCornerShape(16.dp),
                colors = PodroidChipColors(),
            )
        }
    }
}
