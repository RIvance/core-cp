// expected: "<h2>Welcome to <b>CP</b>!</h2>\n\\section{Welcome to \\textbf{CP}!}"

import Documents::*

def document[Element] = trait [self: DocumentSig<Element>] => {
  body = new self.Section(
    new self.Compose(
      new self.Text("Welcome to "),
      new self.Compose(new self.Bold(new self.Text("CP")), new self.Text("!"))
    )
  );
};

def renderers = new (html ,, latex);
def rendered = document[Html & Latex] ^ renderers;

def main: String = rendered.body.html ++ "\n" ++ rendered.body.latex;
