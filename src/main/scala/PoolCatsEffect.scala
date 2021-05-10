import scala.concurrent.duration._
import cats._
import cats.implicits._
import cats.effect.implicits._
import cats.effect.{ExitCode, IO, IOApp, Ref}
import cats.effect.std.Queue


object Test2 extends IOApp.Simple {
  type Worker[A, B] = A => IO[B]

  trait WorkerPool[A, B] {
    def exec(a: A): IO[B]
  }

  object WorkerPool {
    def of[A, B](fs: List[Worker[A, B]]): IO[WorkerPool[A, B]] = for{
      q <- Queue.unbounded[IO, Worker[A, B]]
      _ <- fs.traverse(w => q.offer(w))
      pool = new WorkerPool[A, B] {
        def exec(a: A): IO[B] = {
          for {
            worker <- q.take
            res <- worker(a).guarantee(q.offer(worker))
          } yield res
        }
      }
    } yield pool
  }

  def mkWorker(id: Int, muliply: Int): IO[Worker[Int, Int]] =
    Ref[IO].of(0).map { counter =>
      def simulateWork: IO[Unit] =
        IO(50*muliply).map(_.millis).flatMap(IO.sleep)

      def report: IO[Unit] =
        counter.get.flatMap(i => IO(println(s"Total processed by $id: $i")))

      x =>
        IO.println(s"$id $x") >>
        simulateWork >>
        counter.update(_ + 1) >>
        report >>
        IO.pure(x + 1)
    }

  val run = {
    val testPool: IO[WorkerPool[Int, Int]] = List(mkWorker(1, 1), mkWorker(2, 2))
      .sequence
      .flatMap(WorkerPool.of[Int, Int])

    for {
      pool <- testPool
      a <- pool.exec(1)
      b <- pool.exec(2)
      c <- pool.exec(3)
    } yield ExitCode.Success
  }
}


