package spire.math

import Predef.{any2stringadd => _, _}

import spire.algebra._
import spire.math.poly.Term
import spire.std.int._
import spire.std.option._
import spire.std.tuples._
import spire.syntax.field._
import spire.syntax.nroot._
import spire.syntax.order._

/**
 * Interval represents a set of values, usually numbers.
 * 
 * Intervals have upper and lower bounds. Each bound can be one of
 * three kinds:
 * 
 *   * Closed: The boundary value is included in the interval.
 *   * Open: The boundary value is excluded from the interval.
 *   * Unbound: There is no boundary value.
 *
 * When the underlying type of the interval supports it, intervals may
 * be used in arithmetic. There are several possible interpretations
 * of interval arithmetic: the interval can represent uncertainty
 * about a single value (for instance, a quantity +/- tolerance in
 * engineering) or it can represent all values in the interval
 * simultaneously. In this implementation we have chosen to use the
 * probabillistic interpretation.
 *
 * One common pitfall with interval arithmetic is that many familiar
 * algebraic relations do not hold. For instance, given two intervals
 * a and b:
 * 
 *   a == b does not imply a * a == a * b
 *
 * Consider a = b = [-1, 1]. Since any number times itself is
 * non-negative, a * a = [0, 1]. However, a * b = [-1, 1], since we
 * may actually have a=1 and b=-1.
 *
 * These situations will result in loss of precision (in the form of
 * wider intervals). The result is not wrong per se, but less
 * acccurate than it could be.
 */
sealed abstract class Interval[A](implicit order: Order[A]) { lhs =>

  @inline private[this] final def isClosed(flags: Int): Boolean = flags == 0
  @inline private[this] final def isClosedLower(flags: Int): Boolean = (flags & 1) == 0
  @inline private[this] final def isClosedUpper(flags: Int): Boolean = (flags & 2) == 0

  @inline private[this] final def isOpen(flags: Int): Boolean = flags == 3
  @inline private[this] final def isOpenLower(flags: Int): Boolean = (flags & 1) == 1
  @inline private[this] final def isOpenUpper(flags: Int): Boolean = (flags & 2) == 2

  @inline private[this] final def lowerFlag(flags: Int): Int = flags & 1
  @inline private[this] final def upperFlag(flags: Int): Int = flags & 2

  @inline private[this] final def reverseLowerFlag(flags: Int): Int = flags ^ 1
  @inline private[this] final def reverseUpperFlag(flags: Int): Int = flags ^ 2
  @inline private[this] final def reverseFlags(flags: Int): Int = flags ^ 3

  private[this] final def lowerFlagToUpper(flags: Int): Int = (flags & 1) << 1
  private[this] final def upperFlagToLower(flags: Int): Int = (flags & 2) >>> 1

  @inline private[this] final def swapFlags(flags: Int): Int =
    ((flags & 1) << 1) | ((flags & 2) >>> 1)

  def isEmpty: Boolean = this.isInstanceOf[Empty[_]]

  def nonEmpty: Boolean = !isEmpty

  def isPoint: Boolean = this.isInstanceOf[Point[_]]

  def contains(t: A): Boolean =
    hasAtOrBelow(t) && hasAtOrAbove(t)

  def crosses(t: A): Boolean =
    hasBelow(t) && hasAbove(t)

  def crossesZero(implicit ev: AdditiveMonoid[A]): Boolean =
    hasBelow(ev.zero) && hasAbove(ev.zero)

  private[spire] def lowerPair: Option[(A, Int)] = this match {
    case Bounded(lower, upper, flags) => Some((lower, lowerFlag(flags)))
    case Point(value) => Some((value, 0))
    case Above(lower, flags) => Some((lower, flags))
    case _: All[_] => None
    case _: Below[_] => None
    case _: Empty[_] => sys.error("Should never be called on empty Interval") // TODO: remove this check used during refactoring
  }

  private[spire] def upperPair: Option[(A, Int)] = this match {
    case Bounded(lower, upper, flags) => Some((upper, upperFlag(flags)))
    case Point(value) => Some((value, 0))
    case Below(upper, flags) => Some((upper, flags))
    case _: All[_] => None
    case _: Above[_] => None
    case _: Empty[_] => sys.error("Should never be called on empty Interval") // TODO: remove this check used during refactoring
  }

  import Interval.{Bound, Open, Closed, Unbound, EmptyBound}

  def lowerBound: Bound[A] = this match {
    case Point(value) => Closed(value)
    case Bounded(lower, _, flags) if isOpenLower(flags) => Open(lower)
    case Bounded(lower, _, _) => Closed(lower)
    case Above(lower, flags) if isOpenLower(flags) => Open(lower)
    case Above(lower, _) => Closed(lower)
    case All() => Unbound()
    case Below(_, _) => Unbound()
    case Empty() => EmptyBound()
  }

