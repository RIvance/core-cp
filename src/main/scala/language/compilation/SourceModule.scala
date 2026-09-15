package cp.language.compilation

import cp.language.core.Module
import cp.naming.Namespace
import cp.util.Result

import java.nio.file.Path

/** Platform-neutral source identity used by compilation and diagnostics. */
final case class SourcePath(value: String) {
  require(value.nonEmpty, "a source path cannot be empty")

  def fileName: String = {
    val normalized = value.replace('\\', '/')
    normalized.substring(normalized.lastIndexOf('/') + 1)
  }

  override def toString: String = value
}

/** Source text paired with the filename needed for module identity and diagnostics. */
final case class CpSourceFile(path: SourcePath, contents: String) {
  def fileName: String = path.fileName
}

object CpSourceFile {
  /** JVM filesystem boundary; core compilation stores only the portable path value. */
  def apply(path: Path, contents: String): CpSourceFile = {
    new CpSourceFile(SourcePath(path.toString), contents)
  }
}

enum ModuleSourceError {
  case ModuleIdentityUnavailable
  case InvalidSourceFileExtension(path: SourcePath)
  case InvalidModuleFileName(path: SourcePath)
}

final case class IdentifiedSourceModule(
  sourceFile: CpSourceFile,
  sourceModule: Module,
  namespace: Namespace
)

object IdentifiedSourceModule {
  private val PascalCaseModuleName = "[A-Z][A-Za-z0-9]*".r

  def create(
    sourceFile: CpSourceFile,
    sourceModule: Module
  ): Result[IdentifiedSourceModule, ModuleSourceError] = {
    moduleNameFrom(sourceFile.path).map { fileModuleName =>
      IdentifiedSourceModule(
        sourceFile,
        sourceModule,
        sourceModule.declaredNamespace.getOrElse(Namespace(fileModuleName))
      )
    }
  }

  private def moduleNameFrom(path: SourcePath): Result[String, ModuleSourceError] = {
    val fileName = path.fileName
    if (!fileName.endsWith(".cp")) {
      Result.Err(ModuleSourceError.InvalidSourceFileExtension(path))
    } else {
      val moduleName = fileName.stripSuffix(".cp")
      moduleName match {
        case PascalCaseModuleName() => Result.Ok(moduleName)
        case _ => Result.Err(ModuleSourceError.InvalidModuleFileName(path))
      }
    }
  }
}
