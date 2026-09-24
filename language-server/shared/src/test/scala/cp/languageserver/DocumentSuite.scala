package cp.languageserver

import cp.languageserver.document.{DocumentStore, DocumentUri, TextDocument}
import cp.languageserver.protocol.Position
import cp.util.Result

class DocumentSuite extends munit.FunSuite {
  private val uri = DocumentUri.parse("untitled:///資料/%4Dain.cp").toOption.get

  test("URI decoding preserves identity, authority and the source filename") {
    assertEquals(uri.path, "/資料/Main.cp")
    assertEquals(uri.sourceName, "untitled:/資料/Main.cp")
    assertEquals(uri.value, "untitled:///資料/%4Dain.cp")
    val remote = DocumentUri.parse("file://host/project/Main.cp").toOption.get
    assertEquals(remote.sourceName, "file://host/project/Main.cp")
    assert(DocumentUri.parse("not a URI").toOption.isEmpty)
    assert(DocumentUri.parse("Main.cp").toOption.isEmpty)
  }

  test("positions count UTF-16 and recognize all LSP line endings") {
    val document = new TextDocument(uri, 1, "a🌍b\r\nc\rd\n")
    assertEquals(document.offset(Position(0, 3)), Result.Ok(3))
    assert(document.offset(Position(0, 2)).toOption.isEmpty)
    assertEquals(document.offset(Position(1, 1)), Result.Ok(7))
    assertEquals(document.offset(Position(2, 0)), Result.Ok(8))
    assertEquals(document.position(10), Some(Position(3, 0)))
    assertEquals(document.offset(Position(0, 100)), Result.Ok(4))
    assertEquals(document.offset(Position(100, 0)), Result.Ok(10))
    assertEquals(document.position(5), Some(Position(0, 4)))
    assertEquals(document.position(-1), None)
    assertEquals(document.position(11), None)
  }

  test("stale versions and duplicate opens cannot replace a document snapshot") {
    val original = new TextDocument(uri, 10, "original")
    val opened = DocumentStore.empty.open(original).toOption.get
    assert(opened.open(new TextDocument(uri, 11, "duplicate")).toOption.isEmpty)
    assertEquals(opened.change(uri, 9, "stale").toOption.get.get(uri).get.text, "original")
    val changed = opened.change(uri, 11, "new").toOption.get
    assertEquals(changed.get(uri).get.text, "new")
    assertEquals(opened.get(uri).get.text, "original")
    assertEquals(changed.close(uri).get(uri), None)
    assert(DocumentStore.empty.change(uri, 1, "not open").toOption.isEmpty)
  }
}
