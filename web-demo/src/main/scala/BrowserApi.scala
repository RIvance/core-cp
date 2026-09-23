package cp.visualizer

import cp.fitrie.*
import cp.fitrie.evaluation.*
import cp.language.compilation.{CpFiTrieCompiler, CpSourceFile, SourcePath}
import cp.language.evaluation.CpEvaluator
import cp.language.{CompiledCpModule, CompiledCpProgram, Cp, CpCompilationError}
import cp.primitive.{BinaryOperator, PrimitiveType, PrimitiveValue}
import cp.util.Result
import cp.tooling.{CompilerDiagnostics, CompilerSourceFile}

import scala.scalajs.js
import scala.scalajs.js.annotation.{JSExport, JSExportTopLevel}

/** Browser-owned facade over CP compilation and immutable FiTrie sessions. */
@JSExportTopLevel("CpTrieWorkbench")
final class BrowserApi {
  private val maximumCoalescedReductions = 100
  private var compilation: Option[(CompiledCpProgram, CompiledCpModule)] = None
  private var initialSession: Option[EvaluationSession] = None
  private var initialStepNumber: Int = 0
  private var initialIsComplete: Boolean = false
  private var currentSession: Option[EvaluationSession] = None
  private var stepNumber: Int = 0

  /** Compile an immutable workspace without running either evaluator. */
  @JSExport
  def compile(files: js.Array[CompilerSourceFile], entryFile: String): js.Object = {
    clearSession()
    compilation = None
    val sourceFiles = files.toList.map(file => CpSourceFile(SourcePath(file.fileName), file.source))
    Cp.compileModules(sourceFiles) match {
      case Result.Err(error) => compilationFailure(error, sourceFiles)
      case Result.Ok(program) =>
        program.modules.values.find(_.sourceFile.path.value == entryFile) match {
          case None => failure("entry", "The selected entry file is not a CP module.")
          case Some(module) =>
            val entryPoint = module.namespace.identifier("main")
            module.definitions.get(entryPoint) match {
              case None => failure("entry", s"Define an ordinary `main` value in ${module.namespace.render}.")
              case Some(definition) =>
                compilation = Some((program, module))
                js.Dynamic.literal(
                  ok = true,
                  entryPoint = entryPoint.render,
                  elaboratedMainTerm = definition.sourceTerm.render(72)
                )
            }
        }
    }
  }

  /** Direct evaluation can be interrupted by terminating its owning worker. */
  @JSExport
  def evaluate(): js.Object = compilation match {
    case None => serializeFiobsEvaluation(FiobsEvaluationPresentation.Failure("Compile a CP workspace first."))
    case Some((program, module)) =>
      val result = CpEvaluator.evaluateFully(program, module.namespace) match {
        case Result.Ok(value) => FiobsEvaluationPresentation.success(value)
        case Result.Err(error) => FiobsEvaluationPresentation.failure(error)
      }
      serializeFiobsEvaluation(result)
  }

  /** A separate worker owns the interactive session, independently of direct evaluation. */
  @JSExport
  def start(): js.Object = compilation match {
    case None => failure("state", "Compile a CP workspace before starting evaluation.")
    case Some((program, module)) =>
      clearSession()
      CpFiTrieCompiler.compile(program, module.namespace) match {
        case Result.Err(error) => failure("FiTrie", CompilerDiagnostics.render(error))
        case Result.Ok(compiled) =>
          Evaluation.start(compiled.entry, compiled.globalEnvironment).flatMap { session =>
            advanceToVisibleState(
              session,
              FiTriePresentation.project(session.snapshot),
              maximumCoalescedReductions
            )
          } match {
            case Result.Err(error) => failure("evaluation", error.toString)
            case Result.Ok(VisibleAdvance(session, complete, reductions)) =>
              initialSession = Some(session)
              initialStepNumber = reductions
              initialIsComplete = complete
              currentSession = Some(session)
              stepNumber = reductions
              success(session, complete)
          }
      }
  }

  @JSExport
  def step(): js.Object = currentSession match {
    case None => failure("state", "Compile a CP module before stepping.")
    case Some(session) => advanceToVisibleState(
        session,
        FiTriePresentation.project(session.snapshot),
        maximumCoalescedReductions
      ) match {
      case Result.Err(error) => failure("evaluation", error.toString)
      case Result.Ok(VisibleAdvance(next, complete, reductions)) =>
        currentSession = Some(next)
        stepNumber += reductions
        success(next, isComplete = complete)
    }
  }

  @JSExport
  def restart(): js.Object = initialSession match {
    case None => failure("state", "Compile a CP module before restarting evaluation.")
    case Some(session) =>
      currentSession = Some(session)
      stepNumber = initialStepNumber
      success(session, isComplete = initialIsComplete)
  }

  private def clearSession(): Unit = {
    initialSession = None
    initialStepNumber = 0
    initialIsComplete = false
    currentSession = None
    stepNumber = 0
  }

