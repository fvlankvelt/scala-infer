package scappla.tensor

import scappla.{Completeable, Functions}
import scappla.Functions.{exp, log}
import shapeless.Nat

sealed trait Shape {

  def size: Int

  def sizes: List[Int]
}

object Shape {
  implicit def ops[S <: Shape](shape: S) = new ShapeOps[S](shape)
}

class ShapeOps[S <: Shape](shape: S) {
  def :#:[H <: Dim[_]](h: H): H :#: S = scappla.tensor.:#:(h, shape)
}

trait Dim[Self <: Dim[_]] extends Shape {
  self: Self =>

  def size: Int

  final override def sizes: List[Int] = List(size)

  def :#:[H <: Dim[_]](head: H) = scappla.tensor.:#:[H, Self](head, this)
}

final case class :#:[H <: Dim[_], +T <: Shape](head: H, tail: T) extends Shape {

  def size = head.size * tail.size

  def sizes = head.size :: tail.sizes
}

sealed trait Scalar extends Shape {

  val size = 1

  val sizes = List.empty
}

object Scalar extends Scalar

trait DataOps[D] {

  // (de)constructing values

  def zeros(dims: Int*): D

  def set(a: Array[Float], dims: Int*): D

  def get(a: D): Array[Float]

  // element-wise operations

  def plus(a: D, b: D): D

  def minus(a: D, b: D): D

  def times(a: D, b: D): D

  def div(a: D, b: D): D

  def negate(a: D): D

  def log(a: D): D

  def exp(a: D): D

  // shape-affecting operations

  def sum(a: D, dim: Int): D

  def broadcast(a: D, dimIndex: Int, dimSize: Int): D
}

sealed trait Tensor[S <: Shape, D] {

  def shape: S

  protected var data: Option[D] = None

  protected def evalData: D

  protected def evalBackwardData(gradient: D): Unit

  def collect: Array[Float] = {
    dataOps.get(getData)
  }

  def backward(gradient: Array[Float]): Unit = {
    backwardData(dataOps.set(gradient, shape.sizes: _*))
  }

  def buffer(): TBuffer[S, D] = {
    TBuffer(this)
  }

  def plus(other: Tensor[S, D]): Tensor[S, D] = {
    TPlus(this, other)
  }

  def minus(other: Tensor[S, D]): Tensor[S, D] = {
    TMinus(this, other)
  }

  def times(other: Tensor[S, D]): Tensor[S, D] = {
    TTimes(this, other)
  }

  def div(other: Tensor[S, D]): Tensor[S, D] = {
    TDiv(this, other)
  }

  def negate: Tensor[S, D] = {
    TNeg(this)
  }

  private[tensor] def dataOps: DataOps[D]

  private[tensor] def backwardData(gradient: D): Unit = {
    evalBackwardData(gradient)
    data = None
  }

  private[tensor] def getData: D = {
    data = data.orElse(Some(evalData))
    data.get
  }
}

case class TBuffer[S <: Shape, D](value: Tensor[S, D])
    extends Tensor[S, D] with Completeable {

  override def dataOps: DataOps[D] = value.dataOps

  override def shape: S = value.shape

  override def evalData: D = value.getData

  private var grad: Option[D] = None

  private[tensor] override def backwardData(gradient: D): Unit = {
    evalBackwardData(gradient)
  }

  override def evalBackwardData(gradient: D): Unit = {
    grad = grad.map {
      dataOps.plus(_, gradient)
    }.orElse(Some(gradient))
  }

  override def complete(): Unit = {
    grad.foreach {
      value.backwardData
    }
    grad = None
    data = None
  }
}

case class TParam[S <: Shape, D](
    dataOps: DataOps[D],
    var values: Tensor[S, D],
    update: Tensor[S, D] => Tensor[S, D]
) extends Tensor[S, D] {

  def shape: S = values.shape

  override def evalData: D =
    values.getData

  override def evalBackwardData(gradient: D): Unit =
    values = update(TConst(shape, dataOps, gradient))
}

case class TConst[S <: Shape, D](
    shape: S, dataOps: DataOps[D], evalData: D
) extends Tensor[S, D] {

  override def evalBackwardData(gradient: D): Unit = {}
}

case class TNeg[S <: Shape, D](orig: Tensor[S, D]) extends Tensor[S, D] {

  override val shape: S =
    orig.shape

  override val dataOps: DataOps[D] =
    orig.dataOps

  override def evalData: D =
    dataOps.negate(orig.getData)

  override def evalBackwardData(gradient: D): Unit =
    orig.backwardData(dataOps.negate(gradient))
}

