package cp.languageserver.jvm

import cp.languageserver.compiler.CpBackend
import cp.languageserver.server.LanguageServer

import scala.util.control.NonFatal

object Main {
  def main(arguments: Array[String]): Unit = {
    val status = if (arguments.nonEmpty && arguments.toList != List("--stdio")) {
      System.err.println("Usage: java -jar cp-language-server.jar [--stdio]")
      1
    } else {
      try new StdioServer(new LanguageServer(new CpBackend), System.in, System.out).run() catch {
        case NonFatal(error) =>
          System.err.println("CP language server: " + Option(error.getMessage).getOrElse(error.getClass.getName))
          1
      }
    }
    System.exit(status)
  }
}
