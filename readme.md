# Scala Training

### [from Oleg Pyzhcov](https://olegpy.com/cats-effect-exercises/)

## Worker pool with load balancing

### Objective
Do parallel processing, distributed over a limited number of workers, each with its own state (counters, DB connections, etc.).

### Requirements
- Processing jobs must run in parallel
- Submitting a processing request must wait if all workers are busy.
- Submission should do load balancing: wait for the first worker to finish, not for a certain one.
- Worker should become available whenever a job is completed successfully, with an exception or cancelled.

Assume the number of workers is not very large (<= 100).

### [basic version](https://github.com/iamnatali/ScalaTraining/blob/master/src/main/scala/PoolCatsEffect.scala)

### Bonus
- Add methods to WorkerPool interface for adding workers on the fly and removing all workers. If all workers are removed, submitted jobs must wait until one is added.

### [version with first bonus](https://github.com/iamnatali/ScalaTraining/blob/master/src/main/scala/PoolCatsEffectbonus1.scala)

- Generalize for any F using Concurrent typeclass

### [version with second bonus](https://github.com/iamnatali/ScalaTraining/blob/master/src/main/scala/PoolCatsEffectbonus2.scala)

## Race for success

### Objective
Quickly obtain data which can be requested from multiple sources of unknown latency (databases, caches, network services, etc.).

### Requirements
- [x] The function should run requests in parallel.
- [x] The function should wait for the first request to complete successfuly.
- [x] Once a first request has completed, everything that is still in-flight must be cancelled. 
- [x] If all requests have failed, all errors should be reported for better debugging.

Assume that there will be <= 32 providers and they all don’t block OS threads for I/O.

### [basic version](https://github.com/iamnatali/ScalaTraining/blob/master/src/main/scala/raceCatsEffect.scala)

## Bonus
- [x] NEW (15/12/2018): Avoid using runtime checking for CompositeException (including pattern matching on it).
- [x] If returned IO is cancelled, all in-flight requests should be properly cancelled as well.
- [x] Refactor function to allow generic effect type to be used, not only cats’ IO. (e.g. anything with Async or Concurrent instances).
- [x] Refactor function to allow generic container type to be used (e.g. anything with Traverse or NonEmptyTraverse instances).
    - [x] Don’t use toList. If you have to work with lists anyway, might as well push the conversion responsibility to the caller. 
    - [ ] If you want to support collections that might be empty (List, Vector, Option), the function must result in a failing IO/F when passed an empty value.

### [version with bonuses](https://github.com/iamnatali/ScalaTraining/blob/master/src/main/scala/raceCatsEffectbonus1.scala)

### [from Oleg Pyzhcov 2](https://olegpy.com/resource-exercises/)

## Tearing resources apart
There’s one unsafe method on Resource, and it’s called allocated. It gives you access to allocator and finalizer directly. In fact, originally Resource didn’t have it - that’s how dangerous it is. However, it is required for some advanced usages, like embedding a Resource into some other datastructure.

For example, one can imagine a scope that manages lifetime of several resources:

```scala
sealed trait Scope[F[_]] {
def open[A](ra: Resource[F, A]): F[A]
}


object Scope {
// Implement this. Add context bounds as necessary.
def apply[F[_]]: Resource[F, Scope[F]] = ???
}
```
With such construct, it’s easier to write code that both creates and uses Resources, as you can use regular for-comprehension without needing to wrap it in giant braces and call .use(IO.pure) at the end:

```scala
object Runner extends IOApp {
def run(args: List[String]): IO[ExitCode] = Scope[IO].use { s =>
for {
httpClient <- s.open(BlazeClientBuilder(ExecutionContext.global).resource)
proxy <- Proxy(httpClient)
_  <- s.open(BlazeServerBuilder.bindHttp(80, "localhost").withHttpApp(proxy).resource)
_  <- IO.sleep(10.minutes) // use IO.never to run forever
} yield ExitCode.Success
}
}
```

The requirements are as follows:

- [x] All opened resources should be properly finalized when use is finished, in order of acquisition. 
- [x] Even if one finalizer crashes, the rest should be attempted.
- open should be atomic WRT cancellation: 
  - [ ] resource is either opened or not (I think it is done, but test is not passed)
  - [x] cannot be cancelled in the middle of acquisition.

Both guarantees are provided to you with standard usage of Resource, but allocated removes them.

### [solution](https://github.com/iamnatali/ScalaTraining/blob/master/src/main/scala/TearingResourcesApart.scala)




