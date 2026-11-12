import sbt._
import sbt.Keys._

/**
 * Publication is restricted to the native backend's runtime closure in this
 * fork.
 */
object ForkRelease extends AutoPlugin {
  override def trigger = allRequirements

  val repository      = "ffebr/zio-quill-vertx"
  val groupId         = "io.github.ffebr"
  val registry        = s"https://maven.pkg.github.com/$repository"
  private val modules = Set("quill-engine", "quill-core", "quill-sql", "quill-zio", "quill-vertx-db2-client", "quill-vertx-zio")

  object autoImport {
    val verifyForkPublish = taskKey[Unit]("Check the fork, immutable version, publication destination and module")
  }
  import autoImport._

  def validVersion(value: String): Boolean =
    value.matches("[0-9]+\\.[0-9]+\\.[0-9]+(?:-[0-9A-Za-z]+(?:[.-][0-9A-Za-z]+)*)?") && !value.endsWith("SNAPSHOT")

  def versionSettings: Seq[Setting[_]] =
    sys.props.get("quill.vertx.release.version").toSeq.flatMap { value =>
      require(validVersion(value), "Expected an immutable semantic release version")
      Seq(version := value, isSnapshot := false, Compile / packageDoc / publishArtifact := false)
    }

  override def projectSettings: Seq[Setting[_]] = Seq(
    verifyForkPublish := {
      require(sys.env.get("GITHUB_ACTIONS").contains("true"), "Publish through this fork's GitHub Actions workflow")
      require(sys.env.get("GITHUB_REPOSITORY").contains(repository), s"Publication is restricted to $repository")
      require(organization.value == groupId, s"Publication requires group $groupId")
      require(modules(thisProject.value.id), "Only the native runtime dependency closure may be published")
      require(validVersion(version.value), "Expected an immutable semantic release version")
      require(sys.props.get("quill.vertx.release.version").contains(version.value), "Set quill.vertx.release.version explicitly")
      require(publishTo.value.collect { case target: MavenRepository => target.root }.contains(registry), "Unexpected publication destination")
      require(sys.env.get("GITHUB_TOKEN").exists(_.nonEmpty), "GitHub Actions must supply GITHUB_TOKEN")
    },
    publish := publish.dependsOn(verifyForkPublish).value
  )
}
