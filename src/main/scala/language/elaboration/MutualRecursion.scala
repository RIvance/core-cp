package cp.language.elaboration

import cp.language.core.{Module, TermDefinitionComponent}
import cp.naming.Namespace
import cp.util.Sha256

import java.nio.charset.StandardCharsets

private[elaboration] final case class GeneratedMutualNames(
  definitionName: String,
  selfName: String
)

private[elaboration] final case class MutualRecursionPlan(
  generatedNamesByComponent: Map[Set[String], GeneratedMutualNames]
) {
  def generatedNamesFor(component: TermDefinitionComponent): GeneratedMutualNames = {
    generatedNamesByComponent(component.definitionNames)
  }
}

private[elaboration] object MutualRecursionPlan {
  def apply(module: Module, namespace: Namespace): MutualRecursionPlan = {
    val mutualComponents = module.termDefinitionComponents(namespace).filter(_.isMutuallyRecursive)
    MutualRecursionPlan(GeneratedMutualNames.assign(mutualComponents))
  }
}

private[elaboration] object GeneratedMutualNames {
  private val initialHashLength = 8

  def assign(
    components: List[TermDefinitionComponent]
  ): Map[Set[String], GeneratedMutualNames] = {
    components.zipWithIndex.foldLeft(
      (Map.empty[Set[String], GeneratedMutualNames], Set.empty[String])
    ) { case ((assignedNames, unavailableNames), (component, componentIndex)) =>
      val completeHash = componentHash(component)
      val hashLengths = (initialHashLength to completeHash.length by 4).toList
      val selectedHash = hashLengths.iterator.map(completeHash.take)
        .find(hash => !unavailableNames.contains(s"$$mutual_$hash"))
        .getOrElse(s"${completeHash}_$componentIndex")
      val names = GeneratedMutualNames(
        s"$$mutual_$selectedHash",
        s"$$mutual_self_$selectedHash"
      )
      assignedNames.updated(component.definitionNames, names) ->
        (unavailableNames + names.definitionName + names.selfName)
    }._1
  }

  private def componentHash(component: TermDefinitionComponent): String = {
    val input = component.definitions.flatMap { definition =>
      val nameBytes = definition.name.getBytes(StandardCharsets.UTF_8)
      Array(
        (nameBytes.length >>> 24).toByte,
        (nameBytes.length >>> 16).toByte,
        (nameBytes.length >>> 8).toByte,
        nameBytes.length.toByte
      ).toList ::: nameBytes.toList
    }.toArray
    Sha256.hexDigest(input)
  }
}