  def upperBound: Bound[A] = this match {
    case Point(value) => Closed(value)
    case Bounded(_, upper, flags) if isOpenUpper(flags) => Open(upper)
    case Bounded(_, upper, _) => Closed(upper)
    case Below(upper, flags) if isOpenUpper(flags) => Open(upper)
    case Below(upper, _) => Closed(upper)
    case All() => Unbound()
    case Above(_, _) => Unbound()
    case Empty() => EmptyBound()
  }

  def mapBounds[B: Order](f: A => B): Interval[B] =
    Interval.fromBounds(lowerBound.map(f), upperBound.map(f))

  def fold[B](f: (Bound[A], Bound[A]) => B): B =
    f(lowerBound, upperBound)

  private[this] def lowerPairBelow(lower1: A, flags1: Int, lower2: A, flags2: Int): Boolean =
    lower1 < lower2 || lower1 === lower2 && (isClosedLower(flags1) || isOpenLower(flags2))

  private[this] def upperPairAbove(upper1: A, flags1: Int, upper2: A, flags2: Int): Boolean =
    upper1 > upper2 || upper1 === upper2 && (isClosedUpper(flags1) || isOpenUpper(flags2))

  def isSupersetOf(rhs: Interval[A]): Boolean = (lhs, rhs) match {
    // deal with All, Empty and Point on either left or right side

    case (All(), _) => true
    case (_, All()) => false

    case (_, Empty()) => true
    case (Empty(), _) => false

    case (Point(lhsval), Point(rhsval)) => lhsval === rhsval
    case (Point(_), _) => false // rhs cannot be Empty or Point
    case (_, Point(rhsval)) => lhs.contains(rhsval)

    // remaining cases are Above, Below and Bounded, we deal first with the obvious false

    case (Above(_, _), Below(_, _)) => false
    case (Below(_, _), Above(_, _)) => false
    case (Bounded(_, _, _), Below(_, _)) => false
    case (Bounded(_, _, _), Above(_, _)) => false

    case (Above(lower1, flags1), Bounded(lower2, _, flags2)) =>
      lowerPairBelow(lower1, flags1, lower2, flags2)
    case (Above(lower1, flags1), Above(lower2, flags2)) =>
      lowerPairBelow(lower1, flags1, lower2, flags2)

    case (Below(upper1, flags1), Below(upper2, flags2)) =>
      upperPairAbove(upper1, flags1, upper2, flags2)
    case (Below(upper1, flags1), Bounded(_, upper2, flags2)) =>
      upperPairAbove(upper1, flags1, upper2, flags2)

    case (Bounded(lower1, upper1, flags1), Bounded(lower2, upper2, flags2)) =>
      lowerPairBelow(lower1, flags1, lower2, flags2) &&
      upperPairAbove(upper1, flags1, upper2, flags2)
  }

  def isProperSupersetOf(rhs: Interval[A]): Boolean =
    lhs != rhs && (lhs isSupersetOf rhs)

  def isSubsetOf(rhs: Interval[A]): Boolean =
    rhs isSupersetOf lhs

  def isProperSubsetOf(rhs: Interval[A]): Boolean =
    rhs isProperSupersetOf lhs

  // Does this interval contain any points above t ?
  def hasAbove(t: A): Boolean = this match {
    case Empty() => false
    case Point(p) => p > t
    case Below(upper, _) => upper > t
    case Bounded(_, upper, _) => upper > t
    case All() => true
    case Above(_, _) => true
  }

  // Does this interval contain any points below t ?
  def hasBelow(t: A): Boolean = this match {
    case Empty() => false
    case Point(p) => p < t
    case Above(lower, _) => lower < t
    case Bounded(lower, _, _) => lower < t
    case Below(_, _) => true
    case All() => true
  }

  // Does this interval contains any points at or above t ?
  def hasAtOrAbove(t: A) = this match {
    case _: Empty[_] => false
    case Point(p) => p >= t
    case Below(upper, flags) =>
      upper > t || isClosedUpper(flags) && upper === t
    case Bounded(lower, upper, flags) =>
      upper > t || isClosedUpper(flags) && upper === t
    case _: Above[_] => true
    case _: All[_] => true
  }

  // Does this interval contains any points at or below t ?
  def hasAtOrBelow(t: A) = this match {
    case _: Empty[_] => false
    case Point(p) => p <= t
    case Above(lower, flags) =>
      lower < t || isClosedLower(flags) && lower === t
    case Bounded(lower, upper, flags) =>
      lower < t || isClosedLower(flags) && lower === t
    case _: Below[_] => true
    case _: All[_] => true
  }

  def isAt(t: A) = this match {
    case Point(p) => t === p
    case _ => false
  }

  private[this] def minLower(lower1: A, lower2: A, flags1: Int, flags2: Int): (A, Int) =
    (lower1 compare lower2) match {
      case -1 => (lower1, flags1)
      case 0 => (lower1, flags1 & flags2)
      case 1 => (lower2, flags2)
    }

