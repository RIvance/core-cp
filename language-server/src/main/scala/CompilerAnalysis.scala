package cp.tooling

import cp.language.Cp
import cp.language.compilation.{CpSourceFile, SourcePath}
import cp.util.Result

import scala.scalajs.js
import scala.scalajs.js.annotation.{JSExport, JSExportTopLevel}

/** Source filenames retain CP's normal module identity rules. */
@js.native
trait CompilerSourceFile extends js.Object {
  val fileName: String = js.native
  val source: String = js.native
}

/** Analysis uses the same public compiler as execution and never evaluates a program. */
@JSExportTopLevel("CpAnalysis")
final class CompilerAnalysis {
  @JSExport
  def check(files: js.Array[CompilerSourceFile]): js.Object | Null = {
    val sources = files.toList.map(file => CpSourceFile(SourcePath(file.fileName), file.source))
    Cp.compileModules(sources) match {
      case Result.Ok(_) => null
      case Result.Err(error) => CompilerDiagnostics.compilationIssue(error, sources)
    }
  }
}
