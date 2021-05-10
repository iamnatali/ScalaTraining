import scala.util.Random
import scala.concurrent.duration._
import cats._
import cats.data._
import cats.implicits._
import cats.effect.implicits._
import cats.effect.{ExitCode, IO, IOApp, Ref}
import cats.effect.std.Queue


object Test21 extends IOApp.Simple {
  type Worker[A, B] = A => IO[B]

  trait WorkerPool[A, B] {
    def exec(a: A): IO[B]

    def add(worker: Worker[A, B]): IO[Unit]

    def removeAll: IO[Unit]
  }

  object WorkerPool {
    def of[A, B](fs: List[Worker[A, B]]): IO[WorkerPool[A, B]] = for {
      q <- Queue.bounded[IO, Worker[A, B]](1)
      ref <- Ref[IO].of(fs.toSet)
      fibers <- fs.traverse(w => q.offer(w).start)
      fiberRef <- Ref[IO].of(fibers)

      pool = new WorkerPool[A, B] {
        def exec(a: A): IO[B] = {
          for {
            worker <- q.take
            res <- worker(a).guarantee(ref.get.flatMap(set =>
              if (set.contains(worker)) {
                q.offer(worker).start
                  .map(fiber =>
                  fiberRef.getAndUpdate(list => fiber +: list))
              } else IO.unit))
          } yield res
        }

        def add(worker: Worker[A, B]): IO[Unit] = {
          ref.getAndUpdate(set => set+worker) >>
          q.offer(worker).start.map(fiber => fiberRef.getAndUpdate(list => fiber +: list))>>
            IO.println("add")
        }

        def removeAll: IO[Unit] =
          for {
            list <- fiberRef.get
            _ <- list.traverse(fiber => fiber.cancel)
            _ <- fiberRef.getAndSet(List.empty)
            _ <- ref.getAndSet(Set.empty[Worker[A, B]])
            _ <- emptyQueue(q)
            _ <- IO.println("remove")
          } yield ()

        def emptyQueue(queue: Queue[IO, Worker[A, B]]): IO[Unit] =
          q.tryTake.flatMap{
            case Some(_) => emptyQueue(queue)
            case None => IO.unit
          }
      }
    } yield pool
  }

  def mkWorker(id: Int, muliply: Int): IO[Worker[Int, Int]] =
    Ref[IO].of(0).map { counter =>
      def simulateWork: IO[Unit] =
        IO(50 * muliply).map(_.millis).flatMap(IO.sleep)

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
    val testPool: IO[WorkerPool[Int, Int]] = List(
      mkWorker(1, 1),
      mkWorker(2, 2)
    )
      .sequence
      .flatMap(WorkerPool.of[Int, Int])

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


