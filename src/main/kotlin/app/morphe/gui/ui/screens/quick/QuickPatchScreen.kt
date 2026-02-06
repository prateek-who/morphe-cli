package app.morphe.gui.ui.screens.quick

import androidx.compose.animation.*
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.draganddrop.dragAndDropTarget
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.draganddrop.DragAndDropEvent
import androidx.compose.ui.draganddrop.DragAndDropTarget
import androidx.compose.ui.draganddrop.awtTransferable
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import cafe.adriel.voyager.core.screen.Screen
import app.morphe.morphe_cli.generated.resources.Res
import app.morphe.morphe_cli.generated.resources.reddit
import app.morphe.morphe_cli.generated.resources.youtube
import app.morphe.morphe_cli.generated.resources.youtube_music
import app.morphe.gui.data.constants.AppConstants
import app.morphe.gui.data.repository.ConfigRepository
import app.morphe.gui.data.repository.PatchRepository
import app.morphe.gui.util.PatchService
import org.jetbrains.compose.resources.painterResource
import org.koin.compose.koinInject
import app.morphe.gui.ui.components.SettingsButton
import app.morphe.gui.ui.theme.MorpheColors
import app.morphe.gui.util.AdbManager
import app.morphe.gui.util.ChecksumStatus
import java.awt.Desktop
import java.awt.datatransfer.DataFlavor
import java.io.File
import javax.swing.JFileChooser
import javax.swing.filechooser.FileNameExtensionFilter

/**
 * Quick Patch Mode - Single screen simplified patching.
 */
class QuickPatchScreen : Screen {
    @Composable
    override fun Content() {
        val patchRepository: PatchRepository = koinInject()
        val patchService: PatchService = koinInject()
        val configRepository: ConfigRepository = koinInject()

        val viewModel = remember {
            QuickPatchViewModel(patchRepository, patchService, configRepository)
        }

        QuickPatchContent(viewModel)
    }
}

