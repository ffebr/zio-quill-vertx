package zio.quill.context.vertx

import io.vertx.core.net.impl.{ConnectionBase, NetSocketInternal}
import io.vertx.sqlclient.SqlConnection
import io.vertx.sqlclient.impl.{Connection, SocketConnectionBase, SqlConnectionInternal}
import zio.Task
import zio.ZIO

/**
 * Transport abort for Vert.x 4.5.36 socket-backed SQL clients.
 *
 * This uses version-pinned Vert.x internal APIs to close the physical socket
 * directly, bypassing the SQL command queue. The socket close event retires the
 * physical connection from the pool. This is a transport abort, not a
 * server-confirmed statement cancellation; the statement outcome may be
 * indeterminate if it races with the abort.
 */
private[quill] object VertxConnectionAbort {

  def abort(connection: SqlConnection): Task[Unit] =
    ZIO.attempt(socketFor(connection)).flatMap { socket =>
      closeAndAwaitRetirement(socket)
    }

  private def closeAndAwaitRetirement(socket: NetSocketInternal): Task[Unit] =
    ZIO.attempt {
      val closed = socket match {
        case connectionBase: ConnectionBase => connectionBase.closeFuture()
        case _                              =>
          throw new UnsupportedOperationException(
            "Transport abort requires a Vert.x socket with a close future"
          )
      }
      val requested = socket.close()
      (closed, requested)
    }.flatMap { case (closed, requested) =>
      // NetSocket.close() can complete before channelInactive reaches Vert.x's
      // SQL close handler. Waiting for ConnectionBase.closeFuture() ensures the
      // handler has run and the pool has removed the physical connection first.
      VertxRuntime.fromFuture(closed).flatMap(_ => VertxRuntime.fromFuture(requested)).unit
    }

  private def socketFor(connection: SqlConnection): NetSocketInternal = {
    val initial: Connection = connection match {
      case internal: SqlConnectionInternal => internal.unwrap()
      case _                               =>
        throw new UnsupportedOperationException(
          "Transport abort requires a Vert.x internal SQL connection"
        )
    }

    val raw = unwrapConnection(initial)
    raw match {
      case socketConnection: SocketConnectionBase => socketConnection.socket()
      case _                                      =>
        throw new UnsupportedOperationException(
          "Transport abort is supported only for socket-backed Vert.x SQL clients"
        )
    }
  }

  private def unwrapConnection(connection: Connection): Connection = {
    var current = connection
    var next    = current.unwrap()
    while (next ne current) {
      current = next
      next = current.unwrap()
    }
    current
  }
}
