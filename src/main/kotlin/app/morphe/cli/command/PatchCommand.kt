package app.morphe.cli.command

import app.morphe.cli.command.model.PatchingResult
import app.morphe.cli.command.model.PatchingStep
import app.morphe.cli.command.model.PatchingStepResult
import app.morphe.cli.command.model.addStepResult
import app.morphe.cli.command.model.toSerializablePatch
import app.morphe.engine.PatchEngine
import app.morphe.library.ApkUtils
import app.morphe.library.installation.installer.*
import app.morphe.patcher.patch.loadPatchesFromJar
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.encodeToStream
import picocli.CommandLine
import picocli.CommandLine.ArgGroup
import picocli.CommandLine.Help.Visibility.ALWAYS
import picocli.CommandLine.Model.CommandSpec
import picocli.CommandLine.Spec
import java.io.File
import java.util.logging.Logger
import app.morphe.cli.command.model.FailedPatch as CliFailedPatch

@OptIn(ExperimentalSerializationApi::class)
@CommandLine.Command(
    name = "patch",
    description = ["Patch an APK file."],
)
internal object PatchCommand : Runnable {
    private val logger = Logger.getLogger(this::class.java.name)

    @Spec
    private lateinit var spec: CommandSpec

    @ArgGroup(exclusive = false, multiplicity = "0..*")
    private var selection = mutableSetOf<Selection>()

    internal class Selection {
        @ArgGroup(exclusive = false)
        internal var enabled: EnableSelection? = null

        internal class EnableSelection {
            @ArgGroup(multiplicity = "1")
            internal lateinit var selector: EnableSelector

            internal class EnableSelector {
                @CommandLine.Option(
                    names = ["-e", "--enable"],
                    description = ["Name of the patch."],
                    required = true,
                )
                internal var name: String? = null

                @CommandLine.Option(
                    names = ["--ei"],
                    description = ["Index of the patch in the combined list of the supplied MPP files."],
                    required = true,
                )
                internal var index: Int? = null
            }

            @CommandLine.Option(
                names = ["-O", "--options"],
                description = ["Option values keyed by option keys."],
                mapFallbackValue = CommandLine.Option.NULL_VALUE,
                converter = [OptionKeyConverter::class, OptionValueConverter::class],
            )
            internal var options = mutableMapOf<String, Any?>()
        }

        @ArgGroup(exclusive = false)
        internal var disable: DisableSelection? = null

        internal class DisableSelection {
            @ArgGroup(multiplicity = "1")
            internal lateinit var selector: DisableSelector

            internal class DisableSelector {
                @CommandLine.Option(
                    names = ["-d", "--disable"],
                    description = ["Name of the patch."],
                    required = true,
                )
                internal var name: String? = null

                @CommandLine.Option(
                    names = ["--di"],
                    description = ["Index of the patch in the combined list of the supplied MPP files."],
                    required = true,
                )
                internal var index: Int? = null
            }
        }
    }

    @CommandLine.Option(
        names = ["--exclusive"],
        description = ["Disable all patches except the ones enabled."],
        showDefaultValue = ALWAYS,
    )
    private var exclusive = false

    @CommandLine.Option(
        names = ["-f", "--force"],
        description = ["Don't check for compatibility with the supplied APK's version."],
        showDefaultValue = ALWAYS,
    )
    private var force: Boolean = false

    private var outputFilePath: File? = null

    @CommandLine.Option(
        names = ["-o", "--out"],
        description = ["Path to save the patched APK file to. Defaults to the same path as the supplied APK file."],
    )
    @Suppress("unused")
    private fun setOutputFilePath(outputFilePath: File?) {
        this.outputFilePath = outputFilePath?.absoluteFile
    }

    private var patchingResultOutputFilePath: File? = null

    @CommandLine.Option(
        names = ["-r", "--result-file"],
        description = ["Path to save the patching result file to"],
    )
    @Suppress("unused")
    private fun setPatchingResultOutputFilePath(outputFilePath: File?) {
        this.patchingResultOutputFilePath = outputFilePath?.absoluteFile
    }

    @CommandLine.Option(
        names = ["-i", "--install"],
        description = ["Serial of the ADB device to install to. If not specified, the first connected device will be used."],
        // Empty string to indicate that the first connected device should be used.
        fallbackValue = "",
        arity = "0..1",
    )
    private var deviceSerial: String? = null

    @CommandLine.Option(
        names = ["--mount"],
        description = ["Install the patched APK file by mounting."],
        showDefaultValue = ALWAYS,
    )
    private var mount: Boolean = false

