import scala.util.Random
import scala.concurrent.duration._
import cats.data._
import cats.effect.std.Queue
import cats.implicits._
import cats.effect.{ExitCode, Fiber, IO, IOApp, Outcome, Ref}

case class Data(source: String, body: String)

case class CompositeException(ex: NonEmptyList[Throwable]) extends Exception("All race candidates have failed")

case object Test1 extends IOApp {
  def provider(name: String): IO[Data] = {
    val proc: IO[Data] = for {
      dur <- IO {
        Random.nextInt(500)
      }
      _ <- IO.sleep {
        (10 + dur).millis
      }
      _ <- IO {
        if (Random.nextBoolean()) throw new Exception(s"Error in $name")
      }
      txt <- IO {
        Random.alphanumeric.take(16).mkString
      }
    } yield Data(name, txt)

    proc.guaranteeCase {
      case Outcome.Succeeded(_) => IO {
        println(s"$name request finished")
      }
      case Outcome.Canceled() => IO {
        println(s"$name request canceled")
      }
      case Outcome.Errored(_) => IO {
        println(s"$name errored")
      }
    }
  }

  def processRaceResult[A](outcome: Outcome[IO, Throwable, A], f: Fiber[IO, Throwable, A], q: Ref[IO, List[Throwable]]): IO[A] =
    outcome match {
      case Outcome.Succeeded(a) => f.cancel.flatMap(_ => a)
      case _ => f.join.flatMap {
        case Outcome.Succeeded(fa) => fa
        case _ =>
          q.get.flatMap(ex => NonEmptyList.fromList(ex) match {
            case Some(nel) =>  IO.println(CompositeException(nel).ex) >> IO.raiseError[A](CompositeException(nel))
            case None => IO.raiseError[A](new Exception("Unexpected"))
          })
      }
    }

  def raceToSuccess[A](ios: NonEmptyList[IO[A]]): IO[A] = {
    for {
      q <- Ref[IO].of(List.empty[Throwable])
      r <- ios.reduce((io1: IO[A], io2: IO[A]) => {
        IO.racePair(io1, io2).flatMap {
          case Left((outcome, f)) => processRaceResult(outcome, f, q)
          case Right((f, outcome)) => processRaceResult(outcome, f, q)
        }
      })
    } yield r
  }

  val methods: NonEmptyList[IO[Data]] = NonEmptyList.of(
    "memcached",
    "redis",
    "postgres",
    "mongodb",
    "hdd",
    "aws"
  ).map(provider)

  def run(args: List[String]): IO[ExitCode] = {
    def oneRace = {
      IO.println("start race") >>
        raceToSuccess(methods)
          .flatMap(a => IO(println(s"Final result is $a")))
          .handleErrorWith(err => IO(err.printStackTrace()))
    }

    oneRace.replicateA(5).as(ExitCode.Success)
  }
}