  private def advanceToVisibleState(
    session: EvaluationSession,
    previousPresentation: EvaluationSnapshot,
    remainingReductions: Int,
    reductions: Int = 0
  ): Result[VisibleAdvance, EvaluationError] = {
    session.step match {
      case Result.Err(error) => Result.Err(error)
      case Result.Ok(EvaluationStep.Complete) =>
        Result.Ok(VisibleAdvance(session, complete = true, reductions))
      case Result.Ok(EvaluationStep.Progressed(next)) =>
        val nextReductions = reductions + 1
        val nextPresentation = FiTriePresentation.project(next.snapshot)
        if (
          remainingReductions <= 1 ||
          !FiTriePresentation.equivalent(previousPresentation, nextPresentation)
        ) {
          Result.Ok(VisibleAdvance(next, complete = false, nextReductions))
        } else {
          advanceToVisibleState(
            next,
            previousPresentation,
            remainingReductions - 1,
            nextReductions
          )
        }
    }
  }

  private def success(
    session: EvaluationSession,
    isComplete: Boolean
  ): js.Object = js.Dynamic.literal(
    ok = true,
    entryPoint = compilation.get._2.namespace.identifier("main").render,
    step = stepNumber,
    complete = isComplete,
    snapshot = serialize(FiTriePresentation.project(session.snapshot))
  )

  private def serializeFiobsEvaluation(result: FiobsEvaluationPresentation): js.Object = result match {
    case FiobsEvaluationPresentation.Success(value) => js.Dynamic.literal(
      ok = true,
      value = value
    )
    case FiobsEvaluationPresentation.Failure(message) => js.Dynamic.literal(
      ok = false,
      message = message
    )
  }

  private def compilationFailure(error: CpCompilationError, sources: List[CpSourceFile]): js.Object = {
    js.Dynamic.literal(ok = false, error = CompilerDiagnostics.compilationIssue(error, sources))
  }

  private def failure(phase: String, message: String): js.Object = js.Dynamic.literal(
    ok = false,
    error = js.Dynamic.literal(
      phase = phase,
      message = message,
      fileName = null,
      line = null,
      column = null,
      endLine = null,
      endColumn = null
    )
  )

  private def serialize(snapshot: EvaluationSnapshot): js.Object = js.Dynamic.literal(
    root = snapshot.root.value,
    nodes = js.Array(snapshot.nodes.map(serializeNode)*)
  )

  private def serializeNode(node: EvaluationTrieNode): js.Object = js.Dynamic.literal(
    id = node.id.value,
    responses = js.Array(node.responses.map(serializeResponse)*),
    routes = js.Array(node.routes.map(route => js.Dynamic.literal(
      label = renderRoute(route.routeKey),
      notation = renderPaperRoute(route.routeKey),
      target = route.target.value
    ))*),
    terminations = js.Array(node.terminations.map(termination => js.Dynamic.literal(
      key = renderPrimitiveType(termination.primitiveType),
      value = renderPrimitiveValue(termination.payload)
    ))*)
  )

  private def serializeResponse(response: EvaluationResponse): js.Object = response match {
    case EvaluationResponse.LocalVariable(index) => js.Dynamic.literal(
      kind = "local-variable",
      label = s"x${subscript(index.value)}",
      notation = s"x${subscript(index.value)}"
    )
    case EvaluationResponse.Global(identifier) => js.Dynamic.literal(
      kind = "global",
      label = s"global ${identifier.render}",
      notation = s"global ${identifier.render}"
    )
    case EvaluationResponse.StructuralReference(target) => js.Dynamic.literal(
      kind = "structural-reference",
      label = s"ref N${target.value}",
      target = target.value
    )
    case EvaluationResponse.Index(receiver, requests) => js.Dynamic.literal(
      kind = "index",
      label = s"index ${requests.map(renderRequest).mkString("⟨", ", ", "⟩")}",
      receiver = receiver.value,
      requests = js.Array(requests.map(serializeRequest)*)
    )
    case EvaluationResponse.Filter(receiver, selectedRootKeys) => js.Dynamic.literal(
      kind = "filter",
      label = s"filter ${renderRootKeyExpression(selectedRootKeys)}",
      receiver = receiver.value,
      selectedRootKeys = renderRootKeyExpression(selectedRootKeys),
      selectedRootKeyCount = finiteRootKeyCount(selectedRootKeys)
    )
    case EvaluationResponse.PrimitiveOperation(operator, left, right) => js.Dynamic.literal(
      kind = "primitive-operation",
      label = s"primitive ${renderOperator(operator)}",
      operator = renderOperator(operator),
      left = left.value,
      right = right.value
    )
    case EvaluationResponse.Conditional(condition, whenTrue, whenFalse) => js.Dynamic.literal(
      kind = "conditional",
      label = "if / then / else",
      condition = condition.value,
      whenTrue = whenTrue.value,
      whenFalse = whenFalse.value
    )
  }

