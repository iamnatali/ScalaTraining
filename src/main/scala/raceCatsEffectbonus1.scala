import cats.Reducible

import scala.util.Random
import scala.concurrent.duration._
import cats.data._
import cats.effect.kernel.Concurrent
import cats.implicits._
import cats.effect.{ExitCode, Fiber, IO, IOApp, Outcome, Ref}

case object Test11 extends IOApp {
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

  def processRaceResult[F[_]: Concurrent, A](outcome: Outcome[F, Throwable, A], f: Fiber[F, Throwable, A], q: Ref[F, List[Throwable]]): F[A] =
    outcome match {
      case Outcome.Succeeded(a) => f.cancel.flatMap(_ => a)
      case _ => f.join.flatMap {
        case Outcome.Succeeded(fa) => fa
        case _ =>
          q.get.flatMap(ex => NonEmptyList.fromList(ex) match {
            case Some(nel) =>  Concurrent[F].raiseError[A](CompositeException(nel))
            case None => Concurrent[F].raiseError[A](new Exception("Unexpected"))
          })
      }
    }

  def raceToSuccess[N[_]: Reducible, F[_]: Concurrent, A](ios: N[F[A]]): F[A] = {
    for {
      q <- Ref[F].of(List.empty[Throwable])
      r <- ios.reduce((io1: F[A], io2: F[A]) => {
        Concurrent[F].racePair(io1, io2).flatMap {
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
