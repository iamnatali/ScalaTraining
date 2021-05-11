import scala.concurrent.duration._

import cats.effect._
import cats.effect.implicits._
import cats.effect.kernel.Resource.ExitCase

import cats.implicits._

sealed trait Scope {
  def open[A](ra: Resource[IO, A]): IO[A]

  def getFinalizers: IO[List[IO[Unit]]]
}

object Scope {
  // Implement this. Add context bounds as necessary.
  def apply: Resource[IO, Scope] = Resource.make(
    for {
      finalizers <- Ref[IO].of(List.empty[IO[Unit]])
      r = new Scope {
        def open[A](ra: Resource[IO, A]): IO[A] =
          IO.uncancelable { _ =>
            for {
              t <- ra.allocated
              res <- finalizers.getAndUpdate(list => t._2 +: list).as(t._1)
            } yield res
          }

        def getFinalizers: IO[List[IO[Unit]]] = finalizers.get
      }
    } yield r
  )(s =>
    for {
      finalUnits <- s.getFinalizers
      _ <- IO.println(finalUnits.length)
      _ <- finalUnits.reduce((io1, io2) => io1.guarantee(io2))
    } yield ()
  )
}

object TearingResourcesApart extends IOApp {

  case class TestResource(idx: Int)

  override def run(args: List[String]): IO[ExitCode] = {
    happyPath >>
      cancelability >>
      errorInRelease >>
      errorInAcquire >>
      atomicity >>
      IO(println("Run completed")) >>
      IO.pure(ExitCode.Success)
  }

  case class Allocs(normal: Resource[IO, TestResource], slowAcquisition: Resource[IO, TestResource],
                    crashOpen: Resource[IO, TestResource], crashClose: Resource[IO, TestResource])

  def happyPath: IO[Unit] =
    test { (allocs, scope, _) =>
      for {
        r1 <- scope.open(allocs.normal)
        r2 <- scope.open(allocs.slowAcquisition)
        r3 <- scope.open(allocs.normal)
      } yield ()
    } { (allocs, deallocs, ec) =>
      IO.println("happyPath") >>
        IO.println(allocs) >>
        IO.println(deallocs) >>
        IO.delay {
          require(allocs == Vector(1, 2, 3))
          require(allocs == deallocs.reverse)
          require(ec.isInstanceOf[ExitCase.Succeeded.type])
        }
    }

  def atomicity: IO[Unit] =
    test { (allocs, scope, cancelMe) =>
      for {
        r1 <- scope.open(allocs.normal)
        lock <- Deferred[IO, Unit]
        _ <- (lock.get >> cancelMe).start
        r2 <- scope.open(Resource.eval(lock.complete(())) >> allocs.slowAcquisition)
        _ <- IO.println("next")
        _ <- IO.sleep(1.second)
        r3 <- scope.open(allocs.normal)
      } yield ()
    } { (allocs, deallocs, ec) =>
      IO.println("atomicity") >>
        IO.println(allocs) >>
        IO.println(deallocs) >>
        IO.delay {
          require(allocs == Vector(1, 2))
          require(deallocs == Vector(2, 1))
          require(ec == ExitCase.Canceled)
        }
    }

  def cancelability: IO[Unit] =
    test { (allocs, scope, cancelMe) =>
      for {
        r1 <- scope.open(allocs.normal)
        r2 <- scope.open(allocs.slowAcquisition)
        _ <- cancelMe
        _ <- IO.sleep(1.second)
        r3 <- scope.open(allocs.normal)
      } yield ()
    } { (allocs, deallocs, ec) =>
      IO.println("cancelability") >>
        IO.println(allocs) >>
        IO.println(deallocs) >>
        IO.delay {
          require(allocs == Vector(1, 2))
          require(deallocs == Vector(2, 1))
          require(ec == ExitCase.Canceled)
        }
    }

  def errorInRelease: IO[Unit] =
    test { (allocs, scope, _) =>
      for {
        r1 <- scope.open(allocs.normal)
        r2 <- scope.open(allocs.crashClose)
        r3 <- scope.open(allocs.normal)
      } yield ()
    } { (allocs, deallocs, ec) =>
      IO.println("errorInRelease") >>
        IO.println(allocs) >>
        IO.println(deallocs) >>
        IO.delay {
          require(allocs == Vector(1, 2, 3))
          require(deallocs == Vector(3, 1))
          require(ec match {
            case ExitCase.Errored(_) => true
            case _ => false
          })
        }
    }

  def errorInAcquire: IO[Unit] =
    test { (allocs, scope, _) =>
      for {
        r1 <- scope.open(allocs.normal)
        r2 <- scope.open(allocs.crashOpen)
        r3 <- scope.open(allocs.normal)
      } yield ()
    } { (allocs, deallocs, ec) =>
      IO.println("errorInAcquire") >>
        IO.println(allocs) >>
        IO.println(deallocs) >>
        IO.delay {
          require(allocs == Vector(1))
          require(deallocs == Vector(1))
          require(ec match {
            case ExitCase.Errored(_) => true
            case _ => false
          })
        }
    }


  def test(run: (Allocs, Scope, IO[Unit]) => IO[Unit])
          (check: (Vector[Int], Vector[Int], ExitCase) => IO[Unit]): IO[Unit] =
    for {
      idx <- Ref[IO].of(1)
      allocLog <- Ref[IO].of(Vector.empty[Int])
      deallocLog <- Ref[IO].of(Vector.empty[Int])
      open = idx.modify(i => (i + 1, i))
        .flatTap(i => allocLog.update(_ :+ i)).map(TestResource)
      close = (r: TestResource) => deallocLog.update(_ :+ r.idx)
      cancel <- Deferred[IO, Unit]
      slow = IO.sleep(1.second)
      allocs = Allocs(
        Resource.make(open)(close),
        Resource.make(slow >> open)(close),
        Resource.eval(IO.raiseError(new Exception)),
        Resource.make(open)(_ => IO.raiseError(new Exception))
      )
      finish <- Deferred[IO, Either[Throwable, Unit]]
      scope = for {
        _ <- Resource.makeCase(().pure[IO])((_, ec) => {
          (allocLog.get, deallocLog.get).mapN(check(_, _, ec))
            .flatten.attempt.flatMap(finish.complete)
        }.void)
        s <- Scope.apply
      } yield s
      _ <- scope.use(run(allocs, _, cancel.complete(()).void))
        .race(cancel.get)
        .attempt
      _ <- finish.get.rethrow
    } yield ()
}
