package notebook.util

import java.io.File

import com.datafellas.utils.Deps
import coursier._
import org.slf4j.LoggerFactory
import org.sonatype.aether.repository.RemoteRepository

import scala.util.Try
import scalaz.\/
import scalaz.concurrent.Task


// FIXME: do we need a unique tmp dir per Kernel session !?
// FIXME: remove aether leftovers
object CoursierDeps {


  private[this] val log = LoggerFactory.getLogger(this.getClass)

  def makeRepositories(remotes: List[RemoteRepository]): Seq[Repository] = {
    //    val repositories_example: Seq[Repository] = Seq(
    //      Cache.ivy2Local,
    //      MavenRepository("https://repo1.maven.org/maven2")
    //    )
    remotes.map { remote =>
      val auth = Option(remote.getAuthentication).map(auth => coursier.core.Authentication(auth.getUsername, auth.getPassword))
      MavenRepository(remote.getUrl, authentication = auth)
    }
  }

  def script(cp: String,
             remotes: List[RemoteRepository],
             repo: java.io.File,
             sparkVersion: String): Try[List[String]] = {
    val (repositories, artifacts) = parseCoursierDependencies(cp, remotes, sparkVersion)

    fetchLocalJars(repositories, artifacts)
  }

  /**
    * convert aether crap to coursier
    * */
  private[util] def parseCoursierDependencies(cp: String, remotes: List[RemoteRepository], sparkVersion: String): (Seq[Repository], Set[Dependency]) = {
    val (includes, excludes) = Deps.parse(cp, sparkVersion)
    val repositories = makeRepositories(remotes)
    // P.S. this excludes transitive deps ignoring the `version`, however it's fine
    // as sbt-dependency-manager in 2.10 also ignores it...
    val exclusions: Set[(String, String)] = excludes
      .filter(rule => rule.group.isDefined || rule.artifact.isDefined)
      .map(rule => (rule.group.getOrElse("*"), rule.artifact.getOrElse("*")))

    val artifacts = includes.map { dep =>
      coursier.Dependency(
        module = Module(organization = dep.group, name = dep.artifact),
        version = dep.version,
        exclusions = exclusions,
        attributes = Attributes(classifier = dep.classifier.getOrElse(""))
      )
    }
    (repositories, artifacts)
  }

  private def fetchLocalJars(repositories: Seq[Repository], deps: Set[Dependency]): Try[List[String]] = {
    val resolutionStart: Resolution = coursier.Resolution(deps)
    val fetch = Fetch.from(repositories, Cache.fetch())
    val resolution = resolutionStart.process.run(fetch).run
    resolution.metadataErrors.foreach { resoveError =>
      log.error("Cannot resolve custom dependency: ", resoveError)
    }
    val localArtifacts: Seq[FileError \/ File] = Task.gatherUnordered(
      resolution.artifacts.map(Cache.file(_).run)
    ).unsafePerformSync

    collectFetchedFiles(localArtifacts.filter(_.isRight)).foreach { artifact =>
      log.info("Fetched artifact to:" + artifact)
    }

    Try {
      collectFetchedFiles(localArtifacts)
        .map(_.getCanonicalFile.getAbsolutePath.toString)
        .toList
    }
  }

  def collectFetchedFiles(localArtifacts: Seq[FileError \/ File]): Seq[File] = {
    localArtifacts.map { artifactOrErr =>
      artifactOrErr.getOrElse(throw new RuntimeException("Dependency resolution failed"))
    }.filterNot(_.getPath.endsWith(".pom"))
  }
}
