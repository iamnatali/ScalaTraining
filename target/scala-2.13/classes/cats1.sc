import cats._

import cats.implicits._

//Semigroup[Int => Int].combine(_ + 1, _ * 10).apply(6)

//val source = List("Cats", "is", "awesome")
//val product = Functor[List].fproduct(source)(_.length).toMap

//Monad[Option].ifM(Option(true))(Option("truthy"),
// Option("falsy"))

//Foldable[List].foldK(List(List(1, 2), List(3, 4, 5)))

//Foldable[List].foldK(List(None, Option("two"), Option("three")))

//def parseInt(s: String): Option[Int] =
//  Either.catchOnly[NumberFormatException](s.toInt).toOption
//
//Foldable[List].traverse_(List("1", "2", "3"))(parseInt)
//Foldable[List].traverse_(List("a", "b", "c"))(parseInt)