case class TPlus[S <: Shape, D](left: Tensor[S, D], right: Tensor[S, D]) extends Tensor[S, D] {
  assert(left.shape == right.shape)

  override val shape: S =
    left.shape

  override val dataOps: DataOps[D] =
    left.dataOps

  override def evalData: D =
    dataOps.plus(left.getData, right.getData)

  override def evalBackwardData(gradient: D): Unit = {
    left.backwardData(gradient)
    right.backwardData(gradient)
  }
}

case class TMinus[S <: Shape, D](left: Tensor[S, D], right: Tensor[S, D]) extends Tensor[S, D] {
  assert(left.shape == right.shape)

  override val shape: S =
    left.shape

  override val dataOps: DataOps[D] =
    left.dataOps

  override def evalData: D =
    dataOps.minus(left.getData, right.getData)

  override def evalBackwardData(gradient: D): Unit = {
    left.backwardData(gradient)
    right.backwardData(dataOps.negate(gradient))
  }
}

case class TTimes[S <: Shape, D](left: Tensor[S, D], right: Tensor[S, D]) extends Tensor[S, D] {
  assert(left.shape == right.shape)

  override val shape: S =
    left.shape

  override val dataOps: DataOps[D] =
    left.dataOps

  override def evalData: D =
    dataOps.times(left.getData, right.getData)

  override def evalBackwardData(gradient: D): Unit = {
    left.backwardData(dataOps.times(gradient, right.getData))
    right.backwardData(dataOps.times(gradient, left.getData))
  }
}

case class TDiv[S <: Shape, D](numer: Tensor[S, D], denom: Tensor[S, D]) extends Tensor[S, D] {
  assert(numer.shape == denom.shape)

  override val shape: S =
    numer.shape

  override val dataOps: DataOps[D] =
    numer.dataOps

  override def evalData: D =
    dataOps.div(numer.getData, denom.getData)

  override def evalBackwardData(gradient: D): Unit = {
    numer.backwardData(dataOps.div(gradient, denom.getData))
    denom.backwardData(dataOps.div(
      dataOps.times(gradient, numer.getData),
      dataOps.times(denom.getData, denom.getData)
    ))
  }
}

case class TLog[S <: Shape, D](upstream: Tensor[S, D]) extends Tensor[S, D] {

  override val shape: S = upstream.shape

  override val dataOps: DataOps[D] = upstream.dataOps

  override def evalData: D =
    dataOps.log(upstream.getData)

  override def evalBackwardData(gradient: D): Unit =
    upstream.backwardData(dataOps.div(gradient, upstream.getData))
}

case class TExp[S <: Shape, D](upstream: Tensor[S, D]) extends Tensor[S, D] {

  override val shape: S =
    upstream.shape

  override val dataOps: DataOps[D] =
    upstream.dataOps

  override def evalData: D =
    dataOps.exp(upstream.getData)

  override def evalBackwardData(gradient: D): Unit =
    upstream.backwardData(dataOps.times(gradient, upstream.getData))
}

case class TSum[R <: Shape, S <: Shape, D](
    shape: R, index: Int, upstream: Tensor[S, D]
) extends Tensor[R, D] {

  override val dataOps: DataOps[D] =
    upstream.dataOps

  override def evalData: D =
    dataOps.sum(upstream.getData, index)

  override def evalBackwardData(gradient: D): Unit = {
    upstream.backwardData(dataOps.broadcast(gradient, index, upstream.shape.sizes(index)))
  }
}

object Tensor {

  def sum[S <: Shape, D <: Dim[_], I <: Nat, R <: Shape, X](
      tensor: Tensor[S, X]
  )(implicit
      indexOf: IndexOf.Aux[S, D, I],
      removeAt: RemoveAt.Aux[S, I, R]
  ): Tensor[R, X] = {
    TSum[R, S, X](removeAt.apply(tensor.shape), indexOf.toInt, tensor)
  }

  def apply[S <: Shape, D](
      shape: S,
      values: Array[Float]
  )(implicit
      dataOps: DataOps[D]
  ): Tensor[S, D] = TConst(shape, dataOps, dataOps.set(values, shape.sizes: _*))

  def param[S <: Shape, D](
      values: Tensor[S, D],
      update: Tensor[S, D] => Tensor[S, D]
  )(implicit
      dataOps: DataOps[D]
  ): Tensor[S, D] = TParam(dataOps, values, update)

  // FUNCTIONS

  implicit def logTensor[S <: Shape, D]: log.Apply[Tensor[S, D], Tensor[S, D]] = new Functions.log.Apply[Tensor[S, D], Tensor[S, D]] {

    def apply(in: Tensor[S, D]): Tensor[S, D] = TLog(in)
  }

  implicit def expTensor[S <: Shape, D]: exp.Apply[Tensor[S, D], Tensor[S, D]] = new Functions.exp.Apply[Tensor[S, D], Tensor[S, D]] {

    def apply(in: Tensor[S, D]): Tensor[S, D] = TExp(in)
  }
}
