type Html = { html: String; };
type Latex = { latex: String; };

type TextSig<Element> = {
  Compose: Element -> Element -> Element;
  Text: String -> Element;
  LineBreak: Element;
};

def htmlText = trait implements TextSig<Html> => {
  (Compose left right).html = left.html ++ right.html;
  (Text text).html = text;
  (LineBreak).html = "<br>";
};

def latexText = trait implements TextSig<Latex> => {
  (Compose left right).latex = left.latex ++ right.latex;
  (Text text).latex = text;
  (LineBreak).latex = "\\\\";
};

type DocumentSig<Element> extends TextSig<Element> = {
  Section: Element -> Element;
  SubSection: Element -> Element;
  SubSubSection: Element -> Element;
  Enumerate: Element -> Element;
  Itemize: Element -> Element;
  Item: Element -> Element;
  Link: String -> Element -> Element;
  Bold: Element -> Element;
  Emphasis: Element -> Element;
};

def html = trait implements DocumentSig<Html> inherits htmlText => {
  (Section element).html = "<h2>" ++ element.html ++ "</h2>";
  (SubSection element).html = "<h3>" ++ element.html ++ "</h3>";
  (SubSubSection element).html = "<h4>" ++ element.html ++ "</h4>";
  (Enumerate element).html = "<ol>" ++ element.html ++ "</ol>";
  (Itemize element).html = "<ul>" ++ element.html ++ "</ul>";
  (Item element).html = "<li>" ++ element.html ++ "</li>";
  (Link target element).html = "<a href=\"" ++ target ++ "\">" ++ element.html ++ "</a>";
  (Bold element).html = "<b>" ++ element.html ++ "</b>";
  (Emphasis element).html = "<em>" ++ element.html ++ "</em>";
};

def latex = trait implements DocumentSig<Latex> inherits latexText => {
  (Section element).latex = "\\section{" ++ element.latex ++ "}";
  (SubSection element).latex = "\\subsection{" ++ element.latex ++ "}";
  (SubSubSection element).latex = "\\subsubsection{" ++ element.latex ++ "}";
  (Enumerate element).latex = "\\enumerate{" ++ element.latex ++ "}";
  (Itemize element).latex = "\\itemize{" ++ element.latex ++ "}";
  (Item element).latex = "\\item{" ++ element.latex ++ "}";
  (Link target element).latex = "\\href{" ++ target ++ "}{" ++ element.latex ++ "}";
  (Bold element).latex = "\\textbf{" ++ element.latex ++ "}";
  (Emphasis element).latex = "\\emph{" ++ element.latex ++ "}";
};