  private def serializeRequest(request: EvaluationRequest): js.Object = request match {
    case EvaluationRequest.Application(argument) => js.Dynamic.literal(
      kind = "application",
      argument = argument.value
    )
    case EvaluationRequest.TypeApplication(pathInterface) => js.Dynamic.literal(
      kind = "type-application",
      pathInterface = pathInterface.toString
    )
    case EvaluationRequest.Projection(label) => js.Dynamic.literal(
      kind = "projection",
      label = label.value
    )
    case EvaluationRequest.Unfold => js.Dynamic.literal(kind = "unfold")
  }

  private def renderRequest(request: EvaluationRequest): String = request match {
    case EvaluationRequest.Application(argument) => s"ωᵃᵖᵖ[N${argument.value}]"
    case EvaluationRequest.TypeApplication(pathInterface) => s"ωᵗᵃᵖᵖ[$pathInterface]"
    case EvaluationRequest.Projection(label) => s"ωᵖʳᵒʲ_${label.value}"
    case EvaluationRequest.Unfold => "ωᵘⁿᶠᵒˡᵈ"
  }

  private def renderRootKeyExpression(expression: RootKeyExpression): String = expression match {
    case RootKeyExpression.Concrete(rootKeys) => renderRootKeySet(rootKeys)
    case RootKeyExpression.Front(pathInterface) => s"($pathInterface)•"
    case RootKeyExpression.Union(left, right) =>
      s"(${renderRootKeyExpression(left)} ∪ ${renderRootKeyExpression(right)})"
    case RootKeyExpression.Difference(left, right) =>
      s"(${renderRootKeyExpression(left)} ∖ ${renderRootKeyExpression(right)})"
  }

  private def finiteRootKeyCount(expression: RootKeyExpression): js.Any = {
    expression.normalize match {
      case Some(RootKeySet.Finite(rootKeys)) => rootKeys.size.asInstanceOf[js.Any]
      case _ => null
    }
  }

  private def renderRootKeySet(rootKeySet: RootKeySet): String = rootKeySet match {
    case RootKeySet.Universal => "𝕂"
    case RootKeySet.Finite(rootKeys) =>
      rootKeys.toVector.map(renderRootKey).sorted.mkString("⟨", ", ", "⟩")
    case RootKeySet.Cofinite(excludedRootKeys) =>
      excludedRootKeys.toVector.map(renderRootKey).sorted.mkString("𝕂 ∖ ⟨", ", ", "⟩")
  }

  private def renderRootKey(rootKey: RootKey): String = rootKey match {
    case RootKey.Route(routeKey) => renderRoute(routeKey)
    case RootKey.Termination(primitiveType) => renderPrimitiveType(primitiveType)
  }

  private def renderRoute(routeKey: RouteKey): String = routeKey match {
    case RouteKey.Application => "κᵃᵖᵖ"
    case RouteKey.TypeApplication => "κᵗᵃᵖᵖ"
    case RouteKey.Unfold => "κᵘⁿᶠᵒˡᵈ"
    case RouteKey.Projection(label) => s"κᵖʳᵒʲ_${label.value}"
  }

  private def renderPaperRoute(routeKey: RouteKey): String = routeKey match {
    case RouteKey.Application => "appₓ"
    case RouteKey.TypeApplication => "tappᵅ"
    case RouteKey.Unfold => "unfold"
    case RouteKey.Projection(label) => s"proj_${label.value}"
  }

  private def renderOperator(operator: BinaryOperator): String = operator match {
    case BinaryOperator.LessThanOrEqual => "≤"
    case BinaryOperator.GreaterThanOrEqual => "≥"
    case BinaryOperator.Equal => "="
    case BinaryOperator.NotEqual => "≠"
    case BinaryOperator.And => "∧"
    case BinaryOperator.Or => "∨"
    case _ => operator.symbol
  }

  private def subscript(value: Int): String = value.toString.map {
    case '0' => '₀'
    case '1' => '₁'
    case '2' => '₂'
    case '3' => '₃'
    case '4' => '₄'
    case '5' => '₅'
    case '6' => '₆'
    case '7' => '₇'
    case '8' => '₈'
    case '9' => '₉'
  }.mkString

  private def renderPrimitiveType(primitiveType: PrimitiveType): String = primitiveType match {
    case PrimitiveType.Integer => "int"
    case PrimitiveType.Decimal => "decimal"
    case PrimitiveType.Boolean => "bool"
    case PrimitiveType.Text => "string"
    case PrimitiveType.Unit => "unit"
  }

  private def renderPrimitiveValue(value: PrimitiveValue): String = value match {
    case PrimitiveValue.Integer(number) => number.toString
    case PrimitiveValue.Decimal(number) => number.toString
    case PrimitiveValue.Boolean(boolean) => boolean.toString
    case PrimitiveValue.Text(text) => s"\"${escapeText(text)}\""
    case PrimitiveValue.UnitValue => "()"
  }

  private def escapeText(text: String): String = text.flatMap {
    case '\\' => "\\\\"
    case '"' => "\\\""
    case '\n' => "\\n"
    case '\r' => "\\r"
    case '\t' => "\\t"
    case character => character.toString
  }
}

private final case class VisibleAdvance(
  session: EvaluationSession,
  complete: Boolean,
  reductions: Int
)