  private[this] def maxLower(lower1: A, lower2: A, flags1: Int, flags2: Int): (A, Int) =
    (lower1 compare lower2) match {
      case -1 => (lower2, flags2)
      case 0 => (lower1, flags1 | flags2)
      case 1 => (lower1, flags1)
    }

  private[this] def minUpper(upper1: A, upper2: A, flags1: Int, flags2: Int): (A, Int) =
    (upper1 compare upper2) match {
      case -1 => (upper1, flags1)
      case 0 => (upper1, flags1 | flags2)
      case 1 => (upper2, flags2)
    }

  private[this] def maxUpper(upper1: A, upper2: A, flags1: Int, flags2: Int): (A, Int) =
    (upper1 compare upper2) match {
      case -1 => (upper2, flags2)
      case 0 => (upper1, flags1 & flags2)
      case 1 => (upper1, flags1)
    }

  def intersects(rhs: Interval[A]): Boolean =
    !(lhs intersect rhs).isEmpty

  def &(rhs: Interval[A]): Interval[A] =
    lhs intersect rhs

  def intersect(rhs: Interval[A]): Interval[A] =
    Interval.fromBounds(maxLower(lhs.lowerBound, rhs.lowerBound, true),
      minUpper(lhs.upperBound, rhs.upperBound, true))

  def unary_~(): List[Interval[A]] =
    this match {
      case All() => Nil
      case Empty() => List(All())
      case Above(lower, lf) =>
        List(Below(lower, lowerFlagToUpper(reverseLowerFlag(lf))))
      case Below(upper, uf) =>
        List(Above(upper, upperFlagToLower(reverseUpperFlag(uf))))
      case Point(p) => List(Interval.below(p), Interval.above(p))
      case Bounded(lower, upper, flags) =>
        val lx = lowerFlagToUpper(reverseLowerFlag(lowerFlag(flags)))
        val ux = upperFlagToLower(reverseUpperFlag(upperFlag(flags)))
        List(Below(lower, lx), Above(upper, ux))
    }

  def --(rhs: Interval[A]): List[Interval[A]] =
    if (lhs intersects rhs) {
      (~rhs).map(lhs & _).filter(_.nonEmpty)
    } else {
      (lhs :: Nil).filter(_.nonEmpty)
    }

  def split(t: A): (Interval[A], Interval[A]) =
    (this intersect Interval.below(t), this intersect Interval.above(t))

  def splitAtZero(implicit ev: AdditiveMonoid[A]): (Interval[A], Interval[A]) =
    split(ev.zero)

  def mapAroundZero[B](f: Interval[A] => B)(implicit ev: AdditiveMonoid[A]): (B, B) =
    splitAtZero match {
      case (a, b) => (f(a), f(b))
    }

  def |(rhs: Interval[A]): Interval[A] =
    lhs union rhs

  def union(rhs: Interval[A]): Interval[A] =
    Interval.fromBounds(minLower(lhs.lowerBound, rhs.lowerBound, false),
      maxUpper(lhs.upperBound, rhs.upperBound, false))

  override def toString(): String = this match {
    case _: All[_] => "(-∞, ∞)"
    case _: Empty[_] => "(Ø)"
    case Above(lower, flags) =>
      if (isClosedLower(flags)) s"[$lower, ∞)" else s"($lower, ∞)"
    case Below(upper, flags) =>
      if (isClosedUpper(flags)) s"(-∞, $upper]" else s"(-∞, $upper)"
    case Point(p) => s"[$p]"
    case Bounded(lower, upper, flags) =>
      val s1 = if (isClosedLower(flags)) s"[$lower" else s"($lower"
      val s2 = if (isClosedUpper(flags)) s"$upper]" else s"$upper)"
      s"$s1, $s2"
  }

  def abs(implicit m: AdditiveGroup[A]): Interval[A] =
    if (crossesZero) { // only Bounded, Above or Below can cross zero
      this match {
        case Bounded(lower, upper, fs) =>
          val x = -lower
          if (x > upper) Bounded(m.zero, x, lowerFlagToUpper(fs))
          else if (upper > x) Bounded(m.zero, upper, upperFlag(fs))
          else Bounded(m.zero, x, lowerFlagToUpper(fs) & upperFlag(fs))
        case _ => // Above or Below
          Interval.atOrAbove(m.zero)
      }
    } else if (hasBelow(m.zero)) {
      -this
    } else {
      this
    }

