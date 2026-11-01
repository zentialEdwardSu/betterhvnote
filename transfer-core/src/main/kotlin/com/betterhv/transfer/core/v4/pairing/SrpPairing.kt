package com.betterhv.transfer.core.v4.pairing

import com.betterhv.transfer.core.TransferCrypto
import com.betterhv.transfer.core.v4.codec.RecordCodec
import com.betterhv.transfer.proto.v4.ErrorCode
import com.betterhv.transfer.proto.v4.PairChallenge
import com.betterhv.transfer.proto.v4.PairConfirm
import com.betterhv.transfer.proto.v4.PairInit
import com.betterhv.transfer.proto.v4.PairResult
import com.betterhv.transfer.proto.v4.PairingFrame
import com.google.protobuf.ByteString
import java.math.BigInteger
import java.security.MessageDigest
import java.security.SecureRandom

class PairingException(val code: ErrorCode, message: String, val attemptsLeft: Int = 0) :
  IllegalStateException(message)

/** Result of a successful pairing: the peer's full identity and the 32-byte session PSK. */
class PairingOutcome(val peerDeviceId: String, val peerDeviceName: String, val psk: ByteArray)

/**
 * SRP-6a over the RFC 5054 2048-bit group with SHA-256, keyed by the six-digit code.
 *
 * A passive observer learns nothing that allows an offline guess of the code, and an active
 * attacker can test exactly one code per protocol run, which the attempt limit bounds.
 */
internal object Srp {
  val N = BigInteger(
    "AC6BDB41324A9A9BF166DE5E1389582FAF72B6651987EE07FC3192943DB56050A37329CBB4A099ED8193E075" +
      "7767A13DD52312AB4B03310DCD7F48A9DA04FD50E8083969EDB767B0CF6095179A163AB3661A05FBD5FAAAE8" +
      "2918A9962F0B93B855F97993EC975EEAA80D740ADBF4FF747359D041D5C33EA71D281E446B14773BCA97B43A" +
      "23FB801676BD207A436C6481F1D2B9078717461A5B9D32E688F87748544523B524B0D57D5EA77A2775D2ECFA" +
      "032CFBDBF52FB37861602790" +
      "04E57AE6AF874E7303CE53299CCC041C7BC308D82A5698F3A8D0C38271AE35F8E9DBFBB694B5C803D89F7AE4" +
      "35DE236D525F54759B65E372FCD68EF20FA7111F9E4AFF73",
    16,
  )
  val g: BigInteger = BigInteger.valueOf(2)
  val LENGTH = (N.bitLength() + 7) / 8
  val k: BigInteger = hashInt(pad(N), pad(g))

  fun pad(value: BigInteger): ByteArray {
    val raw = value.toByteArray().let { if (it.size > 1 && it[0] == 0.toByte()) it.copyOfRange(1, it.size) else it }
    require(raw.size <= LENGTH) { "SRP value exceeds the group size" }
    return ByteArray(LENGTH - raw.size) + raw
  }

  fun hash(vararg parts: ByteArray): ByteArray = MessageDigest.getInstance("SHA-256").run {
    parts.forEach(::update)
    digest()
  }

  fun hashInt(vararg parts: ByteArray): BigInteger = BigInteger(1, hash(*parts))

  fun x(salt: ByteArray, identity: String, code: String): BigInteger =
    hashInt(salt, hash("$identity:$code".encodeToByteArray()))

  fun randomExponent(random: SecureRandom): BigInteger {
    while (true) {
      val candidate = BigInteger(1, ByteArray(EXPONENT_BYTES).also(random::nextBytes))
      if (candidate.signum() != 0) return candidate
    }
  }

  fun proofs(a: BigInteger, b: BigInteger, s: BigInteger): Pair<ByteArray, ByteArray> {
    val m1 = hash(pad(a), pad(b), pad(s))
    val m2 = hash(pad(a), m1, pad(s))
    return m1 to m2
  }

  fun psk(s: BigInteger, transcript: ByteArray): ByteArray =
    TransferCrypto.hkdfSha256(hash(pad(s)), hash(transcript), PSK_INFO, PSK_BYTES)

  fun transcript(init: PairInit, challenge: PairChallenge): ByteArray =
    init.toByteArray() + challenge.toByteArray()

  fun isValidPublic(value: BigInteger): Boolean = value.signum() > 0 && value.mod(N).signum() != 0 && value < N

  const val EXPONENT_BYTES = 32
  const val SALT_BYTES = 16
  const val PSK_BYTES = 32
  private val PSK_INFO = "notelink/v4/pairing-psk".encodeToByteArray()
}

object PairingCode {
  private val random = SecureRandom()

  fun generate(): String = random.nextInt(1_000_000).toString().padStart(6, '0')

