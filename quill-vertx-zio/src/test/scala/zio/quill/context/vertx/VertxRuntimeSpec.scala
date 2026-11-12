package zio.quill.context.vertx

import io.vertx.core.{Future, Promise}
import io.vertx.sqlclient.{Pool, SqlConnection}
import java.lang.reflect.{InvocationHandler, Method, Proxy}
import java.util.concurrent.{CountDownLatch, TimeUnit}
import java.util.concurrent.atomic.AtomicInteger
import org.scalatest.matchers.must.Matchers
import org.scalatest.wordspec.AnyWordSpec
import zio.{Runtime, Unsafe, ZIO}

class VertxRuntimeSpec extends AnyWordSpec with Matchers {
  private def run[A](effect: ZIO[Any, Throwable, A]): A =
    Unsafe.unsafe { implicit unsafe => Runtime.default.unsafe.run(effect).getOrThrowFiberFailure() }

  private def await(latch: CountDownLatch): ZIO[Any, Throwable, Unit] =
    ZIO.attemptBlocking {
      if (!latch.await(5, TimeUnit.SECONDS)) throw new AssertionError("timed out waiting for callback")
    }

  private def connection(closeCount: AtomicInteger, closed: CountDownLatch): SqlConnection =
    Proxy
      .newProxyInstance(
        classOf[SqlConnection].getClassLoader,
        Array(classOf[SqlConnection]),
        new InvocationHandler {
          override def invoke(proxy: Object, method: Method, args: Array[Object]): Object =
            method.getName match {
              case "close" =>
                closeCount.incrementAndGet()
                closed.countDown()
                Future.succeededFuture[Void](null)
              case "toString" => "test-sql-connection"
              case "hashCode" => Integer.valueOf(System.identityHashCode(proxy))
              case "equals"   => java.lang.Boolean.valueOf(proxy eq args(0))
              case other      => throw new UnsupportedOperationException(other)
            }
        }
      )
      .asInstanceOf[SqlConnection]

  private def pool(
    acquisition: Promise[SqlConnection],
    requested: CountDownLatch
  ): Pool =
    Proxy
      .newProxyInstance(
        classOf[Pool].getClassLoader,
        Array(classOf[Pool]),
        new InvocationHandler {
          override def invoke(proxy: Object, method: Method, args: Array[Object]): Object =
            method.getName match {
              case "getConnection" =>
                requested.countDown()
                acquisition.future()
              case "toString" => "test-pool"
              case "hashCode" => Integer.valueOf(System.identityHashCode(proxy))
              case "equals"   => java.lang.Boolean.valueOf(proxy eq args(0))
              case other      => throw new UnsupportedOperationException(other)
            }
        }
      )
      .asInstanceOf[Pool]

  "VertxRuntime.fromFuture" should {
    "capture a synchronous exception while constructing the future" in {
      val expected = new IllegalStateException("future construction")
      val result   = Unsafe.unsafe { implicit unsafe =>
        Runtime.default.unsafe.run(VertxRuntime.fromFuture[Int](throw expected).either).getOrThrowFiberFailure()
      }
      result mustBe Left(expected)
    }

    "preserve asynchronous failures and successful values" in {
      val failed  = new IllegalArgumentException("callback failure")
      val failure = run(VertxRuntime.fromFuture(Future.failedFuture[Int](failed)).either)
      val success = run(VertxRuntime.fromFuture(Future.succeededFuture(42)))
      failure mustBe Left(failed)
      success mustBe 42
    }
  }

  "VertxRuntime.fromFutureScoped" should {
    "interrupt promptly when the future never completes" in {
      val promise     = Promise.promise[String]()
      val requested   = new CountDownLatch(1)
      val effect      = VertxRuntime.fromFutureScoped { requested.countDown(); promise.future() }(_ => ZIO.unit)
      val interrupted = run(for {
        fiber <- effect.fork
        _     <- await(requested)
        exit  <- fiber.interrupt
      } yield exit.isInterrupted)
      interrupted mustBe true
    }

    "release a value that arrives after interruption exactly once" in {
      val promise         = Promise.promise[String]()
      val requested       = new CountDownLatch(1)
      val releaseCount    = new AtomicInteger(0)
      val releaseObserved = new CountDownLatch(1)
      val effect          = VertxRuntime.fromFutureScoped { requested.countDown(); promise.future() } { _ =>
        ZIO.succeed { releaseCount.incrementAndGet(); releaseObserved.countDown(); () }
      }
      val released = run(for {
        fiber <- effect.fork
        _     <- await(requested)
        _     <- fiber.interrupt
        _     <- ZIO.succeed(promise.complete("late"))
        _     <- await(releaseObserved)
      } yield releaseCount.get())
      released mustBe 1
    }

    "return a normally completed value without releasing it" in {
      val releaseCount = new AtomicInteger(0)
      val value        = run(
        VertxRuntime.fromFutureScoped(Future.succeededFuture("ready"))(_ =>
          ZIO.succeed { releaseCount.incrementAndGet(); () }
        )
      )
      value mustBe "ready"
      releaseCount.get() mustBe 0
    }
  }

  "VertxRuntime.acquireConnection" should {
    "register ownership before interruptible acquisition and close a late connection" in {
      val acquisition    = Promise.promise[SqlConnection]()
      val requested      = new CountDownLatch(1)
      val closeCount     = new AtomicInteger(0)
      val closed         = new CountDownLatch(1)
      val testPool       = pool(acquisition, requested)
      val testConnection = connection(closeCount, closed)

      val result = run(ZIO.scoped(for {
        // This fiber shares the outer scope, so the test proves cancellation
        // closes ownership before the parent scope's finalizers run.
        fiber <- VertxRuntime.acquireConnection(testPool).unit.fork
        _     <- await(requested)
        exit  <- fiber.interrupt
        _     <- ZIO.succeed(acquisition.complete(testConnection))
        _     <- await(closed)
      } yield exit.isInterrupted && closeCount.get() == 1))

      result mustBe true
    }
  }
}
