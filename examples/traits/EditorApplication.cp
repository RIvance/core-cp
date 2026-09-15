// expected: "CoreCP 2.0: Pressing C-x for cutting text"

type KeyBindings = { onKey: String -> String; };
type Clipboard = { cut: String; };
type Version = { version: String; };
type Help = { help: String; };

def keyBindings = trait implements KeyBindings => {
  onKey(key: String) = "Pressing " ++ key;
};

def clipboard = trait [self: KeyBindings] implements Clipboard => {
  cut = self.onKey("C-x") ++ " for cutting text";
};

def version = trait implements Version => {
  version = "2.0";
};

def help = trait [self: Clipboard & Version] implements Help => {
  help = "CoreCP " ++ self.version ++ ": " ++ self.cut;
};

def main: String = (new (keyBindings ,, clipboard ,, version ,, help)).help;
