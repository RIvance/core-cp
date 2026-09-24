package cp.languageserver.compiler

import cp.language.Cp
import cp.language.analysis.CompletionKind
import cp.language.compilation.{CpSourceFile, SourcePath}
import cp.languageserver.document.{DocumentStore, DocumentUri, TextDocument}
import cp.languageserver.protocol.{CompletionItem, CompletionItemKind, CompletionList, Diagnostic}
import cp.languageserver.server.{DiagnosticReport, LanguageBackend}

/** The only compiler adapter. It consumes public source-analysis results, not compiler internals. */
final class CpBackend extends LanguageBackend {
  override val completionTriggers: List[String] = List(".", ":")

  private def workspace(documents: DocumentStore): List[(TextDocument, CpSourceFile)] = {
    documents.values.filter(_.uri.path.endsWith(".cp")).map { document =>
      document -> CpSourceFile(SourcePath(document.uri.sourceName), document.text)
    }
  }

  override def check(documents: DocumentStore): DiagnosticReport = {
    val sources = workspace(documents)
    val issues = if (sources.isEmpty) Nil else Cp.check(sources.map(_._2))
    val located = List.newBuilder[(DocumentUri, Diagnostic)]
    val messages = List.newBuilder[String]
    issues.foreach { issue =>
      val location = for {
        source <- issue.source
        document <- sources.find(_._2.path == source.path).map(_._1)
        span <- source.span
        range <- document.range(span.startOffset, span.endOffset)
      } yield document.uri -> Diagnostic(range, issue.message, "CP " + issue.phase.label)
      location match {
        case Some(diagnostic) => located += diagnostic
        case None => messages += issue.message
      }
    }
    DiagnosticReport(located.result().groupMap(_._1)(_._2), messages.result())
  }

  override def complete(documents: DocumentStore, document: TextDocument, offset: Int): CompletionList = {
    val sources = workspace(documents)
    val sites = Cp.analyze(sources.map(_._2)).completions.getOrElse(SourcePath(document.uri.sourceName), Nil)
    val site = sites.filter(site => site.span.startOffset <= offset && offset <= site.span.endOffset)
      .sortBy(site => site.span.endOffset - site.span.startOffset).headOption
    val items = site.toList.flatMap { site =>
      val prefix = document.text.substring(site.span.startOffset, offset)
      document.range(site.span.startOffset, site.span.endOffset).toList.flatMap { range =>
        site.candidates.filter(_.name.startsWith(prefix)).map { candidate =>
          val kind = candidate.kind match {
            case CompletionKind.Function => CompletionItemKind.Function
            case CompletionKind.Variable => CompletionItemKind.Variable
            case CompletionKind.Field => CompletionItemKind.Field
            case CompletionKind.Type => CompletionItemKind.Class
            case CompletionKind.TypeParameter => CompletionItemKind.TypeParameter
          }
          CompletionItem(candidate.name, kind, candidate.description, range)
        }
      }
    }
    CompletionList(items)
  }
}