    @CommandLine.Option(
        names = ["--keystore"],
        description = [
            "Path to the keystore file containing a private key and certificate pair to sign the patched APK file with. " +
                "Defaults to the same directory as the supplied APK file.",
        ],
    )
    private var keyStoreFilePath: File? = null

    @CommandLine.Option(
        names = ["--keystore-password"],
        description = ["Password of the keystore. Empty password by default."],
    )
    private var keyStorePassword: String? = null // Empty password by default

    @CommandLine.Option(
        names = ["--keystore-entry-alias"],
        description = ["Alias of the private key and certificate pair keystore entry."],
        showDefaultValue = ALWAYS,
    )
    private var keyStoreEntryAlias = "Morphe Key"

    @CommandLine.Option(
        names = ["--keystore-entry-password"],
        description = ["Password of the keystore entry."],
    )
    private var keyStoreEntryPassword = "" // Empty password by default

    @CommandLine.Option(
        names = ["--signer"],
        description = ["The name of the signer to sign the patched APK file with."],
        showDefaultValue = ALWAYS,
    )
    private var signer = "Morphe"

    @CommandLine.Option(
        names = ["-t", "--temporary-files-path"],
        description = ["Path to store temporary files."],
    )
    private var temporaryFilesPath: File? = null

    private var aaptBinaryPath: File? = null

    @CommandLine.Option(
        names = ["--purge"],
        description = ["Purge temporary files directory after patching."],
        showDefaultValue = ALWAYS,
    )
    private var purge: Boolean = false

    @CommandLine.Parameters(
        description = ["APK file to patch."],
        arity = "1",
    )
    @Suppress("unused")
    private fun setApk(apk: File) {
        if (!apk.exists()) {
            throw CommandLine.ParameterException(
                spec.commandLine(),
                "APK file ${apk.path} does not exist",
            )
        }
        this.apk = apk
    }

    private lateinit var apk: File

    @CommandLine.Option(
        names = ["-p", "--patches"],
        description = ["One or more path to MPP files."],
        required = true,
    )
    @Suppress("unused")
    private fun setPatchesFile(patchesFiles: Set<File>) {
        patchesFiles.firstOrNull { !it.exists() }?.let {
            throw CommandLine.ParameterException(spec.commandLine(), "${it.name} can't be found")
        }
        this.patchesFiles = patchesFiles
    }

    private var patchesFiles = emptySet<File>()

    @CommandLine.Option(
        names = ["--custom-aapt2-binary"],
        description = ["Path to a custom AAPT binary to compile resources with."],
    )
    @Suppress("unused")
    private fun setAaptBinaryPath(aaptBinaryPath: File) {
        if (!aaptBinaryPath.exists()) {
            throw CommandLine.ParameterException(
                spec.commandLine(),
                "AAPT binary ${aaptBinaryPath.name} does not exist",
            )
        }
        this.aaptBinaryPath = aaptBinaryPath
    }

    @CommandLine.Option(
        names = ["--unsigned"],
        description = ["Disable signing of the final apk."],
    )
    private var unsigned: Boolean = false

    @CommandLine.Option(
        names = ["--striplibs"],
        description = ["Architectures to keep, comma-separated (e.g. arm64-v8a,x86). Strips all other native architectures."],
        split = ",",
    )
    private var striplibs: List<String> = emptyList()

