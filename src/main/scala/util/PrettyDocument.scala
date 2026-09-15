package cp.util

import scala.annotation.tailrec

/** A small strict pretty-printing document with width-sensitive groups. */
private[cp] enum PrettyDocument {
  case Empty
  case Text(value: String)
  case Line
  case Concatenation(left: PrettyDocument, right: PrettyDocument)
  case Indentation(width: Int, document: PrettyDocument)
  case Group(document: PrettyDocument)
}

private[cp] object PrettyDocument {
  val line: PrettyDocument = PrettyDocument.Line

  def text(value: String): PrettyDocument = {
    require(!value.contains('\n'), "pretty-document text cannot contain a line break")
    if (value.isEmpty) PrettyDocument.Empty else PrettyDocument.Text(value)
  }

  def concatenate(documents: PrettyDocument*): PrettyDocument = {
    documents.foldLeft(PrettyDocument.Empty: PrettyDocument) {
      case (PrettyDocument.Empty, document) => document
      case (document, PrettyDocument.Empty) => document
      case (left, right) => PrettyDocument.Concatenation(left, right)
    }
  }

  def join(documents: List[PrettyDocument], separator: PrettyDocument): PrettyDocument = documents match {
    case Nil => PrettyDocument.Empty
    case head :: tail => tail.foldLeft(head) { (combined, document) =>
      concatenate(combined, separator, document)
    }
  }

  def indent(width: Int, document: PrettyDocument): PrettyDocument = {
    require(width >= 0, "pretty-document indentation cannot be negative")
    if (width == 0 || document == PrettyDocument.Empty) document
    else PrettyDocument.Indentation(width, document)
  }

  def group(document: PrettyDocument): PrettyDocument = document match {
    case PrettyDocument.Empty => PrettyDocument.Empty
    case _ => PrettyDocument.Group(document)
  }

  def render(document: PrettyDocument, maximumLineWidth: Int): String = {
    require(maximumLineWidth > 0, "the maximum pretty-printing line width must be positive")
    val result = new StringBuilder
    var column = 0
    var pendingDocuments = List(PendingDocument(0, LayoutMode.Broken, document))

    while (pendingDocuments.nonEmpty) {
      val PendingDocument(indentation, mode, currentDocument) = pendingDocuments.head
      val remainingDocuments = pendingDocuments.tail
      currentDocument match {
        case PrettyDocument.Empty => pendingDocuments = remainingDocuments
        case PrettyDocument.Text(value) =>
          result.append(value)
          column += value.length
          pendingDocuments = remainingDocuments
        case PrettyDocument.Line =>
          mode match {
            case LayoutMode.Flat =>
              result.append(' ')
              column += 1
            case LayoutMode.Broken =>
              result.append('\n')
              result.append(" " * indentation)
              column = indentation
          }
          pendingDocuments = remainingDocuments
        case PrettyDocument.Concatenation(left, right) =>
          pendingDocuments = PendingDocument(indentation, mode, left) ::
            PendingDocument(indentation, mode, right) :: remainingDocuments
        case PrettyDocument.Indentation(width, nestedDocument) =>
          pendingDocuments = PendingDocument(indentation + width, mode, nestedDocument) :: remainingDocuments
        case PrettyDocument.Group(nestedDocument) =>
          val flattened = PendingDocument(indentation, LayoutMode.Flat, nestedDocument)
          if (fits(maximumLineWidth - column, flattened :: remainingDocuments)) {
            pendingDocuments = flattened :: remainingDocuments
          } else {
            pendingDocuments = PendingDocument(indentation, LayoutMode.Broken, nestedDocument) :: remainingDocuments
          }
      }
    }

    result.result()
  }

  @tailrec
  private def fits(remainingWidth: Int, pendingDocuments: List[PendingDocument]): Boolean = {
    if (remainingWidth < 0) {
      false
    } else {
      pendingDocuments match {
        case Nil => true
        case PendingDocument(_, _, PrettyDocument.Empty) :: remainingDocuments =>
          fits(remainingWidth, remainingDocuments)
        case PendingDocument(_, _, PrettyDocument.Text(value)) :: remainingDocuments =>
          fits(remainingWidth - value.length, remainingDocuments)
        case PendingDocument(_, LayoutMode.Flat, PrettyDocument.Line) :: remainingDocuments =>
          fits(remainingWidth - 1, remainingDocuments)
        case PendingDocument(_, LayoutMode.Broken, PrettyDocument.Line) :: _ => true
        case PendingDocument(indentation, mode, PrettyDocument.Concatenation(left, right)) :: remainingDocuments =>
          fits(
            remainingWidth,
            PendingDocument(indentation, mode, left) ::
              PendingDocument(indentation, mode, right) :: remainingDocuments
          )
        case PendingDocument(indentation, mode, PrettyDocument.Indentation(width, nestedDocument)) ::
            remainingDocuments =>
          fits(
            remainingWidth,
            PendingDocument(indentation + width, mode, nestedDocument) :: remainingDocuments
          )
        case PendingDocument(indentation, _, PrettyDocument.Group(nestedDocument)) :: remainingDocuments =>
          fits(
            remainingWidth,
            PendingDocument(indentation, LayoutMode.Flat, nestedDocument) :: remainingDocuments
          )
      }
    }
  }
}

private enum LayoutMode {
  case Flat, Broken
}

private final case class PendingDocument(
  indentation: Int,
  mode: LayoutMode,
  document: PrettyDocument
)
