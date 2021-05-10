import scala.util.Random
import scala.concurrent.duration._
import cats._
import cats.data._
import cats.implicits._
import cats.effect.implicits._
import cats.effect.{Concurrent, ExitCode, IO, IOApp, Ref}
import cats.effect.std.Queue
import cats.effect.std.Console


object Test22 extends IOApp.Simple {
  abstract class Worker[F[_] : Concurrent : Console, A, B]{
    def f: A => F[B]
  }

  abstract class WorkerPool[F[_]: Concurrent : Console, A, B] {
    def exec(a: A): F[B]

    def add(worker: Worker[F, A, B]): F[Unit]

    def removeAll: F[Unit]
  }

  object WorkerPool {
    def of[F[_]: Concurrent : Console, A, B](fs: List[Worker[F, A, B]]): F[WorkerPool[F, A, B]] = for {
      q <- Queue.bounded[F, Worker[F, A, B]](1)
      ref <- Ref[F].of(fs.toSet)
      fibers <- fs.traverse(w => q.offer(w).start)
      fiberRef <- Ref[F].of(fibers)

      pool = new WorkerPool[F, A, B] {
        def exec(a: A): F[B] = {
          for {
            worker <- q.take
            res <- worker.f(a).guarantee(ref.get.flatMap(set =>
              if (set.contains(worker)) {
                q.offer(worker).start
                  .map(fiber =>
                  fiberRef.getAndUpdate(list => fiber +: list))
              } else Concurrent[F].unit))
          } yield res
        }

        def add(worker: Worker[F, A, B]): F[Unit] = {
          ref.getAndUpdate(set => set+worker) >>
          q.offer(worker).start.map(fiber => fiberRef.getAndUpdate(list => fiber +: list)) >>
            Console[F].println("add")
        }

        def removeAll: F[Unit] =
          for {
            list <- fiberRef.get
            _ <- list.traverse(fiber => fiber.cancel)
            _ <- fiberRef.getAndSet(List.empty)
            _ <- ref.getAndSet(Set.empty[Worker[F, A, B]])
            _ <- emptyQueue(q)
            _ <- Console[F].println("remove")
          } yield ()

        def emptyQueue(queue: Queue[F, Worker[F, A, B]]): F[Unit] =
          q.tryTake.flatMap{
            case Some(_) => emptyQueue(queue)
            case None => Concurrent[F].unit
          }
      }
    } yield pool
  }

  def mkWorker(id: Int, muliply: Int): IO[Worker[IO, Int, Int]] =
    Ref[IO].of(0).map { counter =>
      def simulateWork: IO[Unit] =
        IO(50 * muliply).map(_.millis).flatMap(IO.sleep)

      def report: IO[Unit] =
        counter.get.flatMap(i => IO(println(s"Total processed by $id: $i")))

      new Worker[IO, Int, Int] {
        def f: Int => IO[Int] = x =>
          IO.println(s"$id $x") >>
            simulateWork >>
            counter.update(_ + 1) >>
            report >>
            IO.pure(x + 1)
      }
    }

  val run = {
    val testPool: IO[WorkerPool[IO, Int, Int]] = List(
      mkWorker(1, 1),
      mkWorker(2, 2)
    )
      .sequence
      .flatMap(WorkerPool.of[IO, Int, Int])

    for {
      pool <- testPool
      _ <- pool.exec(1)
      _ <- pool.exec(2)
      _ <- pool.removeAll
      _ <- IO.sleep(100.millis)
      w <- mkWorker(3, 1)
      _ <- pool.add(w)
      _ <- pool.exec(3)
      _ <- pool.exec(4)
    } yield ExitCode.Success
  }
}


