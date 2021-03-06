package scappla.tensor.nd4j

import scala.collection.convert.AsJavaConverters
import scappla.tensor._

import org.nd4j.linalg.api.ndarray.INDArray
import org.nd4j.linalg.api.ops.impl.reduce.longer.MatchCondition
import org.nd4j.linalg.factory.Nd4j
import org.nd4j.linalg.indexing.conditions.Conditions
import org.nd4j.linalg.ops.transforms.Transforms
import org.nd4j.linalg.api.ops.custom.Lgamma
import org.nd4j.linalg.api.ops.custom.Digamma

object Nd4jTensor {

  implicit val ops: TensorData[INDArray] = new TensorData[INDArray] {

    override def fill(value: Float, shape: Int*): INDArray =
      Nd4j.valueArrayOf(shape.toArray, value)

    override def gaussian(shape: Int*): INDArray =
      Nd4j.rand(shape: _*)

    // bridge data to/from JVM

    override def count(d: INDArray, cond: Condition): Int = {
      cond match {
        case GreaterThan(value) =>
          val op = new MatchCondition(d, Conditions.greaterThan(value))

          // MAX_VALUE = "along all dimensions" or equivalently "for entire array"
          Nd4j.getExecutioner.exec(op).getInt(0)
      }
    }

    override def get(d: INDArray, indices: Int*): Float = {
      d.getFloat(indices: _*)
    }

    override def put(d: INDArray, value: Float, indices: Int*): Unit = {
      d.putScalar(indices.toArray, value)
    }

    override def imax(d: INDArray): Seq[Int] = {
      val indices = d.argMax(d.shape().indices: _*)
      (for {
        (_, idx) <- d.shape().zipWithIndex
      } yield {
        indices.getInt(idx)
      }).toSeq
    }

    // elementwise operations

    override def plus(a: INDArray, b: INDArray): INDArray =
      a.add(b)

    override def minus(a: INDArray, b: INDArray): INDArray =
      a.sub(b)

    override def times(a: INDArray, b: INDArray): INDArray =
      a.mul(b)

    override def div(a: INDArray, b: INDArray): INDArray =
      a.div(b)

    override def pow(a: INDArray, b: INDArray): INDArray =
      Transforms.pow(a, b)

    override def negate(a: INDArray): INDArray =
      a.neg()

    override def sqrt(a: INDArray): INDArray =
      Transforms.sqrt(a)

    override def log(a: INDArray): INDArray =
      Transforms.log(a)

    override def exp(a: INDArray): INDArray =
      Transforms.exp(a)

    override def logistic(a: INDArray): INDArray =
      Transforms.sigmoid(a, true)

    override def digamma(a: INDArray): INDArray = {
      val out = Nd4j.exec(new Digamma(a))
      out(0)
    }

    override def lgamma(a: INDArray): INDArray = {
      val out = Nd4j.exec(new Lgamma(a))
      out(0)
    }

    override def softplus(a: INDArray): INDArray =
      Transforms.softPlus(a, true)

    override def cumsum(a: INDArray, dim: Int): INDArray =
      a.cumsum(dim)

    // reshaping operations

    override def sum(a: INDArray, dim: Int): INDArray = {
      val oldShape = a.shape().map {_.toInt}
      val result = a.sum(dim)
      val newShape = oldShape.take(dim) ++ oldShape.drop(dim + 1)
      val finalResult = result.reshape(newShape)
      finalResult
    }

    override def broadcast(a: INDArray, dimIndex: Int, dimSize: Int): INDArray = {
      val oldShape = a.shape().map {_.toInt}
      val newTmp: Seq[Int] = (oldShape.take(dimIndex) :+ 1) ++ oldShape.drop(dimIndex)
      val reshaped = a.reshape(newTmp.toArray)
      val newShape: Seq[Int] = (oldShape.take(dimIndex) :+ dimSize) ++ oldShape.drop(dimIndex)
      reshaped.broadcast(newShape.map { _.toLong }.toArray: _*)
    }

    override def sumAll(a: INDArray): Float = {
      a.sumNumber().floatValue()
    }

    override def tensordot(
        a: INDArray,
        b: INDArray,
        ab: List[(Int, Int)],
        bc: List[(Int, Int)],
        ca: List[(Int, Int)]
    ): INDArray = {
      val aIndices = ab.map { _._1 }.toArray[Int]
      val bIndices = ab.map { _._2 }.toArray[Int]

      val acMap = ca.map { _.swap }.toMap
      val bcMap = bc.toMap

      val ndAc = (0 until a.shape.length)
        .filterNot(aIndices.contains(_))
        .zipWithIndex
        .map { case (aIndex, curIndex) =>
          val cIndex = acMap(aIndex)
          cIndex -> curIndex
        }

      val ndBc = (0 until b.shape.length)
        .filterNot(bIndices.contains(_))
        .zipWithIndex
        .map {
          case (bIndex, curIndex) =>
          val cIndex = bcMap(bIndex)
          cIndex -> (curIndex + ndAc.size)
        }

      val permutation = (ndAc ++ ndBc).sortBy(_._1).map { _._2 }
      Nd4j.tensorMmul(a, b, Array(aIndices, bIndices))
        .permute(permutation: _*)
    }

  }

}