  private[this] def minLower(lhs: Bound[A], rhs: Bound[A], emptyIsMin: Boolean): Bound[A] = 
    (lhs, rhs, emptyIsMin) match {
      case (EmptyBound(), _, true) => lhs
      case (EmptyBound(), _, false) => rhs
      case (_, EmptyBound(), true) => rhs
      case (_, EmptyBound(), false) => lhs
      case (Unbound(), _, _) | (_, Unbound(), _) => Unbound()
      case (Closed(lv), Closed(rv), _) if lv <= rv => lhs
      case (Closed(_), Closed(_), _) => rhs
      case (Open(lv), Open(rv), _) if lv <= rv => lhs
      case (Open(_), Open(_), _) => rhs
      case (Closed(lv), Open(rv), _) if lv <= rv => lhs
      case (Closed(_), Open(_), _) => rhs
      case (Open(lv), Closed(rv), _) if rv <= lv => rhs
      case (Open(_), Closed(_), _) => lhs
  }

  private[this] def maxLower(lhs: Bound[A], rhs: Bound[A], emptyIsMax: Boolean): Bound[A] = 
    (lhs, rhs, emptyIsMax) match {
      case (EmptyBound(), _, true) => lhs
      case (EmptyBound(), _, false) => rhs
      case (_, EmptyBound(), true) => rhs
      case (_, EmptyBound(), false) => lhs
      case (Unbound(), _, _) => rhs
      case (_, Unbound(), _) => lhs
      case (Closed(lv), Closed(rv), _) if lv >= rv => lhs
      case (Closed(_), Closed(_), _) => rhs
      case (Open(lv), Open(rv), _) if lv >= rv => lhs
      case (Open(_), Open(_), _) => rhs
      case (Closed(lv), Open(rv), _) if rv >= lv => rhs
      case (Closed(_), Open(_), _) => lhs
      case (Open(lv), Closed(rv), _) if lv >= rv => lhs
      case (Open(_), Closed(_), _) => rhs
    }

  private[this] def minUpper(lhs: Bound[A], rhs: Bound[A], emptyIsMin: Boolean): Bound[A] = 
    (lhs, rhs, emptyIsMin) match {
      case (EmptyBound(), _, true) => lhs
      case (EmptyBound(), _, false) => rhs
      case (_, EmptyBound(), true) => rhs
      case (_, EmptyBound(), false) => lhs
      case (Unbound(), _, _) => rhs
      case (_, Unbound(), _) => lhs
      case (Closed(lv), Closed(rv), _) if lv <= rv => lhs
      case (Closed(_), Closed(_), _) => rhs
      case (Open(lv), Open(rv), _) if lv <= rv => lhs
      case (Open(_), Open(_), _) => rhs
      case (Closed(lv), Open(rv), _) if rv <= lv => rhs
      case (Closed(_), Open(_), _) => lhs
      case (Open(lv), Closed(rv), _) if lv <= rv => lhs
      case (Open(_), Closed(_), _) => rhs
    }

  private[this] def maxUpper(lhs: Bound[A], rhs: Bound[A], emptyIsMax: Boolean): Bound[A] = 
    (lhs, rhs, emptyIsMax) match {
      case (EmptyBound(), _, true) => lhs
      case (EmptyBound(), _, false) => rhs
      case (_, EmptyBound(), true) => rhs
      case (_, EmptyBound(), false) => lhs
      case (Unbound(), _, _) | (_, Unbound(), _) => Unbound()
      case (Closed(lv), Closed(rv), _) if lv >= rv => lhs
      case (Closed(_), Closed(_), _) => rhs
      case (Open(lv), Open(rv), _) if lv >= rv => lhs
      case (Open(_), Open(_), _) => rhs
      case (Closed(lv), Open(rv), _) if lv >= rv => lhs
      case (Closed(_), Open(_), _) => rhs
      case (Open(lv), Closed(rv), _) if rv >= lv => rhs
      case (Open(_), Closed(_), _) => lhs
    }

  // for all a in A, and all b in B, (A vmin B) is the interval that contains all (a min b)
  def vmin(rhs: Interval[A])(implicit m: AdditiveMonoid[A]): Interval[A] =
    Interval.fromBounds(minLower(lhs.lowerBound, rhs.lowerBound, true),
      minUpper(lhs.upperBound, rhs.upperBound, true))

  // for all a in A, and all b in B, (A vmax B) is the interval that contains all (a max b)
  def vmax(rhs: Interval[A])(implicit m: AdditiveMonoid[A]): Interval[A] =
    Interval.fromBounds(maxLower(lhs.lowerBound, rhs.lowerBound, true),
      maxUpper(lhs.upperBound, rhs.upperBound, true))

  def combine(rhs: Interval[A])(f: (A, A) => A): Interval[A] = {
    val lb = lhs.lowerBound.combine(rhs.lowerBound)(f)
    val ub = lhs.upperBound.combine(rhs.upperBound)(f)
    Interval.fromBounds(lb, ub)
  }

  def +(rhs: Interval[A])(implicit ev: AdditiveSemigroup[A]): Interval[A] = combine(rhs)(_ + _)

