package cp.language.elaboration

import cp.fiobs.Term
import cp.fiobs.binding.Binding.*
import cp.language.typing.Type
import cp.naming.Identifier
import cp.util.{Graph, Result, Sha256}

import java.nio.charset.StandardCharsets

/** Lowers recursive components using resolved globals; lexical names never participate in substitution. */
private[elaboration] object RecursiveDefinitions {
  def lower(
    definitions: Map[Identifier, ElaboratedTermDefinition],
    annotated: Set[Identifier]
  ): Result[Map[Identifier, ElaboratedTermDefinition], CpElaborationError] = {
    val graph = definitions.values.foldLeft(Graph.directed[Identifier].addVertices(definitions.keys)) {
      (graph, definition) => definition.initializer.referencedGlobals.intersect(definitions.keySet)
        .foldLeft(graph)((current, dependency) => current.addEdge(dependency, definition.identifier))
    }
    graph.stronglyConnectedComponents.toList.foldLeft(
      Result.Ok(definitions): Result[Map[Identifier, ElaboratedTermDefinition], CpElaborationError]
    ) { (accumulated, identifiers) =>
      accumulated.flatMap { current =>
        val component = identifiers.toList.sortBy(_.name).map(definitions)
        val recursive = component.size > 1 || identifiers.exists(graph.isSelfLoop)
        if (!recursive) Result.Ok(current)
        else component.find(definition => !annotated.contains(definition.identifier)) match {
          case Some(definition) =>
            Result.Err(CpElaborationError.RecursiveDeclarationRequiresType(definition.identifier.name))
          case None => component match {
            // Ω, g : A ; Δ ; Γ ⊢ E ⇐ A ↝ e    g ∈ globals(e)
            // ───────────────────────────────────────────────────────── E-TermDecl-Rec
            // Ω ; Δ ⊢ def g : A = E ↝ global g : A = fix(x : ⟦A⟧). e[g ↦ x]
            case definition :: Nil =>
              val initializer = Term.Fix(
                TypeTranslation.toFiobs(definition.definitionType),
                definition.initializer.substituteGlobals(Map(definition.identifier -> Term.Variable(0)))
              )
              Result.Ok(current.updated(definition.identifier, definition.copy(initializer = initializer)))
            case _ => Result.Ok(lowerMutual(component, current))
          }
        }
      }
    }
  }

  private def lowerMutual(
    component: List[ElaboratedTermDefinition],
    definitions: Map[Identifier, ElaboratedTermDefinition]
  ): Map[Identifier, ElaboratedTermDefinition] = {
    // C = {gᵢ : Aᵢ = eᵢ}ⁱ∈ᴵ    |I| > 1    R = ⋀ᵢ{gᵢ : Aᵢ}
    // eᵢ′ = eᵢ[gⱼ ↦ (s.gⱼ : ⟦Aⱼ⟧)]ʲ∈ᴵ    m fresh
    // ───────────────────────────────────────────────────────────────────── E-TermDecl-Mutual
    // C ↝ global m : R = fix(s : ⟦R⟧). {ḡᵢ = eᵢ′}ⁱ∈ᴵ ; global ḡᵢ : Aᵢ = (m.gᵢ : ⟦Aᵢ⟧)ⁱ∈ᴵ
    val bundle = freshBundleIdentifier(component, definitions.keySet)
    val fields = component.map(definition => definition.identifier.name -> definition.definitionType)
    val recordType = Type.records(fields.head, fields.tail)
    def projection(receiver: Term, definition: ElaboratedTermDefinition): Term = Term.Annotation(
      Term.Projection(receiver, definition.identifier.name), TypeTranslation.toFiobs(definition.definitionType)
    )
    val replacements = component.map { definition =>
      definition.identifier -> projection(Term.Variable(0), definition)
    }.toMap
    val record = component.map { definition =>
      Term.Record(definition.identifier.name, definition.initializer.substituteGlobals(replacements))
    }.reduceLeft[Term](Term.Merge(_, _))
    val bundleDefinition = ElaboratedTermDefinition(
      bundle, Term.Fix(TypeTranslation.toFiobs(recordType), record), recordType, ModuleDefinitionVisibility.Internal
    )
    definitions.updated(bundle, bundleDefinition) ++ component.map { definition =>
      definition.identifier -> definition.copy(initializer = projection(Term.Global(bundle), definition))
    }
  }

  private def freshBundleIdentifier(
    component: List[ElaboratedTermDefinition],
    unavailable: Set[Identifier]
  ): Identifier = {
    val namespace = component.head.identifier.scope
    val bytes = component.flatMap { definition =>
      val name = definition.identifier.name.getBytes(StandardCharsets.UTF_8)
      List(
        (name.length >>> 24).toByte, (name.length >>> 16).toByte, (name.length >>> 8).toByte, name.length.toByte
      ) ++ name
    }.toArray
    val digest = Sha256.hexDigest(bytes)
    Iterator.from(0).map { suffix =>
      namespace.identifier(s"$$mutual_$digest" + (if (suffix == 0) "" else s"_$suffix"))
    }.find(identifier => !unavailable.contains(identifier)).getOrElse {
      throw new IllegalStateException("the infinite fresh-identifier stream was exhausted")
    }
  }
}
