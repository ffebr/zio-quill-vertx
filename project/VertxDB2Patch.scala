import java.io.File
import java.nio.file.Files
import java.security.MessageDigest
import java.util.jar.JarFile

import sbt._
import sbt.Keys._

/**
 * Builds a full Vert.x DB2 client jar with the CCSID 500 charset constant
 * corrected.
 */
object VertxDB2Patch {
  private val patchedClass    = "io/vertx/db2client/impl/drda/CCSIDConstants.class"
  private val expectedVersion = "4.5.36"
  private val expectedSha256  = "0e42c8d46602908fb2269ebdcc9ffd5cd1496cc39666cbde24ba66460261760e"

  def settings(version: String): Seq[Def.Setting[_]] = Seq(
    autoScalaLibrary := false,
    crossPaths       := false,
    exportJars       := true,
    Compile / packageBin / packageOptions += Package.ManifestAttributes("Automatic-Module-Name" -> "io.vertx.client.sql.db2"),
    Compile / compileOrder := CompileOrder.JavaThenScala,
    // Keep this Java artifact independent of the consuming Scala version.
    libraryDependencies := Seq(
      "io.vertx" % "vertx-db2-client" % version % Provided,
      "io.vertx" % "vertx-sql-client" % version
    ),
    Compile / packageBin / mappings ++= {
      require(version == expectedVersion, s"Vert.x DB2 patch is verified only for $expectedVersion (requested $version)")
      val report   = update.value
      val upstream = report.configurations
        .flatMap(_.modules)
        .filter(m => m.module.organization == "io.vertx" && m.module.name == "vertx-db2-client" && m.module.revision == version)
        .flatMap(_.artifacts)
        .map(_._2)
        .find(_.getName.endsWith(".jar"))
        .getOrElse(sys.error(s"Could not resolve io.vertx:vertx-db2-client:$version"))
      val digest = MessageDigest.getInstance("SHA-256")
      val in     = Files.newInputStream(upstream.toPath)
      try {
        val buffer = new Array[Byte](8192)
        var count  = in.read(buffer)
        while (count >= 0) {
          if (count > 0) digest.update(buffer, 0, count)
          count = in.read(buffer)
        }
      } finally in.close()
      val sha256 = digest.digest().map(b => f"${b & 0xff}%02x").mkString
      require(sha256 == expectedSha256, s"Unexpected Vert.x DB2 $version jar SHA-256: $sha256")

      val jar      = new JarFile(upstream)
      val unpacked = target.value / "upstream-vertx-db2-client"
      try {
        import scala.collection.JavaConverters._
        jar.entries().asScala
          .filterNot(e =>
            e.isDirectory || e.getName == patchedClass || e.getName.equalsIgnoreCase("META-INF/MANIFEST.MF") ||
              e.getName.matches("(?i)META-INF/[^/]+\\.(SF|RSA|DSA|EC)")
          )
          .map { entry =>
            val source = new File(unpacked, entry.getName)
            IO.createDirectory(source.getParentFile)
            val stream = jar.getInputStream(entry)
            try Files.copy(stream, source.toPath, java.nio.file.StandardCopyOption.REPLACE_EXISTING)
            finally stream.close()
            source -> entry.getName
          }.toVector
      } finally jar.close()
    }
  )
}