  def -(rhs: Interval[A])(implicit ev: AdditiveGroup[A]): Interval[A] = lhs + (-rhs)

  private[this] def minTpl(t1: (A, Int), t2: (A, Int)): (A, Int) =
    if (t1._1 < t2._1) t1 else t2

  private[this] def maxTpl(t1: (A, Int), t2: (A, Int)): (A, Int) =
    if (t1._1 > t2._1) t1 else t2

  private[this] def fromOptionalTpls(t1: Option[(A, Int)], t2: Option[(A, Int)])(implicit r: AdditiveMonoid[A]): Interval[A] =
    (t1, t2) match {
      case (None, None) => Interval.all
      case (Some((x1, f1)), None) => Above(x1, f1)
      case (None, Some((x2, f2))) => Below(x2, f2)
      case (Some(t1), Some(t2)) => fromTpls(t1, t2)
    }

  private[this] def fromTpls(t1: (A, Int), t2: (A, Int))(implicit r: AdditiveMonoid[A]): Interval[A] =
    Interval.withFlags(t1._1, t2._1, lowerFlag(t1._2) | lowerFlagToUpper(t2._2))

  def *(rhs: Interval[A])(implicit ev: Semiring[A]): Interval[A] = {
    val z = ev.zero

    (lhs, rhs) match {
      case (Empty(), _) => lhs
      case (_, Empty()) => rhs
      case (Point(lv), _) => rhs * lv // use multiplication by scalar
      case (_, Point(rv)) => lhs * rv
      // now lhs and rhs are both intervals with more that one point
      case (All(), _) => lhs
      case (_, All()) => rhs        
      case (Above(lower1, lf1), Above(lower2, lf2)) if lower1 < z || lower2 < z => All()
      case (Above(lower1, lf1), Above(lower2, lf2)) => Above(lower1 * lower2, lf1 | lf2)
      case (Above(lower1, lf1), Below(upper2, uf2)) if (lower1 < z || upper2 > z) => All()
      case (Above(lower1, lf1), Below(upper2, uf2)) => 
        Below(lower1 * upper2, lowerFlagToUpper(lf1) | uf2)
      case (Above(lower1, lf1), Bounded(lower2, upper2, flags2)) =>
        (rhs.hasBelow(z), rhs.hasAbove(z)) match {
          case (true, true) => All() // rhs.crossesZero() == true
          case (false, true) => Above(lower1 * lower2, lf1 | lowerFlag(flags2))
          case _ => Below(lower1 * upper2, lowerFlagToUpper(lf1) | upperFlag(flags2))
        }
      case (Below(upper1, uf1), Above(lower2, lf2)) if (upper1 > z || lower2 < z) => All()
      case (Below(upper1, uf1), Above(lower2, lf2)) => 
        Below(upper1 * lower2, uf1 | lowerFlagToUpper(lf2))
      case (Below(upper1, uf1), Below(upper2, uf2)) if (upper1 > z || upper2 > z) => All()
      case (Below(upper1, uf1), Below(upper2, uf2)) =>
        Above(upper1 * upper2, upperFlagToLower(uf1) | upperFlagToLower(uf2))
      case (Below(upper1, uf1), Bounded(lower2, upper2, flags2)) =>
        (rhs.hasBelow(z), rhs.hasAbove(z)) match {
          case (true, true) => All() // rhs.crossesZero() == true
          case (false, true) => Below(upper1 * lower2, uf1 | lowerFlagToUpper(flags2))
          case _ => Above(upper1 * lower2, upperFlagToLower(uf1) | lowerFlag(flags2))
        }
      case (Bounded(lower1, upper1, flags1), Above(lower2, lf2)) if (lower2 < z) => All()
      case (Bounded(lower1, upper1, flags1), Above(lower2, lf2)) =>
        Above(lower1 * lower2, lowerFlag(flags1) | lf2)
      case (Bounded(lower1, upper1, flags1), Below(upper2, uf2)) if (upper2 > z) => All()
      case (Bounded(lower1, upper1, flags1), Below(upper2, uf2)) =>
        Below(lower1 * upper2, lowerFlagToUpper(flags1) | uf2)
      case (Bounded(lower1, upper1, flags1), Bounded(lower2, upper2, flags2)) =>
        val ll = (lower1 * lower2, lowerFlag(flags1) | lowerFlag(flags2))
        val lu = (lower1 * upper2, lowerFlag(flags1) | upperFlagToLower(flags2))
        val ul = (upper1 * lower2, upperFlagToLower(flags1) | lowerFlag(flags2))
        val uu = (upper1 * upper2, upperFlagToLower(flags1) | upperFlagToLower(flags2))
        val lcz = lhs.crossesZero
        val rcz = rhs.crossesZero
        if (lcz && rcz) {
          fromTpls(minTpl(lu, ul), maxTpl(ll, uu))
        } else if (lcz) {
          if (rhs.hasAbove(z)) fromTpls(lu, uu) else fromTpls(ul, ll)
        } else if (rcz) {
          if (lhs.hasAbove(z)) fromTpls(ul, uu) else fromTpls(lu, ll)
        } else if (lhs.hasBelow(z) == rhs.hasBelow(z)) {
          fromTpls(minTpl(ll, uu), maxTpl(ll, uu))
        } else {
          fromTpls(minTpl(lu, ul), maxTpl(lu, ul))
        }
    }
  }

