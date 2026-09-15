// expected: 42

type Answer = { answer: Int; };

def answerTrait: Trait[Answer] = trait implements Answer => {
  answer = 42;
};

def main: Int = (new answerTrait).answer;
