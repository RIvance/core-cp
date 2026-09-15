package cp.util

/** Pure SHA-256 shared by JVM and Scala.js compilation. */
object Sha256 {
  private val initialHash = Array(
    0x6a09e667, 0xbb67ae85, 0x3c6ef372, 0xa54ff53a,
    0x510e527f, 0x9b05688c, 0x1f83d9ab, 0x5be0cd19
  )

  private val roundConstants = Array(
    0x428a2f98, 0x71374491, 0xb5c0fbcf, 0xe9b5dba5, 0x3956c25b, 0x59f111f1, 0x923f82a4, 0xab1c5ed5,
    0xd807aa98, 0x12835b01, 0x243185be, 0x550c7dc3, 0x72be5d74, 0x80deb1fe, 0x9bdc06a7, 0xc19bf174,
    0xe49b69c1, 0xefbe4786, 0x0fc19dc6, 0x240ca1cc, 0x2de92c6f, 0x4a7484aa, 0x5cb0a9dc, 0x76f988da,
    0x983e5152, 0xa831c66d, 0xb00327c8, 0xbf597fc7, 0xc6e00bf3, 0xd5a79147, 0x06ca6351, 0x14292967,
    0x27b70a85, 0x2e1b2138, 0x4d2c6dfc, 0x53380d13, 0x650a7354, 0x766a0abb, 0x81c2c92e, 0x92722c85,
    0xa2bfe8a1, 0xa81a664b, 0xc24b8b70, 0xc76c51a3, 0xd192e819, 0xd6990624, 0xf40e3585, 0x106aa070,
    0x19a4c116, 0x1e376c08, 0x2748774c, 0x34b0bcb5, 0x391c0cb3, 0x4ed8aa4a, 0x5b9cca4f, 0x682e6ff3,
    0x748f82ee, 0x78a5636f, 0x84c87814, 0x8cc70208, 0x90befffa, 0xa4506ceb, 0xbef9a3f7, 0xc67178f2
  )

  def digest(input: Array[Byte]): Array[Byte] = {
    val padded = paddedInput(input)
    val hash = initialHash.clone()
    val schedule = new Array[Int](64)

    var blockOffset = 0
    while (blockOffset < padded.length) {
      var wordIndex = 0
      while (wordIndex < 16) {
        val offset = blockOffset + wordIndex * 4
        schedule(wordIndex) =
          ((padded(offset) & 0xff) << 24) |
          ((padded(offset + 1) & 0xff) << 16) |
          ((padded(offset + 2) & 0xff) << 8) |
          (padded(offset + 3) & 0xff)
        wordIndex += 1
      }
      while (wordIndex < 64) {
        val previous15 = schedule(wordIndex - 15)
        val previous2 = schedule(wordIndex - 2)
        val sigma0 = rotateRight(previous15, 7) ^ rotateRight(previous15, 18) ^ (previous15 >>> 3)
        val sigma1 = rotateRight(previous2, 17) ^ rotateRight(previous2, 19) ^ (previous2 >>> 10)
        schedule(wordIndex) = schedule(wordIndex - 16) + sigma0 + schedule(wordIndex - 7) + sigma1
        wordIndex += 1
      }

      var a = hash(0)
      var b = hash(1)
      var c = hash(2)
      var d = hash(3)
      var e = hash(4)
      var f = hash(5)
      var g = hash(6)
      var h = hash(7)
      var round = 0
      while (round < 64) {
        val choice = (e & f) ^ (~e & g)
        val majority = (a & b) ^ (a & c) ^ (b & c)
        val sum0 = rotateRight(a, 2) ^ rotateRight(a, 13) ^ rotateRight(a, 22)
        val sum1 = rotateRight(e, 6) ^ rotateRight(e, 11) ^ rotateRight(e, 25)
        val temporary1 = h + sum1 + choice + roundConstants(round) + schedule(round)
        val temporary2 = sum0 + majority

        h = g
        g = f
        f = e
        e = d + temporary1
        d = c
        c = b
        b = a
        a = temporary1 + temporary2
        round += 1
      }

      hash(0) += a
      hash(1) += b
      hash(2) += c
      hash(3) += d
      hash(4) += e
      hash(5) += f
      hash(6) += g
      hash(7) += h
      blockOffset += 64
    }

    val output = new Array[Byte](32)
    var hashIndex = 0
    while (hashIndex < hash.length) {
      val value = hash(hashIndex)
      val offset = hashIndex * 4
      output(offset) = (value >>> 24).toByte
      output(offset + 1) = (value >>> 16).toByte
      output(offset + 2) = (value >>> 8).toByte
      output(offset + 3) = value.toByte
      hashIndex += 1
    }
    output
  }

  def hexDigest(input: Array[Byte]): String = {
    digest(input).map(byte => f"${byte & 0xff}%02x").mkString
  }

  private def paddedInput(input: Array[Byte]): Array[Byte] = {
    val remainderAfterMarker = (input.length + 1) % 64
    val zeroPadding = (56 - remainderAfterMarker + 64) % 64
    val output = new Array[Byte](input.length + 1 + zeroPadding + 8)
    Array.copy(input, 0, output, 0, input.length)
    output(input.length) = 0x80.toByte
    val bitLength = input.length.toLong * 8L
    var index = 0
    while (index < 8) {
      output(output.length - 1 - index) = (bitLength >>> (index * 8)).toByte
      index += 1
    }
    output
  }

  private def rotateRight(value: Int, distance: Int): Int = {
    (value >>> distance) | (value << (32 - distance))
  }
}