  def reciprocal(implicit ev: Field[A]): Interval[A] = {
    val z = ev.zero
    def error = throw new java.lang.ArithmeticException("/ by zero")

    this match {
      case All() => error
      case Empty() => this

      case Above(lower, lf) =>
        (lower.compare(z), isClosedLower(lf)) match {
          case (x, _) if x < 0 => error // crosses zero
          case (0, true) => error // contains zero
          case (0, false) => this
          case _ => Bounded(z, lower.reciprocal, 1 | lowerFlagToUpper(lf))
        }

      case Below(upper, uf) =>
        (upper.compare(z), isClosedUpper(uf)) match {
          case (x, _) if x > 0 => error // crosses zero
          case (0, true) => error // contains zero
          case (0, false) => this
          case _ => Bounded(upper.reciprocal, z, 2 | upperFlagToLower(uf))
        }

      case Point(v) => Point(v.reciprocal)

      case Bounded(lower, upper, flags) =>
        (lower.compare(z), upper.compare(z), isClosedLower(flags), isClosedUpper(flags)) match {
          case (x, y, _, _) if x < 0 && y > 0 => error // crosses zero
          case (0, _, true, _) => error // contains zero
          case (_, 0, _, true) => error // contains zero
          case (0, _, false, _) => Above(upper.reciprocal, upperFlagToLower(flags))
          case (_, 0, _, false) => Below(lower.reciprocal, lowerFlagToUpper(flags))
          case _ => Bounded(upper.reciprocal, lower.reciprocal, swapFlags(flags))
        }
    }
  }

  def /(rhs: Interval[A])(implicit ev: Field[A]): Interval[A] =
    lhs * rhs.reciprocal

  def +(rhs: A)(implicit ev: AdditiveSemigroup[A]): Interval[A] =
    this match {
      case Point(v) => Point(v + rhs)
      case Bounded(l, u, flags) => Bounded(l + rhs, u + rhs, flags)
      case Above(l, lf) => Above(l + rhs, lf)
      case Below(u, uf) => Below(u + rhs, uf)
      case All() | Empty() => this
    }

  def -(rhs: A)(implicit ev: AdditiveGroup[A]): Interval[A] =
    this + (-rhs)

  def unary_-()(implicit ev: AdditiveGroup[A]): Interval[A] =
    this match {
      case Point(v) => Point(-v)
      case Bounded(l, u, f) => Bounded(-u, -l, swapFlags(f))
      case Above(l, lf) => Below(-l, lowerFlagToUpper(lf))
      case Below(u, uf) => Above(-u, upperFlagToLower(uf))
      case All() | Empty() => this
    }

  def *(rhs: A)(implicit ev: Semiring[A]): Interval[A] =
    if (rhs < ev.zero) {
      this match {
        case Point(v) => Point(v * rhs)
        case Bounded(l, u, f) => Bounded(u * rhs, l * rhs, swapFlags(f))
        case Above(l, lf) => Below(l * rhs, lowerFlagToUpper(lf))
        case Below(u, uf) => Above(u * rhs, upperFlagToLower(uf))
        case All() | Empty() => this
      }
    } else if (rhs === ev.zero) {
      Interval.zero
    } else {
      this match {
        case Point(v) => Point(v * rhs)
        case Bounded(l, u, flags) => Bounded(l * rhs, u * rhs, flags)
        case Above(l, lf) => Above(l * rhs, lf)
        case Below(u, uf) => Below(u * rhs, uf)
        case All() | Empty() => this
      }
    }

  def pow(k: Int)(implicit r: Ring[A]): Interval[A] = {
    def loop(b: Interval[A], k: Int, extra: Interval[A]): Interval[A] =
      if (k == 1)
        b * extra
      else
        loop(b * b, k >>> 1, if ((k & 1) == 1) b * extra else extra)

    if (k < 0) {
      throw new IllegalArgumentException(s"negative exponent: $k")
    } else if (k == 0) {
      Interval.point(r.one)
    } else if (k == 1) {
      this
    } else if ((k & 1) == 0) {
      val t = abs
      loop(t, k - 1, t)
    } else {
      loop(this, k - 1, this)
    }
  }

