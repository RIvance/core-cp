package cp.util

import java.nio.charset.StandardCharsets

class Sha256Suite extends munit.FunSuite {
  test("SHA-256 matches the standard empty-input vector") {
    assertEquals(
      Sha256.hexDigest(Array.emptyByteArray),
      "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855"
    )
  }

  test("SHA-256 matches the standard abc vector") {
    assertEquals(
      Sha256.hexDigest("abc".getBytes(StandardCharsets.UTF_8)),
      "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad"
    )
  }

  test("SHA-256 handles input spanning more than one block") {
    val input = "abcdbcdecdefdefgefghfghighijhijkijkljklmklmnlmnomnopnopq"
    assertEquals(
      Sha256.hexDigest(input.getBytes(StandardCharsets.UTF_8)),
      "248d6a61d20638b8e5c026930c3e6039a33ce45964ff2167f6ecedd419db06c1"
    )
  }
}