    override fun run() {
        val outputFilePath =
            outputFilePath ?: File("").absoluteFile.resolve(
                "${apk.nameWithoutExtension}-patched.apk",
            )

        val temporaryFilesPath =
            temporaryFilesPath ?: outputFilePath.parentFile.resolve(
                "${outputFilePath.nameWithoutExtension}-temporary-files",
            )

        val keystoreFilePath =
            keyStoreFilePath ?: outputFilePath.parentFile
                .resolve("${outputFilePath.nameWithoutExtension}.keystore")

        // Set up ADB installer (CLI-only)
        val installer = if (deviceSerial != null) {
            val deviceSerial = deviceSerial!!.ifEmpty { null }

            try {
                if (mount) {
                    AdbRootInstaller(deviceSerial)
                } else {
                    AdbInstaller(deviceSerial)
                }
            } catch (e: DeviceNotFoundException) {
                if (deviceSerial?.isNotEmpty() == true) {
                    logger.severe(
                        "Device with serial $deviceSerial not found to install to. " +
                            "Ensure the device is connected and the serial is correct when using the --install option.",
                    )
                } else {
                    logger.severe(
                        "No device has been found to install to. " +
                            "Ensure a device is connected when using the --install option.",
                    )
                }

                return
            }
        } else {
            null
        }

        // Resolve --ei/--di indices to patch names by pre-loading patches
        val patchesList = loadPatchesFromJar(patchesFiles).toList()

        val enabledPatchNames = selection.mapNotNull { sel ->
            sel.enabled?.let { en ->
                en.selector.name ?: patchesList.getOrNull(en.selector.index!!)?.name
            }
        }.toSet()

        val disabledPatchNames = selection.mapNotNull { sel ->
            sel.disable?.let { dis ->
                dis.selector.name ?: patchesList.getOrNull(dis.selector.index!!)?.name
            }
        }.filterNotNull().toSet()

        // Build options map: Map<patchName, Map<optionKey, value>>
        val patchOptions = selection.filter { it.enabled != null }
            .associate { sel ->
                val en = sel.enabled!!
                val name = en.selector.name ?: patchesList[en.selector.index!!].name!!
                name to en.options.toMap()
            }
            .filter { it.value.isNotEmpty() }

        val config = PatchEngine.Config(
            inputApk = apk,
            patches = patchesList.toSet(),
            outputApk = outputFilePath,
            enabledPatches = enabledPatchNames,
            disabledPatches = disabledPatchNames,
            exclusiveMode = exclusive,
            forceCompatibility = force,
            patchOptions = patchOptions,
            unsigned = mount || unsigned,
            signerName = signer,
            keystoreDetails = ApkUtils.KeyStoreDetails(
                keystoreFilePath,
                keyStorePassword,
                keyStoreEntryAlias,
                keyStoreEntryPassword,
            ),
            architecturesToKeep = striplibs,
            aaptBinaryPath = aaptBinaryPath,
            tempDir = temporaryFilesPath,
        )

        val patchingResult = PatchingResult()

        try {
            val engineResult = runBlocking {
                PatchEngine.patch(config) { msg -> logger.info(msg) }
            }

            patchingResult.packageName = engineResult.packageName
            patchingResult.packageVersion = engineResult.packageVersion
            patchingResult.success = engineResult.success

            // Map engine step results to CLI model for --result-file
            engineResult.stepResults.forEach { step ->
                val cliStep = when (step.step) {
                    PatchEngine.PatchStep.PATCHING -> PatchingStep.PATCHING
                    PatchEngine.PatchStep.REBUILDING -> PatchingStep.REBUILDING
                    PatchEngine.PatchStep.STRIPPING_LIBS -> PatchingStep.STRIPPING_LIBS
                    PatchEngine.PatchStep.SIGNING -> PatchingStep.SIGNING
                }
                patchingResult.patchingSteps.add(PatchingStepResult(cliStep, step.success, step.error))
            }

            engineResult.appliedPatches.forEach { name ->
                patchesList.find { it.name == name }?.let {
                    patchingResult.appliedPatches.add(it.toSerializablePatch())
                }
            }
            engineResult.failedPatches.forEach { failed ->
                patchesList.find { it.name == failed.name }?.let {
                    patchingResult.failedPatches.add(CliFailedPatch(it.toSerializablePatch(), failed.error))
                }
            }

            logger.info("Saved to $outputFilePath")

            // ADB install (CLI-only)
            if (engineResult.success) {
                deviceSerial?.let {
                    patchingResult.addStepResult(
                        PatchingStep.INSTALLING,
                        {
                            runBlocking {
                                val result = installer!!.install(
                                    Installer.Apk(outputFilePath, engineResult.packageName),
                                )
                                when (result) {
                                    RootInstallerResult.FAILURE -> {
                                        logger.severe("Failed to mount the patched APK file")
                                        throw IllegalStateException("Failed to mount the patched APK file")
                                    }
                                    is AdbInstallerResult.Failure -> {
                                        logger.severe(result.exception.toString())
                                        throw result.exception
                                    }
                                    else -> logger.info("Installed the patched APK file")
                                }
                            }
                        },
                    )
                }
            }
        } finally {
            patchingResultOutputFilePath?.let { outputFile ->
                outputFile.outputStream().use { outputStream ->
                    Json.encodeToStream(patchingResult, outputStream)
                }
                logger.info("Patching result saved to $outputFile")
            }
        }

        if (purge) {
            logger.info("Purging temporary files")
            val result =
                if (temporaryFilesPath.deleteRecursively()) {
                    "Purged resource cache directory"
                } else {
                    "Failed to purge resource cache directory"
                }
            logger.info(result)
        }
    }
}