  def nroot(k: Int)(implicit r: Ring[A], n: NRoot[A]): Interval[A] = {
    if (k == 1) {
      this
    } else if ((k & 1) == 0 && hasBelow(r.zero)) {
      sys.error("can't take even root of negative number")
    } else {
      this match {
        case All() | Empty() => this
        case Point(v) => Point(v.nroot(k))
        case Above(l, lf) => Above(l.nroot(k), lf)
        case Below(u, uf) => Below(u.nroot(k), uf)
        case Bounded(l, u, flags) => Bounded(l.nroot(k), u.nroot(k), flags)
      }
    }
  }

  def sqrt(implicit r: Ring[A], n: NRoot[A]): Interval[A] = nroot(2)

  def top(epsilon: A)(implicit r: AdditiveGroup[A]): Option[A] = this match {
    case Empty() | All() | Above(_, _) => None // TOCHECK: changed semantics, Empty().top == None
    case Below(upper, uf) =>
      Some(if (isOpenUpper(uf)) upper - epsilon else upper)
    case Point(v) => Some(v)
    case Bounded(_, upper, flags) =>
      Some(if (isOpenUpper(flags)) upper - epsilon else upper)
  }

  def bottom(min: A, epsilon: A)(implicit r: AdditiveGroup[A]): Option[A] = this match {
    case Empty() | All() | Below(_, _) => None // TOCHECK: changed semantics, Empty().bottom == None
    case Above(lower, lf) =>
      Some(if (isOpenLower(lf)) lower + epsilon else lower)
    case Point(v) => Some(v)
    case Bounded(lower, _, flags) =>
      Some(if (isOpenLower(flags)) lower + epsilon else lower)
  }

  import spire.random.{Dist, Uniform}

  def dist(min: A, max: A, epsilon: A)(implicit u: Uniform[A], r: AdditiveGroup[A]): Dist[A] =
    u(bottom(min, epsilon).getOrElse(min), top(epsilon).getOrElse(max))

  def translate(p: Polynomial[A])(implicit ev: Field[A]): Interval[A] = {
    val terms2 = p.terms.map { case Term(c, e) => Term(Interval.point(c), e) }
    val p2 = Polynomial(terms2)
    p2(this)
  }
}

case class All[A: Order] private[spire] () extends Interval[A]
case class Above[A: Order] private[spire] (lower: A, flags: Int) extends Interval[A]
case class Below[A: Order] private[spire] (upper: A, flags: Int) extends Interval[A]

// Bounded, non-empty interval with lower < upper
case class Bounded[A: Order] private[spire] (lower: A, upper: A, flags: Int) extends Interval[A] {
  require(lower < upper) // TODO: remove after refactoring
}
case class Point[A: Order] private[spire] (value: A) extends Interval[A]
case class Empty[A: Order] private[spire] () extends Interval[A]

object Interval {

  sealed trait Bound[A] { lhs =>
    def map[B](f: A => B): Bound[B] = this match {
      case Open(a) => Open(f(a))
      case Closed(a) => Closed(f(a))
      case Unbound() => Unbound()
      case EmptyBound() => EmptyBound()
    }
    def combine[B](rhs: Bound[A])(f: (A, A) => A): Bound[A] = (lhs, rhs) match {
      case (EmptyBound(), _) => lhs
      case (_, EmptyBound()) => rhs
      case (Unbound(), _) => lhs
      case (_, Unbound()) => rhs
      case (Closed(a), y) => y.map(b => f(a, b))
      case (x, Closed(b)) => x.map(a => f(a, b))
      case (Open(a), Open(b)) => Open(f(a, b))
    }

    def unary_-()(implicit ev: AdditiveGroup[A]): Bound[A] =
      lhs.map(-_)
    def reciprocal()(implicit ev: MultiplicativeGroup[A]): Bound[A] =
      lhs.map(_.reciprocal)

    def +(a: A)(implicit ev: AdditiveSemigroup[A]): Bound[A] = map(_ + a)
    def -(a: A)(implicit ev: AdditiveGroup[A]): Bound[A] = map(_ - a)
    def *(a: A)(implicit ev: MultiplicativeSemigroup[A]): Bound[A] = map(_ * a)
    def /(a: A)(implicit ev: MultiplicativeGroup[A]): Bound[A] = map(_ / a)

    def +(rhs: Bound[A])(implicit ev: AdditiveSemigroup[A]): Bound[A] =
      lhs.combine(rhs)(_ + _)
    def -(rhs: Bound[A])(implicit ev: AdditiveGroup[A]): Bound[A] =
      lhs.combine(rhs)(_ - _)
    def *(rhs: Bound[A])(implicit ev: MultiplicativeSemigroup[A]): Bound[A] =
      lhs.combine(rhs)(_ * _)
    def /(rhs: Bound[A])(implicit ev: MultiplicativeGroup[A]): Bound[A] =
      lhs.combine(rhs)(_ / _)
  }

