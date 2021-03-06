/*
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package wvlet.airframe.rx

import wvlet.log.LogSupport

import scala.collection.mutable.ArrayBuffer

/**
  *
  */
trait Rx[A] extends LogSupport {
  import Rx._

  def map[B](f: A => B): Rx[B]         = MapOp[A, B](this, f)
  def flatMap[B](f: A => Rx[B]): Rx[B] = FlatMapOp(this, f)

  def withName(name: String): Rx[A] = this match {
    case NamedOp(p, oldName) => NamedOp(p, name)
    case _                   => NamedOp(this, name)
  }

  def parents: Seq[Rx[_]]

  /**
    * Subscribe any change in the upstream, and if a change is detected,
    *  the given suscriber code will be executed.
    *
    * @param subscriber
    * @tparam U
    * @return
    */
  def subscribe[U](subscriber: A => U): Cancelable = {
    Rx.run(this)(subscriber)
  }
  def run(effect: A => Unit): Cancelable = Rx.run(this)(effect)

}

object Rx extends LogSupport {
  def of[A](v: A): Rx[A]          = SingleOp(v)
  def variable[A](v: A): RxVar[A] = Rx.apply(v)
  def apply[A](v: A): RxVar[A]    = new RxVar(v)

  /**
    * Build a executable chain of Rx operators, and the resulting chain
    * will be registered to the root node (e.g. RxVar). If the root value changes,
    * the effect code block will be executed.
    *
    * @param rx
    * @param effect
    * @tparam A
    * @tparam U
    * @return
    */
  private def run[A, U](rx: Rx[A])(effect: A => U): Cancelable = {
    rx match {
      case MapOp(in, f) =>
        run(in)(x => effect(f.asInstanceOf[Any => A](x)))
      case FlatMapOp(in, f) =>
        var c1 = Cancelable.empty
        val c2 = run(in) { x =>
          val rxb = f.asInstanceOf[Any => Rx[A]](in)
          c1.cancel
          c1 = run(rxb)(effect)
        }
        Cancelable { () => c1.cancel; c2.cancel }
      case NamedOp(input, name) =>
        run(input)(effect)
      case v @ RxVar(currentValue) =>
        v.foreach(effect)
    }
  }

  private[rx] abstract class RxBase[A] extends Rx[A] {}

  abstract class UnaryRx[I, A] extends RxBase[A] {
    def input: Rx[I]
    override def parents: Seq[Rx[_]] = Seq(input)
  }

  case class SingleOp[A](v: A) extends RxBase[A] {
    override def parents: Seq[Rx[_]] = Seq.empty
  }
  case class MapOp[A, B](input: Rx[A], f: A => B)         extends UnaryRx[A, B] {}
  case class FlatMapOp[A, B](input: Rx[A], f: A => Rx[B]) extends UnaryRx[A, B]
  case class NamedOp[A](input: Rx[A], name: String) extends UnaryRx[A, A] {
    override def toString: String = s"${name}:${input}"
  }

  case class RxVar[A](private var currentValue: A) extends RxBase[A] {
    override def toString: String    = s"RxVar(${currentValue})"
    override def parents: Seq[Rx[_]] = Seq.empty

    private var subscribers: ArrayBuffer[Subscriber[A]] = ArrayBuffer.empty

    def get: A = currentValue
    def foreach[U](f: A => U): Cancelable = {
      val s = Subscriber(f)
      // Register a subscriber for propagating future changes
      subscribers += s
      f(currentValue)
      Cancelable { () =>
        // Unsubscribe if cancelled
        subscribers -= s
      }
    }

    def :=(newValue: A): Unit  = set(newValue)
    def set(newValue: A): Unit = update { x: A => newValue }

    /**
      * Updates the variable and trigger the recalculation of the subscribers
      * currentValue => newValue
      */
    def update(updater: A => A): Unit = {
      val newValue = updater(currentValue)
      if (currentValue != newValue) {
        currentValue = newValue
        subscribers.map { s => s(newValue) }
      }
    }
  }
}