  fun isValid(code: String): Boolean = code.length == 6 && code.all { it in '0'..'9' }
}

/**
 * Note side: it shows [code] and starts the exchange. Single use; create a new instance for a
 * retry so every run has fresh SRP randomness.
 */
class PairingInitiator(
  private val code: String,
  private val localDeviceId: String,
  private val localDeviceName: String,
  private val random: SecureRandom = SecureRandom(),
) {
  init {
    require(PairingCode.isValid(code)) { "Pairing code must contain six digits" }
    require(localDeviceId.isNotBlank())
  }

  private val a = Srp.randomExponent(random)
  private val publicA = Srp.g.modPow(a, Srp.N)
  private var init: PairInit? = null
  private var challenge: PairChallenge? = null
  private var expectedServerProof: ByteArray? = null
  private var secret: BigInteger? = null

  fun start(): PairInit {
    check(init == null) { "Pairing already started" }
    return PairInit.newBuilder()
      .setDeviceId(localDeviceId)
      .setDeviceName(localDeviceName)
      .setClientPublic(ByteString.copyFrom(Srp.pad(publicA)))
      .build()
      .also { init = it }
  }

  fun onChallenge(value: PairChallenge): PairConfirm {
    check(init != null && challenge == null) { "Unexpected pairing challenge" }
    val publicB = BigInteger(1, value.serverPublic.toByteArray())
    if (!Srp.isValidPublic(publicB) || value.salt.size() != Srp.SALT_BYTES || value.deviceId.isBlank()) {
      throw PairingException(ErrorCode.ERROR_AUTHENTICATION_FAILED, "Invalid pairing challenge")
    }
    val u = Srp.hashInt(Srp.pad(publicA), Srp.pad(publicB))
    if (u.signum() == 0) throw PairingException(ErrorCode.ERROR_AUTHENTICATION_FAILED, "Invalid pairing challenge")
    val x = Srp.x(value.salt.toByteArray(), localDeviceId, code)
    val base = publicB.subtract(Srp.k.multiply(Srp.g.modPow(x, Srp.N))).mod(Srp.N)
    val s = base.modPow(a.add(u.multiply(x)), Srp.N)
    val (m1, m2) = Srp.proofs(publicA, publicB, s)
    challenge = value
    expectedServerProof = m2
    secret = s
    return PairConfirm.newBuilder().setClientProof(ByteString.copyFrom(m1)).build()
  }

  fun onResult(value: PairResult): PairingOutcome {
    val peer = checkNotNull(challenge) { "Pairing result before challenge" }
    val expected = checkNotNull(expectedServerProof)
    if (value.error != ErrorCode.ERROR_CODE_UNSPECIFIED) {
      throw PairingException(value.error, "Pairing code was rejected", value.attemptsLeft)
    }
    if (!MessageDigest.isEqual(expected, value.serverProof.toByteArray())) {
      throw PairingException(ErrorCode.ERROR_AUTHENTICATION_FAILED, "Peer could not prove the pairing code")
    }
    val psk = Srp.psk(checkNotNull(secret), Srp.transcript(checkNotNull(init), peer))
    return PairingOutcome(peer.deviceId, peer.deviceName.ifBlank { "NoteLink" }, psk)
  }
}

/**
 * Phone/PC side: the user typed [code]. Each `PairInit` is one attempt; after [maxAttempts]
 * failed proofs or [ttlMillis] the responder refuses further attempts.
 */
