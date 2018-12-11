package scappla.distributions

import scappla.Functions.log
import scappla.{Real, Score}

import scala.util.Random

case class Bernoulli(p: Real) extends Distribution[Boolean] {

  import Real._

  override def sample(): Sample[Boolean] = {
    val value = Random.nextDouble() < p.v
    //      println(s"Sample: $value (${p.get.v})")
    new Sample[Boolean] {

      override val get: Boolean =
        value

      override val score: Score =
        Bernoulli.this.observe(get)

      override def complete(): Unit = {}
    }
  }

  override def observe(value: Boolean): Score = {
    if (value) log(p) else log(Real(1.0) - p)
  }

}