  case class EmptyBound[A]() extends Bound[A]
  case class Unbound[A]() extends Bound[A]
  case class Open[A](a: A) extends Bound[A]
  case class Closed[A](a: A) extends Bound[A]

  private[spire] def withFlags[A: Order](lower: A, upper: A, flags: Int): Interval[A] =
    if (lower < upper)
      Bounded(lower, upper, flags)
    else if (lower === upper && flags == 0)
      Point(lower)
    else
      Interval.empty[A]

  def empty[A](implicit o: Order[A]): Interval[A] = Empty[A]

  def point[A: Order](a: A): Interval[A] = Point(a)

  def zero[A](implicit o: Order[A], r: Semiring[A]): Interval[A] = Point(r.zero)

  def all[A: Order]: Interval[A] = All[A]()

  def apply[A: Order](lower: A, upper: A): Interval[A] = closed(lower, upper)

  def fromBounds[A: Order](lower: Bound[A], upper: Bound[A]): Interval[A] =
    (lower, upper) match {
      case (EmptyBound(), EmptyBound()) => empty
      case (Closed(x), Closed(y)) => closed(x, y)
      case (Open(x), Open(y)) => open(x, y)
      case (Unbound(), Open(y)) => below(y)
      case (Open(x), Unbound()) => above(x)
      case (Unbound(), Closed(y)) => atOrBelow(y)
      case (Closed(x), Unbound()) => atOrAbove(x)
      case (Closed(x), Open(y)) => openUpper(x, y)
      case (Open(x), Closed(y)) => openLower(x, y)
      case (Unbound(), Unbound()) => all
      case (EmptyBound(), _) | (_, EmptyBound()) => sys.error("Invalid parameters")
    }

  def closed[A: Order](lower: A, upper: A): Interval[A] = {
    val c = Order[A].compare(lower, upper)
    if (c < 0) Bounded(lower, upper, 0)
    else if (c == 0) Point(lower)
    else Interval.empty[A]
  }
  def open[A: Order](lower: A, upper: A): Interval[A] =
    if (lower < upper) Bounded(lower, upper, 3) else Interval.empty[A]
  def openLower[A: Order](lower: A, upper: A): Interval[A] =
    if (lower < upper) Bounded(lower, upper, 1) else Interval.empty[A]
  def openUpper[A: Order](lower: A, upper: A): Interval[A] =
    if (lower < upper) Bounded(lower, upper, 2) else Interval.empty[A]

  def above[A: Order](a: A): Interval[A] = Above(a, 1)
  def below[A: Order](a: A): Interval[A] = Below(a, 2)
  def atOrAbove[A: Order](a: A): Interval[A] = Above(a, 0)
  def atOrBelow[A: Order](a: A): Interval[A] = Below(a, 0)

  private val NullRe = "^ *\\( *Ø *\\) *$".r
  private val SingleRe = "^ *\\[ *([^,]+) *\\] *$".r
  private val PairRe = "^ *(\\[|\\() *(.+?) *, *(.+?) *(\\]|\\)) *$".r

  def apply(s: String): Interval[Rational] = s match {
    case NullRe() =>
      Interval.empty[Rational]
    case SingleRe(x) =>
      Interval.point(Rational(x))
    case PairRe(left, x, y, right) =>
      (left, x, y, right) match {
        case ("(", "-∞", "∞", ")") =>
          Interval.all[Rational]
        case ("(", "-∞", y, ")") =>
          Interval.below(Rational(y))
        case ("(", "∞", y, "]") =>
          Interval.atOrBelow(Rational(y))
        case ("(", x, "∞", ")") =>
          Interval.above(Rational(x))
        case ("[", x, "∞", ")") =>
          Interval.atOrAbove(Rational(x))
        case ("[", x, y, "]") =>
          Interval.closed(Rational(x), Rational(y))
        case ("(", x, y, ")") =>
          Interval.open(Rational(x), Rational(y))
        case ("[", x, y, ")") =>
          Interval.openUpper(Rational(x), Rational(y))
        case ("(", x, y, "]") =>
          Interval.openLower(Rational(x), Rational(y))
        case _ =>
          throw new NumberFormatException("Impossible: " + s)
      }
    case _ =>
      throw new NumberFormatException("For input string: " + s)
  }

  implicit def eq[A: Eq]: Eq[Interval[A]] =
    new Eq[Interval[A]] {
      def eqv(x: Interval[A], y: Interval[A]): Boolean = x == y
    }

  implicit def semiring[A](implicit ev: Ring[A], o: Order[A]): Semiring[Interval[A]] =
    new Semiring[Interval[A]] {
      def zero: Interval[A] = Interval.point(ev.zero)
      def plus(x: Interval[A], y: Interval[A]): Interval[A] = x + y
      def times(x: Interval[A], y: Interval[A]): Interval[A] = x * y
      override def pow(x: Interval[A], k: Int): Interval[A] = x.pow(k)
    }
}
