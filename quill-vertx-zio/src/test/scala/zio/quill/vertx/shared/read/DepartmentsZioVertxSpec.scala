package zio.quill.vertx.shared.read

import zio.quill.vertx.VertxPostgresSpecSupport
import zio.quill.Delete

import zio.quill.context.sql.base.DepartmentsSpec

class DepartmentsZioVertxSpec extends DepartmentsSpec with VertxPostgresSpecSupport {

  import context._

  override def beforeAll(): Unit = {
    super.beforeAll()
    unsafeRun(context.transaction {
      for {
        // Use fresh fixture relations with the original postgres-schema.sql columns.
        // These shared examples assert insertion order without ORDER BY. PostgreSQL
        // retains column statistics even after TRUNCATE, which can select a different
        // join plan and reverse this list for both the JDBC and native runners.
        _ <- context.run(sql"DROP TABLE Department, Employee, Task".as[Delete[Department]])
        _ <- context.run(sql"CREATE TABLE Department (dpt VARCHAR(255))".as[Delete[Department]])
        _ <- context.run(sql"CREATE TABLE Employee (emp VARCHAR(255), dpt VARCHAR(255), salary INT)".as[Delete[Department]])
        _ <- context.run(sql"CREATE TABLE Task (emp VARCHAR(255), tsk VARCHAR(255))".as[Delete[Department]])
        _ <- context.run(liftQuery(departmentEntries).foreach(p => departmentInsert(p)))
        _ <- context.run(liftQuery(employeeEntries).foreach(p => employeeInsert(p)))
        _ <- context.run(liftQuery(taskEntries).foreach(p => taskInsert(p)))
      } yield ()
    })
  }

  "Example 8 - nested naive" in {
    unsafeRun(context.run(`Example 8 expertise naive`(lift(`Example 8 param`)))) mustEqual `Example 8 expected result`
  }

  "Example 9 - nested db" in {
    unsafeRun(context.run(`Example 9 expertise`(lift(`Example 9 param`)))) mustEqual `Example 9 expected result`
  }
}
