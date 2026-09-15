package cp.fitrie.elaboration

import cp.fiobs.Type
import cp.fitrie.*

private[fitrie] object IntersectionSelection {
  def selectFirstComponent(trie: FiTrie, firstType: Type): FiTrie = {
    /*
     * Δ ⊢ A ⇛ₖ 𝒦ₐ
     * ───────────────────────────────── Sel-L
     * Δ ⊢ t ↦ A✓ | B {t ▷ 𝒦ₐ ; · ; ·}
     */
    selectComponent(trie, firstType)
  }

  def selectSecondComponent(trie: FiTrie, secondType: Type): FiTrie = {
    /*
     * Δ ⊢ B ⇛ₖ 𝒦ᵦ
     * ───────────────────────────────── Sel-R
     * Δ ⊢ t ↦ A | B✓ {t ▷ 𝒦ᵦ ; · ; ·}
     */
    selectComponent(trie, secondType)
  }

  private def selectComponent(trie: FiTrie, selectedType: Type): FiTrie = {
    FiTrie.response(ResponseComputation.Filter(
      trie,
      RootKeyCompilation.compile(selectedType)
    ))
  }
}
