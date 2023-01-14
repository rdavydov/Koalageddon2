package acidicoala.koalageddon.steam.domain.use_case

import acidicoala.koalageddon.core.logging.AppLogger
import acidicoala.koalageddon.core.model.ISA
import acidicoala.koalageddon.core.model.InstallationChecklist
import acidicoala.koalageddon.core.model.Store
import acidicoala.koalageddon.core.serialization.json
import com.sun.jna.Memory
import com.sun.jna.platform.win32.Version
import com.sun.jna.ptr.IntByReference
import com.sun.jna.ptr.PointerByReference
import dorkbox.peParser.PE
import dorkbox.peParser.misc.MagicNumberType
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import org.kodein.di.DI
import org.kodein.di.DIAware
import org.kodein.di.instance
import java.nio.file.Path
import kotlin.io.path.Path
import kotlin.io.path.div
import kotlin.io.path.exists

class GetInstallationChecklist(override val di: DI) : DIAware {

    @Serializable
    data class Module(
        val path: String = "",
        val required: Boolean = true,
    )

    @Serializable
    data class KoaloaderConfig(
        val logging: Boolean = false,
        val enabled: Boolean = true,
        @SerialName("auto_load")
        val autoLoad: Boolean = true,
        val targets: List<String> = listOf(),
        val modules: List<Module> = listOf(),
    )

    private val logger: AppLogger by instance()

    operator fun invoke(store: Store): InstallationChecklist {
        var checklist = InstallationChecklist()

        try {
            checkLoaderDll(store).let { loaderDll ->
                checklist = checklist.copy(loaderDll = loaderDll)

                if (!loaderDll) {
                    return checklist
                }
            }

            val loaderConfigResult = checkLoaderConfig(store)
            val unlockerPath = when {
                loaderConfigResult.isSuccess -> {
                    checklist = checklist.copy(loaderConfig = true)
                    loaderConfigResult.getOrThrow()
                }
                else -> {
                    logger.debug(loaderConfigResult.exceptionOrNull()?.message ?: "Unknown loader config error")
                    return checklist.copy(loaderConfig = false)
                }
            }

            checkUnlockerDll(store, unlockerPath).let { unlockerDll ->
                checklist = checklist.copy(unlockerDll = unlockerDll)

                if (!unlockerDll) {
                    return checklist
                }
            }

            checkUnlockerConfig(store, unlockerPath).let { unlockerConfig ->
                checklist = checklist.copy(unlockerConfig = unlockerConfig)

                if (!unlockerConfig) {
                    return checklist
                }
            }

            return checklist
        } catch (e: Exception) {
            logger.error(e, "Error getting installation checklist")
            return checklist
        }
    }


    private fun checkLoaderDll(store: Store): Boolean = store.path.toFile().listFiles()
        ?.filter { it.extension.equals("dll", ignoreCase = true) }
        ?.any { file ->
            Version.INSTANCE.run {
                val pe = PE(file.absolutePath)

                if (!pe.matches(store.isa)) {
                    return@any false
                }

                when (val bufferSize = GetFileVersionInfoSize(file.absolutePath, null)) {
                    0 -> {
                        false
                    }
                    else -> {
                        val buffer = Memory(bufferSize.toLong())

                        if (!GetFileVersionInfo(file.absolutePath, 0, bufferSize, buffer)) {
                            return@any false
                        }

                        val productNamePointer = PointerByReference()
                        val productNameSize = IntByReference()

                        if (
                            !VerQueryValue(
                                buffer,
                                """\StringFileInfo\040904E4\ProductName""",
                                productNamePointer,
                                productNameSize
                            )
                        ) {
                            return@any false
                        }

                        val productName = productNamePointer.value.getWideString(0)

                        val isKoaloader = productName.equals("Koaloader", ignoreCase = false)

                        if (!isKoaloader) {
                            return@any false
                        }

                        if (file.name.equals("version.dll", ignoreCase = true)) {
                            logger.debug("""Found Koaloader DLL at "${file.absolutePath}"""")
                            return@any true
                        } else {
                            logger.debug(
                                """Found unexpected Koaloader DLL at "${file.absolutePath}". """ +
                                        "Please consider deleting it manually because " +
                                        "it might interfere with Koalageddon integration"
                            )
                            return@any false
                        }
                    }
                }
            }
        } ?: false

    /**
     * @return Path to an unlocker DLL
     */
    private fun checkLoaderConfig(store: Store): Result<Path> {
        val configFile = store.path.toFile().listFiles()
            ?.find { it.name.equals("Koaloader.config.json", ignoreCase = true) }
            ?: return Result.failure(Exception("Koalageddon config not found"))

        logger.debug("""Found Koalageddon config at "${configFile.absolutePath}"""")

        val config = try {
            json.decodeFromString<KoaloaderConfig>(configFile.readText())
        } catch (e: Exception) {
            return Result.failure(Exception("Failed to parse Koaloader config", e))
        }

        if (!config.enabled) {
            return Result.failure(Exception("Koaloader config is disabled"))
        }

        if (config.autoLoad) {
            return Result.failure(Exception("Koaloader config is should not be set to autoload"))
        }

        if (config.targets.size != 1 || !config.targets.first().equals(store.executable, ignoreCase = true)) {
            return Result.failure(Exception("Koaloader config target is misconfigured"))
        }

        val unlockerPath = config.modules
            .map { Path(it.path).let { path -> if (path.isAbsolute) path else store.path / path } }
            .find { path -> path.fileName.toString().equals(store.unlocker.dllName, ignoreCase = true) }
            ?: return Result.failure(Exception("Koaloader config module is misconfigured"))

        return Result.success(unlockerPath)
    }

    private fun checkUnlockerDll(store: Store, unlockerPath: Path): Boolean {
        if (!unlockerPath.exists()) {
            logger.debug("""Unlocker DLL not found at "$unlockerPath"""")
            return false
        }

        logger.debug("""Unlocker DLL found at "$unlockerPath"""")

        val pe = PE(unlockerPath.toString())
        if (!pe.matches(store.isa)) {
            logger.debug("Found Unlocker DLL with incompatible architecture")
            return false
        }

        return true
    }

    private fun checkUnlockerConfig(store: Store, unlockerPath: Path): Boolean {
        val configPath = unlockerPath.parent / store.unlocker.configName

        if (!configPath.exists()) {
            logger.debug("""Unlocker config not found at "$configPath"""")
            return false
        }

        logger.debug("""Unlocker config found at "$configPath"""")

        try {
            store.unlocker.parseConfig(configPath)
        } catch (e: Exception) {
            logger.error(e, "Error parsing the unlocker config")
            return false
        }

        return true
    }

    private fun PE.matches(isa: ISA): Boolean {
        if (!isPE) {
            return false
        }

        val magicNumber = optionalHeader.MAGIC_NUMBER.get()

        return when (isa) {
            ISA.X86 -> magicNumber == MagicNumberType.PE32
            ISA.X86_64 -> magicNumber != MagicNumberType.PE32
        }
    }

}