@OptIn(ExperimentalComposeUiApi::class)
@Composable
fun QuickPatchContent(viewModel: QuickPatchViewModel) {
    val uiState by viewModel.uiState.collectAsState()
    val uriHandler = LocalUriHandler.current

    // Compose drag and drop target
    val dragAndDropTarget = remember {
        object : DragAndDropTarget {
            override fun onStarted(event: DragAndDropEvent) {
                viewModel.setDragHover(true)
            }

            override fun onEnded(event: DragAndDropEvent) {
                viewModel.setDragHover(false)
            }

            override fun onExited(event: DragAndDropEvent) {
                viewModel.setDragHover(false)
            }

            override fun onEntered(event: DragAndDropEvent) {
                viewModel.setDragHover(true)
            }

            override fun onDrop(event: DragAndDropEvent): Boolean {
                viewModel.setDragHover(false)
                val transferable = event.awtTransferable
                return try {
                    if (transferable.isDataFlavorSupported(DataFlavor.javaFileListFlavor)) {
                        @Suppress("UNCHECKED_CAST")
                        val files = transferable.getTransferData(DataFlavor.javaFileListFlavor) as List<File>
                        val apkFile = files.firstOrNull { it.name.endsWith(".apk", ignoreCase = true) }
                        if (apkFile != null) {
                            viewModel.onFileSelected(apkFile)
                            true
                        } else {
                            false
                        }
                    } else {
                        false
                    }
                } catch (e: Exception) {
                    false
                }
            }
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .dragAndDropTarget(
                shouldStartDragAndDrop = { true },
                target = dragAndDropTarget
            )
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            // Header
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "Morphe Quick Patch",
                    fontSize = 18.sp,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface
                )

                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    // Mode indicator
                    Surface(
                        color = MorpheColors.Blue.copy(alpha = 0.1f),
                        shape = RoundedCornerShape(4.dp)
                    ) {
                        Text(
                            text = "QUICK MODE",
                            fontSize = 10.sp,
                            fontWeight = FontWeight.Bold,
                            color = MorpheColors.Blue,
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                        )
                    }

                    // Settings button
                    SettingsButton()
                }
            }

            Spacer(modifier = Modifier.height(20.dp))

            // Main content based on phase
            // Remember last valid data for safe animation transitions
            val lastApkInfo = remember(uiState.apkInfo) { uiState.apkInfo }
            val lastOutputPath = remember(uiState.outputPath) { uiState.outputPath }

            AnimatedContent(
                targetState = uiState.phase,
                modifier = Modifier.weight(1f)
            ) { phase ->
                when (phase) {
                    QuickPatchPhase.IDLE, QuickPatchPhase.ANALYZING -> {
                        IdleContent(
                            isAnalyzing = phase == QuickPatchPhase.ANALYZING,
                            isDragHovering = uiState.isDragHovering,
                            error = uiState.error,
                            onFileSelected = { viewModel.onFileSelected(it) },
                            onDragHover = { viewModel.setDragHover(it) },
                            onClearError = { viewModel.clearError() }
                        )
                    }
                    QuickPatchPhase.READY -> {
                        // Use current or last known apkInfo to prevent crash during animation
                        val apkInfo = uiState.apkInfo ?: lastApkInfo
                        if (apkInfo != null) {
                            ReadyContent(
                                apkInfo = apkInfo,
                                error = uiState.error,
                                onPatch = { viewModel.startPatching() },
                                onClear = { viewModel.reset() },
                                onClearError = { viewModel.clearError() }
                            )
                        }
                    }
                    QuickPatchPhase.DOWNLOADING, QuickPatchPhase.PATCHING -> {
                        PatchingContent(
                            phase = phase,
                            progress = uiState.progress,
                            statusMessage = uiState.statusMessage,
                            onCancel = { viewModel.cancelPatching() }
                        )
                    }
                    QuickPatchPhase.COMPLETED -> {
                        val apkInfo = uiState.apkInfo ?: lastApkInfo
                        val outputPath = uiState.outputPath ?: lastOutputPath
                        if (apkInfo != null && outputPath != null) {
                            CompletedContent(
                                outputPath = outputPath,
                                apkInfo = apkInfo,
                                onPatchAnother = { viewModel.reset() }
                            )
                        }
                    }
                }
            }

            // Bottom app cards (only show in IDLE phase)
            if (uiState.phase == QuickPatchPhase.IDLE) {
                Spacer(modifier = Modifier.height(16.dp))
                SupportedAppsRow(
                    supportedApps = uiState.supportedApps,
                    isLoading = uiState.isLoadingPatches,
                    patchesVersion = uiState.patchesVersion,
                    onOpenUrl = { url -> uriHandler.openUri(url) }
                )
            }
        }

        // Error snackbar
        uiState.error?.let { error ->
            Snackbar(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(16.dp),
                action = {
                    TextButton(onClick = { viewModel.clearError() }) {
                        Text("Dismiss", color = MaterialTheme.colorScheme.inversePrimary)
                    }
                },
                containerColor = MaterialTheme.colorScheme.errorContainer,
                contentColor = MaterialTheme.colorScheme.onErrorContainer
            ) {
                Text(error)
            }
        }
    }
}

