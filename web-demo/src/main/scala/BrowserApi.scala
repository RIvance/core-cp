package cp.visualizer

import cp.fitrie.*
import cp.fitrie.evaluation.*
import cp.language.compilation.{CpFiTrieCompiler, CpSourceFile, SourcePath}
import cp.language.evaluation.CpEvaluator
import cp.language.parser.ParsingError
import cp.language.{Cp, CpCompilationError}
import cp.primitive.{BinaryOperator, PrimitiveType, PrimitiveValue}
import cp.source.ResolvedSourceSpan
import cp.util.Result

import scala.scalajs.js
import scala.scalajs.js.annotation.{JSExport, JSExportTopLevel}

/** Browser-owned facade over CP compilation and immutable FiTrie sessions. */
@JSExportTopLevel("CpTrieWorkbench")
final class BrowserApi {
  private val maximumCoalescedReductions = 100
  private var initialSession: Option[EvaluationSession] = None
  private var initialStepNumber: Int = 0
  private var initialIsComplete: Boolean = false
  private var currentSession: Option[EvaluationSession] = None
  private var stepNumber: Int = 0
  private var elaboratedMainTerm: Option[String] = None
  private var fiobsEvaluation: Option[FiobsEvaluationPresentation] = None

  @JSExport
  def compile(source: String, fileName: String): js.Object = {
    val sourceFile = CpSourceFile(SourcePath(fileName), source)
    Cp.compileModules(List(sourceFile)) match {
      case Result.Err(error) =>
        clearSession()
        compilationFailure(error, source)
      case Result.Ok(compiledCpProgram) =>
        val targetNamespace = compiledCpProgram.modules.keys.head
        val evaluatedFiobs = CpEvaluator.evaluateFully(compiledCpProgram, targetNamespace) match {
          case Result.Ok(value) => FiobsEvaluationPresentation.success(value)
          case Result.Err(error) => FiobsEvaluationPresentation.failure(error)
        }
        CpFiTrieCompiler.compile(compiledCpProgram, targetNamespace) match {
          case Result.Err(error) =>
            clearSession()
            failure(
              "FiTrie",
              CompilerDiagnostics.render(error),
              directFiobsResult = Some(evaluatedFiobs)
            )
          case Result.Ok(compiledFiTrieProgram) =>
            val mainTerm = compiledCpProgram.modules(targetNamespace)
              .definitions(compiledFiTrieProgram.entryPoint)
              .sourceTerm
            Evaluation.start(
              compiledFiTrieProgram.entry,
              compiledFiTrieProgram.globalEnvironment
            ) match {
              case Result.Err(error) =>
                clearSession()
                failure(
                  "evaluation",
                  error.toString,
                  directFiobsResult = Some(evaluatedFiobs)
                )
              case Result.Ok(session) =>
                advanceToVisibleState(
                  session,
                  FiTriePresentation.project(session.snapshot),
                  maximumCoalescedReductions
                ) match {
                  case Result.Err(error) =>
                    clearSession()
                    failure(
                      "evaluation",
                      error.toString,
                      directFiobsResult = Some(evaluatedFiobs)
                    )
                  case Result.Ok(VisibleAdvance(visibleSession, complete, reductions)) =>
                    initialSession = Some(visibleSession)
                    initialStepNumber = reductions
                    initialIsComplete = complete
                    currentSession = Some(visibleSession)
                    stepNumber = reductions
                    elaboratedMainTerm = Some(mainTerm.render(72))
                    fiobsEvaluation = Some(evaluatedFiobs)
                    success(
                      visibleSession,
                      compiledFiTrieProgram.entryPoint.render,
                      complete
                    )
                }
            }
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
      case Result.Err(error) => failure(
          "evaluation",
          error.toString,
          directFiobsResult = fiobsEvaluation
        )
      case Result.Ok(VisibleAdvance(next, complete, reductions)) =>
        currentSession = Some(next)
        stepNumber += reductions
        success(next, entryPoint = "", isComplete = complete)
    }
  }

  @JSExport
  def restart(): js.Object = initialSession match {
    case None => failure("state", "Compile a CP module before restarting evaluation.")
    case Some(session) =>
      currentSession = Some(session)
      stepNumber = initialStepNumber
      success(session, entryPoint = "", isComplete = initialIsComplete)
  }

  private def clearSession(): Unit = {
    initialSession = None
    initialStepNumber = 0
    initialIsComplete = false
    currentSession = None
    stepNumber = 0
    elaboratedMainTerm = None
    fiobsEvaluation = None
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
    entryPoint: String,
    isComplete: Boolean
  ): js.Object = js.Dynamic.literal(
    ok = true,
    entryPoint = entryPoint,
    elaboratedMainTerm = elaboratedMainTerm.getOrElse(""),
    fiobsResult = serializeFiobsEvaluation(fiobsEvaluation.getOrElse {
      throw new IllegalStateException("successful workbench state has no Fiobs evaluation")
    }),
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

  private def compilationFailure(error: CpCompilationError, source: String): js.Object = error match {
    case CpCompilationError.Parsing(_, ParsingError.Syntax(message, line, column)) =>
      failure("parse", message, Some(BrowserSourceRange.point(line, column)))
    case other =>
      val sourceRange = CompilerDiagnostics.sourceSpan(other)
        .flatMap(_.resolveIn(source))
        .map(BrowserSourceRange.from)
      failure("compile", CompilerDiagnostics.render(other), sourceRange)
  }

  private def failure(
    phase: String,
    message: String,
    sourceRange: Option[BrowserSourceRange] = None,
    directFiobsResult: Option[FiobsEvaluationPresentation] = None
  ): js.Object = {
    val (line, column, endLine, endColumn) = sourceRange match {
      case Some(range) => (
          range.startLine.asInstanceOf[js.Any],
          range.startColumn.asInstanceOf[js.Any],
          range.endLine.asInstanceOf[js.Any],
          range.endColumn.asInstanceOf[js.Any]
        )
      case None => (null, null, null, null)
    }
    js.Dynamic.literal(
      ok = false,
      fiobsResult = directFiobsResult match {
        case Some(result) => serializeFiobsEvaluation(result)
        case None => null
      },
      error = js.Dynamic.literal(
        phase = phase,
        message = message,
        line = line,
        column = column,
        endLine = endLine,
        endColumn = endColumn
      )
    )
  }

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

private final case class BrowserSourceRange(
  startLine: Int,
  startColumn: Int,
  endLine: Int,
  endColumn: Int
)

private object BrowserSourceRange {
  def point(line: Int, column: Int): BrowserSourceRange = {
    BrowserSourceRange(line, column, line, column + 1)
  }

  def from(sourceSpan: ResolvedSourceSpan): BrowserSourceRange = BrowserSourceRange(
    sourceSpan.start.line,
    sourceSpan.start.column,
    sourceSpan.end.line,
    sourceSpan.end.column
  )
}
