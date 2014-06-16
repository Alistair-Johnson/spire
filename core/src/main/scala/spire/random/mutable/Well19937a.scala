/************************************************************************\
** Project                                                              **
**       ______  ______   __    ______    ____                          **
**      / ____/ / __  /  / /   / __  /   / __/     (c) 2011-2014        **
**     / /__   / /_/ /  / /   / /_/ /   / /_                            **
**    /___  / / ____/  / /   / __  /   / __/   Erik Osheim, Tom Switzer **
**   ____/ / / /      / /   / / | |   / /__                             **
**  /_____/ /_/      /_/   /_/  |_|  /____/     All rights reserved.    **
**                                                                      **
**      Redistribution and use permitted under the MIT license.         **
**                                                                      **
\************************************************************************/


package spire
package random
package mutable

import spire.syntax.cfor._
import spire.util.Pack
import spire.math.max
import java.nio.ByteBuffer
import java.util.Arrays

/**
 * This is a Scala implementation of the Well19937a PRNG based on WELL19937a.c.
 *
 * <p>The acronym WELL stands for Well Equidistributed Long-period Linear.
 *
 * <p><b>Reference: </b>
 * Fran&ccedil;ois Panneton, Pierre L'Ecuyer and Makoto Matsumoto:
 * "Improved Long-Period Generators Based on Linear Recurrences Modulo 2",
 * <i>ACM Transactions on Mathematical Software,</i> Vol. 32, No. 1, January 2006, pp 1--16.
 *
 * @see <a href="http://www.iro.umontreal.ca/~panneton/well/WELL19937a.c">WELL19937a.c</a>
 * @see <a href="http://www.iro.umontreal.ca/~panneton/WELLRNG.html">Well PRNG Home Page</a>
 * @see <a href="http://en.wikipedia.org/wiki/Well_Equidistributed_Long-period_Linear">WELL @ Wikipedia</a>
 * @author <a href="mailto:dusan.kysel@gmail.com">Du&#x0161;an Kysel</a>
 */
final class Well19937a protected[random](state: Array[Int], i0: Int) extends IntBasedGenerator {

  import Well19937a.{UpperMask, LowerMask, K, R, R_1, R_2, BYTES, M1, M2, M3, mat0pos, mat0neg, mat1, mat3pos}

  /*
    @inline private final val v0      = new Utils.IntArrayWrapper(i => i, state)
    @inline private final val vm1     = new Utils.IntArrayWrapper(i => (i + M1) & R_1, state)
    @inline private final val vm2     = new Utils.IntArrayWrapper(i => (i + M2) & R_1, state)
    @inline private final val vm3     = new Utils.IntArrayWrapper(i => (i + M3) & R_1, state)
    @inline private final val vrm1    = new Utils.IntArrayWrapper(i => (i + R_1) & R_1, state)
    @inline private final val vrm2    = new Utils.IntArrayWrapper(i => (i + R_2) & R_1, state)
    @inline private final val newV0   = vrm1
    @inline private final val newV1   = v0
    @inline private final val newVrm1 = vrm2
  */

  private var i : Int = i0

  def copyInit: Well19937a = new Well19937a(state.clone, i)

  def getSeedBytes(): Array[Byte] = {
    val bytes = new Array[Byte](BYTES)
    val bb = ByteBuffer.wrap(bytes)

    cfor(0)(_ < R, _ + 1) { i => bb.putInt(state(i)) }
    bb.putInt(i)
    bytes
  }

  def setSeedBytes(bytes: Array[Byte]) {
    val bs = if (bytes.length < BYTES) Arrays.copyOf(bytes, BYTES) else bytes
    val bb = ByteBuffer.wrap(bs)

    cfor(0)(_ < R, _ + 1) { i => state(i) = bb.getInt() }
    i = bb.getInt
  }

  /**
   * Generate an equally-distributed random Int.
   */
  def nextInt(): Int = {

    @inline def map(r: Int) = (i + r) & R_1

    val z0: Int = (state(map(R_1)) & LowerMask) | (state(map(R_2)) & UpperMask)
    val z1: Int = mat0neg(-25, state(i)) ^ mat0pos(27, state(map(M1)))
    val z2: Int = mat3pos(9, state(map(M2))) ^ mat0pos(1, state(map(M3)))

    state(i) = z1 ^ z2
    state(map(R_1)) = mat1(z0) ^ mat0neg(-9, z1) ^ mat0neg(-21, z2) ^ mat0pos(21, state(i))
    i = map(R_1)

    /*
      val z0: Int = (vrm1(i) & LowerMask) | (vrm2(i) & UpperMask)
      val z1: Int = mat0neg(-25, v0(i)) ^ mat0pos(27, vm1(i))
      val z2: Int = mat3pos(9, vm2(i)) ^ mat0pos(1, vm3(i))

      newV1(i) = z1 ^ z2
      newV0(i) = mat1(z0) ^ mat0neg(-9, z1) ^ mat0neg(-21, z2) ^ mat0pos(21, newV1(i))
      i = (i + R_1) & R_1
    */

    state(i)
  }
}

object Well19937a extends GeneratorCompanion[Well19937a, (Array[Int], Int)] {

  @inline private val UpperMask = 0x7FFFFFFF // = 0xFFFFFFFF ^ Int.MinValue
  @inline private val LowerMask = 0x80000000 // = Int.MinValue

  /** Number of bits in the pool. */
  @inline private final val K : Int = 19937

  /** Length of the pool in ints. */
  @inline private final val R : Int = (K + 31) / 32

  /** Length of the pool in ints -1. */
  @inline private final val R_1 : Int = R - 1

  /** Length of the pool in ints -2. */
  @inline private final val R_2 : Int = R - 2

  /** Length of the pool and index in bytes */
  @inline private final val BYTES = R * 4 + 4

  /** First parameter of the algorithm. */
  @inline private final val M1 : Int = 70

  /** Second parameter of the algorithm. */
  @inline private final val M2 : Int = 179

  /** Third parameter of the algorithm. */
  @inline private final val M3 : Int = 449

  @inline private final def mat0pos(t: Int, v: Int) = v ^ (v >>> t)
  @inline private final def mat0neg(t: Int, v: Int) = v ^ (v << -t)
  @inline private final def mat1(v: Int)            = v
  @inline private final def mat3pos(t: Int, v: Int) = v >>> t
                                                                                   
  def randomSeed(): (Array[Int], Int) = (Utils.seedFromInt(R, Utils.intFromTime()), 0)

  def fromSeed(seed: (Array[Int], Int)): Well19937a =
    seed match {
      case (state, statei) =>
        assert(state.length == R)
        new Well19937a(state, statei)
    }

  def fromArray(arr: Array[Int]): Well19937a = fromSeed((Utils.seedFromArray(R, arr)), 0)

  def fromBytes(bytes: Array[Byte]): Well19937a = fromArray(Pack.intsFromBytes(bytes, bytes.length / 4))

  def fromTime(time: Long = System.nanoTime) : Well19937a = fromSeed((Utils.seedFromInt(R, Utils.intFromTime(time))), 0)
}
