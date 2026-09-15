// expected: "Version 0.1: usage"

type Editor = {
  onKey: String -> String;
  doCut: String;
  showHelp: String;
};
type Version = { version: String; };

def editor = trait [self: Editor & Version] implements Editor => {
  onKey(key: String) = "Pressing " ++ key;
  doCut = self.onKey("C-x") ++ " for cutting text";
  showHelp = "Version " ++ self.version ++ ": usage";
};

def version = trait implements Version => {
  version = "0.1";
};

def main: String = (new (editor ,, version)).showHelp;