class PairingResponder(
  private val code: String,
  private val localDeviceId: String,
  private val localDeviceName: String,
  private val maxAttempts: Int = DEFAULT_MAX_ATTEMPTS,
  private val ttlMillis: Long = DEFAULT_TTL_MILLIS,
  private val clock: () -> Long = System::currentTimeMillis,
  private val random: SecureRandom = SecureRandom(),
) {
  init {
    require(PairingCode.isValid(code)) { "Pairing code must contain six digits" }
    require(maxAttempts > 0)
  }

  private val startedAt = clock()
  private var failures = 0
  private var completed = false
  private var attempt: Attempt? = null

  private class Attempt(
    val init: PairInit,
    val challenge: PairChallenge,
    val publicA: BigInteger,
    val publicB: BigInteger,
    val secret: BigInteger,
  )

  val attemptsLeft: Int @Synchronized get() = (maxAttempts - failures).coerceAtLeast(0)

  @Synchronized
  fun isActive(nowMillis: Long = clock()): Boolean =
    !completed && failures < maxAttempts && nowMillis - startedAt < ttlMillis

  @Synchronized
  fun onInit(value: PairInit): PairChallenge {
    ensureActive()
    val publicA = BigInteger(1, value.clientPublic.toByteArray())
    if (!Srp.isValidPublic(publicA) || value.deviceId.isBlank()) {
      throw PairingException(ErrorCode.ERROR_AUTHENTICATION_FAILED, "Invalid pairing request", attemptsLeft)
    }
    val salt = ByteArray(Srp.SALT_BYTES).also(random::nextBytes)
    val v = Srp.g.modPow(Srp.x(salt, value.deviceId, code), Srp.N)
    val b = Srp.randomExponent(random)
    val publicB = Srp.k.multiply(v).add(Srp.g.modPow(b, Srp.N)).mod(Srp.N)
    val u = Srp.hashInt(Srp.pad(publicA), Srp.pad(publicB))
    val s = publicA.multiply(v.modPow(u, Srp.N)).mod(Srp.N).modPow(b, Srp.N)
    val challenge = PairChallenge.newBuilder()
      .setDeviceId(localDeviceId)
      .setDeviceName(localDeviceName)
      .setSalt(ByteString.copyFrom(salt))
      .setServerPublic(ByteString.copyFrom(Srp.pad(publicB)))
      .build()
    attempt = Attempt(value, challenge, publicA, publicB, s)
    return challenge
  }

  /** Returns the reply and, when the proof matched, the pairing outcome. */
  @Synchronized
  fun onConfirm(value: PairConfirm): Pair<PairResult, PairingOutcome?> {
    ensureActive()
    val current = attempt ?: throw PairingException(ErrorCode.ERROR_PROTOCOL_MISMATCH, "Confirm before init")
    attempt = null
    val (m1, m2) = Srp.proofs(current.publicA, current.publicB, current.secret)
    if (!MessageDigest.isEqual(m1, value.clientProof.toByteArray())) {
      failures++
      return PairResult.newBuilder()
        .setError(ErrorCode.ERROR_AUTHENTICATION_FAILED)
        .setAttemptsLeft(attemptsLeft)
        .build() to null
    }
    completed = true
    val outcome = PairingOutcome(
      current.init.deviceId,
      current.init.deviceName.ifBlank { "Note" },
      Srp.psk(current.secret, Srp.transcript(current.init, current.challenge)),
    )
    return PairResult.newBuilder().setServerProof(ByteString.copyFrom(m2)).build() to outcome
  }

  private fun ensureActive() {
    if (completed) throw PairingException(ErrorCode.ERROR_PROTOCOL_MISMATCH, "Pairing already completed")
    if (failures >= maxAttempts) {
      throw PairingException(ErrorCode.ERROR_RATE_LIMITED, "Too many wrong pairing codes")
    }
    if (clock() - startedAt >= ttlMillis) {
      throw PairingException(ErrorCode.ERROR_TICKET_EXPIRED, "Pairing code expired")
    }
  }

  companion object {
    const val DEFAULT_MAX_ATTEMPTS = 3
    const val DEFAULT_TTL_MILLIS = 2 * 60_000L
  }
}

/** Clear-text framing of [PairingFrame] in [RecordCodec.FLAG_PAIRING] records. */
object PairingRecords {
  fun encode(frame: PairingFrame, response: Boolean, messageId: Int, maxFragmentBody: Int): List<ByteArray> {
    val flags = RecordCodec.FLAG_PAIRING or if (response) RecordCodec.FLAG_RESPONSE else 0
    val header = RecordCodec.Header(flags, keyId = 0, sessionId = 0L, streamId = 0, messageId = messageId)
    return RecordCodec.sealClear(header, frame.toByteArray(), maxFragmentBody)
  }

  fun isPairingRecord(record: ByteArray): Boolean =
    record.size > RecordCodec.HEADER_BYTES && record[0] == RecordCodec.MAGIC_0 &&
      record[1] == RecordCodec.MAGIC_1 && (record[3].toInt() and RecordCodec.FLAG_PAIRING) != 0

  /** Reassembles fragments of one direction; a new index-0 fragment restarts the message. */
  class Reassembler {
    private val parts = ArrayList<ByteArray>()
    private var count = 0
    private var messageId = 0

    @Synchronized
    fun add(record: ByteArray): PairingFrame? {
      val fragment = RecordCodec.parse(record)
      if (!fragment.header.isPairing) {
        throw RecordCodec.RecordException(ErrorCode.ERROR_PROTOCOL_MISMATCH, "Not a pairing record")
      }
      if (fragment.index == 0) {
        parts.clear()
        count = fragment.count
        messageId = fragment.header.messageId
      } else if (fragment.header.messageId != messageId || fragment.index != parts.size) {
        parts.clear()
        return null
      }
      parts += record
      if (parts.size < count) return null
      val payload = RecordCodec.openClear(parts.toList())
      parts.clear()
      return try {
        PairingFrame.parseFrom(payload)
      } catch (error: Throwable) {
        throw RecordCodec.RecordException(ErrorCode.ERROR_PROTOCOL_MISMATCH, "Malformed pairing frame")
      }
    }
  }
}