@Composable
private fun IdleContent(
    isAnalyzing: Boolean,
    isDragHovering: Boolean,
    error: String?,
    onFileSelected: (File) -> Unit,
    onDragHover: (Boolean) -> Unit,
    onClearError: () -> Unit
) {
    val dropZoneColor = when {
        isDragHovering -> MorpheColors.Blue.copy(alpha = 0.2f)
        else -> MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f)
    }

    val borderColor = when {
        isDragHovering -> MorpheColors.Blue
        else -> MaterialTheme.colorScheme.outline.copy(alpha = 0.3f)
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .clip(RoundedCornerShape(16.dp))
            .background(dropZoneColor)
            .border(2.dp, borderColor, RoundedCornerShape(16.dp))
            .clickable(enabled = !isAnalyzing) {
                openFilePicker()?.let { onFileSelected(it) }
            },
        contentAlignment = Alignment.Center
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            if (isAnalyzing) {
                CircularProgressIndicator(
                    modifier = Modifier.size(48.dp),
                    color = MorpheColors.Blue,
                    strokeWidth = 3.dp
                )
                Spacer(modifier = Modifier.height(16.dp))
                Text(
                    text = "Analyzing APK...",
                    fontSize = 16.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            } else {
                Icon(
                    imageVector = Icons.Default.CloudUpload,
                    contentDescription = null,
                    modifier = Modifier.size(48.dp),
                    tint = if (isDragHovering) MorpheColors.Blue else MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(modifier = Modifier.height(16.dp))
                Text(
                    text = "Drop APK here",
                    fontSize = 18.sp,
                    fontWeight = FontWeight.Medium,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = "or click to browse",
                    fontSize = 14.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

@Composable
private fun ReadyContent(
    apkInfo: QuickApkInfo,
    error: String?,
    onPatch: () -> Unit,
    onClear: () -> Unit,
    onClearError: () -> Unit
) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        // APK Info Card
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
            ),
            shape = RoundedCornerShape(12.dp)
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                // App icon
                Box(
                    modifier = Modifier
                        .size(48.dp)
                        .clip(RoundedCornerShape(8.dp))
                        .background(Color.White),
                    contentAlignment = Alignment.Center
                ) {
                    Image(
                        painter = painterResource(
                            when (apkInfo.packageName) {
                                AppConstants.YouTube.PACKAGE_NAME -> Res.drawable.youtube
                                AppConstants.YouTubeMusic.PACKAGE_NAME -> Res.drawable.youtube_music
                                AppConstants.Reddit.PACKAGE_NAME -> Res.drawable.reddit
                                else -> Res.drawable.youtube // Fallback
                            }
                        ),
                        contentDescription = "${apkInfo.displayName} icon",
                        modifier = Modifier.size(36.dp)
                    )
                }

                Spacer(modifier = Modifier.width(16.dp))

                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = apkInfo.displayName,
                        fontSize = 16.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Text(
                        text = "v${apkInfo.versionName} • ${apkInfo.formattedSize}",
                        fontSize = 13.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }

                // Checksum status
                when (apkInfo.checksumStatus) {
                    is ChecksumStatus.Verified -> {
                        Icon(
                            imageVector = Icons.Default.VerifiedUser,
                            contentDescription = "Verified",
                            tint = MorpheColors.Teal,
                            modifier = Modifier.size(24.dp)
                        )
                    }
                    is ChecksumStatus.Mismatch -> {
                        Icon(
                            imageVector = Icons.Default.Warning,
                            contentDescription = "Checksum mismatch",
                            tint = MaterialTheme.colorScheme.error,
                            modifier = Modifier.size(24.dp)
                        )
                    }
                    else -> {}
                }

                Spacer(modifier = Modifier.width(8.dp))

                IconButton(onClick = onClear) {
                    Icon(
                        imageVector = Icons.Default.Close,
                        contentDescription = "Clear",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(8.dp))

        // Verification status banner
        VerificationStatusBanner(
            checksumStatus = apkInfo.checksumStatus,
            isRecommendedVersion = apkInfo.isRecommendedVersion,
            currentVersion = apkInfo.versionName,
            suggestedVersion = apkInfo.recommendedVersion ?: "Unknown"
        )

        Spacer(modifier = Modifier.weight(1f))

        // Patch button
        Button(
            onClick = onPatch,
            modifier = Modifier
                .fillMaxWidth()
                .height(52.dp),
            colors = ButtonDefaults.buttonColors(
                containerColor = MorpheColors.Blue
            ),
            shape = RoundedCornerShape(12.dp)
        ) {
            Icon(
                imageVector = Icons.Default.AutoFixHigh,
                contentDescription = null,
                modifier = Modifier.size(20.dp)
            )
            Spacer(modifier = Modifier.width(8.dp))
            Text(
                text = "Patch with Defaults",
                fontSize = 16.sp,
                fontWeight = FontWeight.Medium
            )
        }

        Spacer(modifier = Modifier.height(8.dp))

        Text(
            text = "Uses latest patches with recommended settings",
            fontSize = 12.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center
        )
    }
}

@Composable
private fun PatchingContent(
    phase: QuickPatchPhase,
    progress: Float,
    statusMessage: String,
    onCancel: () -> Unit
) {
    Column(
        modifier = Modifier.fillMaxSize(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        // Progress indicator
        Box(contentAlignment = Alignment.Center) {
            CircularProgressIndicator(
                progress = { progress },
                modifier = Modifier.size(100.dp),
                strokeWidth = 6.dp,
                color = MorpheColors.Teal,
                trackColor = MaterialTheme.colorScheme.surfaceVariant
            )
            Text(
                text = "${(progress * 100).toInt()}%",
                fontSize = 20.sp,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurface
            )
        }

        Spacer(modifier = Modifier.height(24.dp))

        Text(
            text = when (phase) {
                QuickPatchPhase.DOWNLOADING -> "Preparing..."
                QuickPatchPhase.PATCHING -> "Patching..."
                else -> ""
            },
            fontSize = 18.sp,
            fontWeight = FontWeight.Medium,
            color = MaterialTheme.colorScheme.onSurface
        )

        Spacer(modifier = Modifier.height(8.dp))

        Text(
            text = statusMessage,
            fontSize = 13.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(horizontal = 16.dp)
        )

        Spacer(modifier = Modifier.height(24.dp))

        TextButton(onClick = onCancel) {
            Text("Cancel", color = MaterialTheme.colorScheme.error)
        }
    }
}

@Composable
private fun CompletedContent(
    outputPath: String,
    apkInfo: QuickApkInfo,
    onPatchAnother: () -> Unit
) {
    val outputFile = File(outputPath)
    val adbManager = remember { AdbManager() }
    var isAdbAvailable by remember { mutableStateOf<Boolean?>(null) }

    LaunchedEffect(Unit) {
        isAdbAvailable = adbManager.isAdbAvailable()
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState()),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Icon(
            imageVector = Icons.Default.CheckCircle,
            contentDescription = "Success",
            tint = MorpheColors.Teal,
            modifier = Modifier.size(64.dp)
        )

        Spacer(modifier = Modifier.height(16.dp))

        Text(
            text = "Patching Complete!",
            fontSize = 22.sp,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onSurface
        )

        Spacer(modifier = Modifier.height(8.dp))

        Text(
            text = outputFile.name,
            fontSize = 14.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center
        )

        if (outputFile.exists()) {
            Text(
                text = formatFileSize(outputFile.length()),
                fontSize = 13.sp,
                color = MorpheColors.Teal
            )
        }

        Spacer(modifier = Modifier.height(24.dp))

        // Action buttons
        Row(
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            OutlinedButton(
                onClick = {
                    try {
                        val folder = outputFile.parentFile
                        if (folder != null && Desktop.isDesktopSupported()) {
                            Desktop.getDesktop().open(folder)
                        }
                    } catch (e: Exception) { }
                },
                shape = RoundedCornerShape(8.dp)
            ) {
                Icon(
                    imageVector = Icons.Default.FolderOpen,
                    contentDescription = null,
                    modifier = Modifier.size(18.dp)
                )
                Spacer(modifier = Modifier.width(6.dp))
                Text("Open Folder")
            }

            Button(
                onClick = onPatchAnother,
                colors = ButtonDefaults.buttonColors(
                    containerColor = MorpheColors.Blue
                ),
                shape = RoundedCornerShape(8.dp)
            ) {
                Text("Patch Another")
            }
        }

        if (isAdbAvailable == true) {
            Spacer(modifier = Modifier.height(12.dp))
            Text(
                text = "Connect your device via USB to install with ADB",
                fontSize = 12.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun SupportedAppsRow(
    supportedApps: List<app.morphe.gui.data.model.SupportedApp>,
    isLoading: Boolean,
    patchesVersion: String?,
    onOpenUrl: (String) -> Unit
) {
    Column(
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "Get the APK from APKMirror:",
                fontSize = 12.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            if (patchesVersion != null) {
                Text(
                    text = "Patches: $patchesVersion",
                    fontSize = 11.sp,
                    color = MorpheColors.Blue.copy(alpha = 0.8f)
                )
            }
        }

        Spacer(modifier = Modifier.height(8.dp))

        if (isLoading) {
            // Loading state
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.Center,
                verticalAlignment = Alignment.CenterVertically
            ) {
                CircularProgressIndicator(
                    modifier = Modifier.size(16.dp),
                    strokeWidth = 2.dp,
                    color = MorpheColors.Blue
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = "Loading supported apps...",
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        } else if (supportedApps.isEmpty()) {
            // No apps loaded
            Text(
                text = "Could not load supported apps",
                fontSize = 12.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        } else {
            // Show supported apps dynamically
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                supportedApps.forEach { app ->
                    val url = app.apkMirrorUrl
                    if (url != null) {
                        OutlinedCard(
                            onClick = { onOpenUrl(url) },
                            modifier = Modifier.weight(1f),
                            shape = RoundedCornerShape(8.dp)
                        ) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(12.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Box(
                                    modifier = Modifier
                                        .size(24.dp)
                                        .clip(RoundedCornerShape(4.dp))
                                        .background(Color.White),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Image(
                                        painter = painterResource(
                                            when (app.packageName) {
                                                AppConstants.YouTube.PACKAGE_NAME -> Res.drawable.youtube
                                                AppConstants.YouTubeMusic.PACKAGE_NAME -> Res.drawable.youtube_music
                                                AppConstants.Reddit.PACKAGE_NAME -> Res.drawable.reddit
                                                else -> Res.drawable.youtube // Fallback
                                            }
                                        ),
                                        contentDescription = null,
                                        modifier = Modifier.size(18.dp)
                                    )
                                }
                                Spacer(modifier = Modifier.width(8.dp))
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        text = app.displayName,
                                        fontSize = 13.sp,
                                        fontWeight = FontWeight.Medium
                                    )
                                    app.recommendedVersion?.let { version ->
                                        Text(
                                            text = "v$version",
                                            fontSize = 10.sp,
                                            color = MorpheColors.Teal
                                        )
                                    }
                                }
                                Icon(
                                    imageVector = Icons.AutoMirrored.Filled.OpenInNew,
                                    contentDescription = "Open",
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.size(16.dp)
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

/**
 * Shows verification status (version + checksum) in a compact banner.
 */
@Composable
private fun VerificationStatusBanner(
    checksumStatus: ChecksumStatus,
    isRecommendedVersion: Boolean,
    currentVersion: String,
    suggestedVersion: String
) {
    when {
        // Recommended version with verified checksum
        checksumStatus is ChecksumStatus.Verified -> {
            Surface(
                modifier = Modifier.fillMaxWidth(),
                color = MorpheColors.Teal.copy(alpha = 0.1f),
                shape = RoundedCornerShape(8.dp)
            ) {
                Row(
                    modifier = Modifier.padding(12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.Center
                ) {
                    Icon(
                        imageVector = Icons.Default.VerifiedUser,
                        contentDescription = null,
                        tint = MorpheColors.Teal,
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Column {
                        Text(
                            text = "Recommended version • Verified",
                            fontSize = 13.sp,
                            fontWeight = FontWeight.Medium,
                            color = MorpheColors.Teal
                        )
                        Text(
                            text = "Checksum matches APKMirror",
                            fontSize = 11.sp,
                            color = MorpheColors.Teal.copy(alpha = 0.8f)
                        )
                    }
                }
            }
        }

        // Checksum mismatch - warning
        checksumStatus is ChecksumStatus.Mismatch -> {
            Surface(
                modifier = Modifier.fillMaxWidth(),
                color = MaterialTheme.colorScheme.error.copy(alpha = 0.1f),
                shape = RoundedCornerShape(8.dp)
            ) {
                Row(
                    modifier = Modifier.padding(12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        imageVector = Icons.Default.Warning,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.error,
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Column {
                        Text(
                            text = "Checksum mismatch",
                            fontSize = 13.sp,
                            fontWeight = FontWeight.Medium,
                            color = MaterialTheme.colorScheme.error
                        )
                        Text(
                            text = "File may be corrupted. Re-download from APKMirror.",
                            fontSize = 11.sp,
                            color = MaterialTheme.colorScheme.error.copy(alpha = 0.8f)
                        )
                    }
                }
            }
        }

        // Recommended version but no checksum configured
        isRecommendedVersion && checksumStatus is ChecksumStatus.NotConfigured -> {
            Surface(
                modifier = Modifier.fillMaxWidth(),
                color = MorpheColors.Teal.copy(alpha = 0.1f),
                shape = RoundedCornerShape(8.dp)
            ) {
                Row(
                    modifier = Modifier.padding(12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.Center
                ) {
                    Icon(
                        imageVector = Icons.Default.CheckCircle,
                        contentDescription = null,
                        tint = MorpheColors.Teal,
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = "Using recommended version",
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Medium,
                        color = MorpheColors.Teal
                    )
                }
            }
        }

        // Non-recommended version (older or newer)
        !isRecommendedVersion -> {
            Surface(
                modifier = Modifier.fillMaxWidth(),
                color = Color(0xFFFF9800).copy(alpha = 0.1f),
                shape = RoundedCornerShape(8.dp)
            ) {
                Row(
                    modifier = Modifier.padding(12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        imageVector = Icons.Default.Info,
                        contentDescription = null,
                        tint = Color(0xFFFF9800),
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Column {
                        Text(
                            text = "Version $currentVersion",
                            fontSize = 13.sp,
                            fontWeight = FontWeight.Medium,
                            color = Color(0xFFFF9800)
                        )
                        Text(
                            text = "Recommended: v$suggestedVersion. Patching may have issues.",
                            fontSize = 11.sp,
                            color = Color(0xFFFF9800).copy(alpha = 0.8f)
                        )
                    }
                }
            }
        }

        // Checksum error
        checksumStatus is ChecksumStatus.Error -> {
            Surface(
                modifier = Modifier.fillMaxWidth(),
                color = Color(0xFFFF9800).copy(alpha = 0.1f),
                shape = RoundedCornerShape(8.dp)
            ) {
                Row(
                    modifier = Modifier.padding(12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.Center
                ) {
                    Icon(
                        imageVector = Icons.Default.CheckCircle,
                        contentDescription = null,
                        tint = Color(0xFFFF9800),
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = "Recommended version (checksum unavailable)",
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Medium,
                        color = Color(0xFFFF9800)
                    )
                }
            }
        }
    }
}

/**
 * Open native file picker.
 */
private fun openFilePicker(): File? {
    val chooser = JFileChooser().apply {
        dialogTitle = "Select APK"
        fileFilter = FileNameExtensionFilter("APK Files", "apk")
        isAcceptAllFileFilterUsed = false
    }

    return if (chooser.showOpenDialog(null) == JFileChooser.APPROVE_OPTION) {
        chooser.selectedFile
    } else null
}

private fun formatFileSize(bytes: Long): String {
    return when {
        bytes < 1024 -> "$bytes B"
        bytes < 1024 * 1024 -> "%.1f KB".format(bytes / 1024.0)
        bytes < 1024 * 1024 * 1024 -> "%.1f MB".format(bytes / (1024.0 * 1024.0))
        else -> "%.2f GB".format(bytes / (1024.0 * 1024.0 * 1024.0))
    }
}
