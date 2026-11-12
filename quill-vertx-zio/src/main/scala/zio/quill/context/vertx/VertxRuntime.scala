package zio.quill.context.vertx

import io.vertx.core.{AsyncResult, Future}
import io.vertx.sqlclient.{Pool, SqlConnection}
import java.util.concurrent.atomic.{AtomicBoolean, AtomicReference}
import scala.util.control.NonFatal
import zio.{Runtime, Scope, Task, UIO, Unsafe, ZIO}

/** Bridges Vert.x callbacks into ZIO without waiting on the calling thread. */
object VertxRuntime {

  /**
   * Convert a Vert.x future; synchronous construction throws and async failures
   * are typed.
   */
  def fromFuture[A](future: => Future[A]): Task[A] =
    ZIO.async[Any, Throwable, A] { callback =>
      val completed                              = new AtomicBoolean(false)
      def complete(result: AsyncResult[A]): Unit =
        if (completed.compareAndSet(false, true)) {
          callback(ZIO.fromEither(resultOutcome(result)))
        }

      try future.onComplete((result: AsyncResult[A]) => complete(result))
      catch {
        case NonFatal(error) => if (completed.compareAndSet(false, true)) callback(ZIO.fail(error))
      }
    }

  /**
   * Convert an acquisition future and release a successful value if
   * interruption wins before the waiting fiber accepts it. The canceler never
   * waits for the native callback. Callback delivery and cancellation share one
   * atomic state, so the release callback runs at most once.
   *
   * Once this effect returns successfully, ownership belongs to its caller. For
   * long-lived native resources prefer [[acquireConnection]], which installs a
   * scope finalizer before beginning the interruptible acquisition.
   */
  def fromFutureScoped[A](future: => Future[A])(release: A => UIO[Unit]): Task[A] =
    ZIO.runtime[Any].flatMap { runtime =>
      ZIO.asyncInterrupt[Any, Throwable, A] { callback =>
        val state = new AtomicReference[BridgeState[A]](BridgePending)

        def cleanup(value: A): Unit = forkCleanup(runtime, release(value))

        def complete(outcome: Either[Throwable, A]): Unit = {
          var done = false
          while (!done) {
            state.get() match {
              case BridgePending =>
                if (state.compareAndSet(BridgePending, BridgeDelivered(outcome))) {
                  callback(ZIO.fromEither(outcome))
                  done = true
                }
              case BridgeCanceled =>
                if (state.compareAndSet(BridgeCanceled, BridgeLate(outcome))) {
                  outcome.foreach(cleanup)
                  done = true
                }
              case _ => done = true
            }
          }
        }

        try future.onComplete { result: AsyncResult[A] =>
            complete(resultOutcome(result))
          }
        catch {
          case NonFatal(error) => complete(Left(error))
        }

        Left(
          ZIO.succeed {
            var canceled = false
            while (!canceled) {
              state.get() match {
                case BridgePending                      => canceled = state.compareAndSet(BridgePending, BridgeCanceled)
                case current @ BridgeDelivered(outcome) =>
                  if (state.compareAndSet(current, BridgeLate(outcome))) {
                    outcome.foreach(cleanup)
                    canceled = true
                  }
                case _ => canceled = true
              }
            }
          }
        )
      }
    }

  /**
   * Register connection ownership in the scope before starting an interruptible
   * pool acquisition. A late successful callback closes itself asynchronously.
   */
  def acquireConnection(pool: Pool): ZIO[Scope, Throwable, SqlConnection] =
    ZIO.runtime[Any].flatMap { runtime =>
      ZIO.acquireRelease(ZIO.succeed(new ConnectionOwner(runtime)))(_.close).flatMap(_.acquire(pool))
    }

  private sealed trait BridgeState[+A]
  private case object BridgePending                                          extends BridgeState[Nothing]
  private final case class BridgeDelivered[A](outcome: Either[Throwable, A]) extends BridgeState[A]
  private case object BridgeCanceled                                         extends BridgeState[Nothing]
  private final case class BridgeLate[A](outcome: Either[Throwable, A])      extends BridgeState[A]

  private def forkCleanup(runtime: Runtime[Any], cleanup: UIO[Unit]): Unit =
    Unsafe.unsafe { implicit unsafe => runtime.unsafe.fork(cleanup); () }

  private def resultOutcome[A](result: AsyncResult[A]): Either[Throwable, A] =
    if (result.succeeded()) Right(result.result()) else Left(result.cause())

  private final class ConnectionOwner(runtime: Runtime[Any]) {
    private sealed trait State
    private case object Waiting                                  extends State
    private final case class Acquired(connection: SqlConnection) extends State
    private case object Failed                                   extends State
    private case object Closed                                   extends State
    private case object LateHandled                              extends State

    private val state = new AtomicReference[State](Waiting)

    def acquire(pool: Pool): Task[SqlConnection] =
      ZIO.asyncInterrupt[Any, Throwable, SqlConnection] { callback =>
        def complete(outcome: Either[Throwable, SqlConnection]): Unit = {
          var done = false
          while (!done) {
            state.get() match {
              case Waiting =>
                outcome match {
                  case Right(connection) =>
                    if (state.compareAndSet(Waiting, Acquired(connection))) {
                      callback(ZIO.succeed(connection))
                      done = true
                    }
                  case Left(error) =>
                    if (state.compareAndSet(Waiting, Failed)) {
                      callback(ZIO.fail(error))
                      done = true
                    }
                }
              case Closed =>
                if (state.compareAndSet(Closed, LateHandled)) {
                  outcome.foreach(connection => forkCleanup(runtime, closeConnection(connection)))
                  done = true
                }
              case _ => done = true
            }
          }
        }

        try pool.getConnection().onComplete { result: AsyncResult[SqlConnection] =>
            complete(resultOutcome(result))
          }
        catch {
          case NonFatal(error) => complete(Left(error))
        }

        Left(close)
      }

    def close: UIO[Unit] =
      ZIO.suspendSucceed {
        var acquired: Option[SqlConnection] = None
        var closed                          = false
        while (!closed) {
          state.get() match {
            case Waiting                        => closed = state.compareAndSet(Waiting, Closed)
            case current @ Acquired(connection) =>
              if (state.compareAndSet(current, Closed)) {
                acquired = Some(connection)
                closed = true
              }
            case Failed => closed = state.compareAndSet(Failed, Closed)
            case _      => closed = true
          }
        }
        acquired.fold[UIO[Unit]](ZIO.unit)(closeConnection)
      }
  }

  private def closeConnection(connection: SqlConnection): UIO[Unit] =
    fromFuture(connection.close()).ignore
}
