package org.apache.spark.sql.ammonitesparkinternals

import java.io.File
import java.net.{InetAddress, URI, URL, URLClassLoader}
import java.nio.charset.StandardCharsets
import java.nio.file.{Files, Paths}

import ammonite.repl.api.ReplAPI
import ammonite.interp.api.InterpAPI
import coursierapi.Dependency
import org.apache.spark.SparkContext
import org.apache.spark.scheduler.{SparkListener, SparkListenerApplicationEnd}
import org.apache.spark.sql.SparkSession
import org.apache.spark.ui.ConsoleProgressBar

import scala.collection.JavaConverters._

object AmmoniteSparkSessionBuilder {

  private def prettyDir(dir: String): String = {
    val home = sys.props("user.home")

    if (dir.startsWith(home))
      "~" + dir.stripPrefix(home)
    else
      dir
  }

  private def confEnvVars: Seq[String] = {

    val sparkBinaryVersion =
      org.apache.spark.SPARK_VERSION
        .split('.')
        .take(2)
        .mkString(".")

    Seq(
      "SPARK_CONF_DEFAULTS",
      s"SPARK_CONF_DEFAULTS_${sparkBinaryVersion.filter(_ != '.')}"
    )
  }

  def shouldPassToSpark(classpathEntry: URL): Boolean = {
    val isJarFile = classpathEntry.getProtocol == "file" &&
      classpathEntry.getPath.endsWith(".jar") &&
      !classpathEntry.getPath.endsWith("-sources.jar")
    // Should be useful in almond, if one generates a launcher for it with the --standalone option (to embed the
    // dependencies in the launcher).
    // Since https://github.com/coursier/coursier/pull/1024 in coursier, these embedded dependencies should have URLs
    // like jar:file:/…, with possible multiple '!/' in them.
    val isJarInJar = classpathEntry.getProtocol == "jar" &&
      classpathEntry.getFile.endsWith(".jar!/") &&
      !classpathEntry.getPath.endsWith("-sources.jar!/")
    isJarFile || isJarInJar
  }

  def classpath(cl: ClassLoader): Stream[java.net.URL] = {
    if (cl == null)
      Stream()
    else {
      val cp = cl match {
        case u: java.net.URLClassLoader => u.getURLs.toStream
        case _ => Stream()
      }

      cp #::: classpath(cl.getParent)
    }
  }

  private lazy val javaDirs = {
    val l = Seq(sys.props("java.home")) ++
      sys.props.get("java.ext.dirs").toSeq.flatMap(_.split(File.pathSeparator)).filter(_.nonEmpty) ++
      sys.props.get("java.endorsed.dirs").toSeq.flatMap(_.split(File.pathSeparator)).filter(_.nonEmpty)
    l.map(_.stripSuffix("/") + "/")
  }

  def isJdkJar(uri: URI): Boolean =
    uri.getScheme == "file" && {
      val path = new File(uri).getAbsolutePath
      javaDirs.exists(path.startsWith)
    }

  def forceProgressBars(sc: SparkContext): Boolean =
    sc.progressBar.nonEmpty || {
      try {
        val method = classOf[org.apache.spark.SparkContext].getDeclaredMethod("org$apache$spark$SparkContext$$_progressBar_$eq", classOf[Option[Any]])
        method.setAccessible(true)
        method.invoke(sc, Some(new ConsoleProgressBar(sc)))
        true
      } catch {
        case _: NoSuchMethodException =>
          false
      }
    }

  def userAddedClassPath(cl: ClassLoader): Stream[Seq[URL]] =
    if (cl == null) Stream.empty
    else
      cl match {
        case cl: URLClassLoader if cl.getClass.getName == "ammonite.runtime.SpecialClassLoader" =>
          cl.getURLs.toSeq #:: userAddedClassPath(cl.getParent)
        case _ =>
          Stream.empty
      }

}

class AmmoniteSparkSessionBuilder
 (implicit
   interpApi: InterpAPI,
   replApi: ReplAPI
 ) extends SparkSession.Builder {

  private val options0: scala.collection.Map[String, String] = {

    def fieldVia(name: String): Option[scala.collection.mutable.HashMap[String, String]] =
      try {
        val f = classOf[SparkSession.Builder].getDeclaredField(name)
        f.setAccessible(true)
        Some(f.get(this).asInstanceOf[scala.collection.mutable.HashMap[String, String]])
      } catch {
        case _: NoSuchFieldException =>
          None
      }

    fieldVia("org$apache$spark$sql$SparkSession$Builder$$options")
      .orElse(fieldVia("options"))
      .getOrElse {
        println("Warning: can't read SparkSession Builder options (options field not found)")
        Map.empty[String, String]
      }
  }

  private def init(): Unit = {

    for {
      envVar <- AmmoniteSparkSessionBuilder.confEnvVars
      path <- sys.env.get(envVar)
    } {
      println(s"Loading spark conf from ${AmmoniteSparkSessionBuilder.prettyDir(path)}")
      loadConf(path)
    }

    config("spark.submit.deployMode", "client")

    for (master0 <- sys.env.get("SPARK_MASTER"))
      master(master0)
  }

  init()

  def loadConf(path: String, ignore: Set[String] = Set()): this.type = {

    val content = new String(Files.readAllBytes(Paths.get(path)), StandardCharsets.UTF_8)

    val extraProps = content
      .linesIterator
      .toVector
      .filter(!_.startsWith("#"))
      .map(_.split("\\s+", 2))
      .collect { case Array(k, v) => k -> v }

    for ((k, v) <- extraProps if !ignore(k))
      config(k, v)

    this
  }

  private var forceProgressBars0 = false

  def progressBars(force: Boolean = true): this.type = {
    forceProgressBars0 = force
    this
  }

  var classServerOpt = Option.empty[AmmoniteClassServer]

  private def isYarn(): Boolean =
    options0.get("spark.master").exists(_.startsWith("yarn"))

  private def hiveSupport(): Boolean =
    options0.get("spark.sql.catalogImplementation").contains("hive")

  private def host(): String =
    options0.get("spark.driver.host")
      .orElse(sys.env.get("HOST"))
      .getOrElse(InetAddress.getLocalHost.getHostAddress)

  private def bindAddress(): String =
    options0.getOrElse("spark.driver.bindAddress", host())

  private def loadExtraDependencies(): Unit = {

    var deps = List.empty[(String, Dependency)]

    if (hiveSupport() && !SparkDependencies.sparkHiveFound())
      deps = ("spark-hive", SparkDependencies.sparkHiveDependency) :: deps

    if (!SparkDependencies.sparkExecutorClassLoaderFound())
      deps = ("spark-stubs", SparkDependencies.stubsDependency) :: deps

    if (isYarn() && !SparkDependencies.sparkYarnFound())
      deps = ("spark-yarn", SparkDependencies.sparkYarnDependency) :: deps

    if (deps.nonEmpty) {
      println(s"Loading ${deps.map(_._1).mkString(", ")}")
      interpApi.load.ivy(deps.map(_._2): _*)
    }
  }

  override def getOrCreate(): SparkSession = {

    loadExtraDependencies()

    val (sessionJars, cp) = Option(replApi) match {
      case None =>

        def firstNonSpecialClassLoader(cl: ClassLoader): Option[ClassLoader] =
          if (cl == null)
            None
          else if (cl.getClass.getName == "ammonite.runtime.SpecialClassLoader")
            firstNonSpecialClassLoader(cl.getParent)
          else
            Some(cl)

        val cl = Thread.currentThread().getContextClassLoader

        val sessionJars0 = AmmoniteSparkSessionBuilder.userAddedClassPath(cl).toVector.flatten
          .filter(AmmoniteSparkSessionBuilder.shouldPassToSpark)
          .map(_.toURI)

        val firstNonSpecialClassLoader0 = firstNonSpecialClassLoader(cl).getOrElse(???)
        val cp = AmmoniteSparkSessionBuilder.classpath(firstNonSpecialClassLoader0)

        (sessionJars0, cp)

      case Some(replApi0) =>

        val sessionJars0 = replApi0
          .sess
          .frames
          .flatMap(_.classpath)
          .filter(AmmoniteSparkSessionBuilder.shouldPassToSpark)
          .map(_.toURI)

        val cp = AmmoniteSparkSessionBuilder.classpath(
          replApi0
            .sess
            .frames
            .last
            .classloader
            .getParent
        )

        (sessionJars0, cp)
    }

    val baseJars = cp
      .map(_.toURI)
      // assuming the JDK on the YARN machines already have those
      .filter(u => !AmmoniteSparkSessionBuilder.isJdkJar(u))
      .toVector

    val jars = (baseJars ++ sessionJars).distinct

    val sparkJars = sys.env.get("SPARK_HOME") match {
      case None =>
        println("Getting spark JARs")
        SparkDependencies.sparkJars(interpApi.repositories(), interpApi.resolutionHooks, Nil)
      case Some(sparkHome) =>
        // Loose attempt at using the scala JARs already loaded in Ammonite,
        // rather than ones from the spark distribution.
        val fromBaseCp = jars.filter { f =>
          f.toASCIIString.contains("/scala-library") ||
            f.toASCIIString.contains("/scala-reflect") ||
            f.toASCIIString.contains("/scala-compiler")
        }
        val fromSparkDistrib = Files.list(Paths.get(sparkHome).resolve("jars"))
          .iterator()
          .asScala
          .toSeq
          .filter { p =>
            val name = p.getFileName.toString
            !name.startsWith("scala-library-") &&
              !name.startsWith("scala-reflect-") &&
              !name.startsWith("scala-compiler-")
          }
          .map(_.toAbsolutePath.toUri)

        fromBaseCp ++ fromSparkDistrib
    }

    if (isYarn())
      config("spark.yarn.jars", sparkJars.map(_.toASCIIString).mkString(","))

    config("spark.jars", jars.filterNot(sparkJars.toSet).map(_.toASCIIString).mkString(","))

    val classServer = new AmmoniteClassServer(
      host(),
      bindAddress(),
      options0.get("spark.repl.class.port").fold(AmmoniteClassServer.randomPort())(_.toInt),
      replApi.sess.frames
    )
    classServerOpt = Some(classServer)

    config("spark.repl.class.uri", classServer.uri.toString)

    if (!options0.contains("spark.ui.port"))
      config("spark.ui.port", AmmoniteClassServer.availablePortFrom(4040).toString)

    if (isYarn() && !options0.contains("spark.yarn.queue"))
      for (queue <- sys.env.get("SPARK_YARN_QUEUE"))
        config("spark.yarn.queue", queue)

    def coreSiteFound =
      Thread.currentThread().getContextClassLoader.getResource("core-site.xml") != null

    def hiveSiteFound =
      Thread.currentThread().getContextClassLoader.getResource("hive-site.xml") != null

    if (isYarn() && !coreSiteFound) {

      val hadoopConfDirOpt = sys.env.get("HADOOP_CONF_DIR")
        .orElse(sys.env.get("YARN_CONF_DIR"))
        .orElse(Some(new File("/etc/hadoop/conf")).filter(_.isDirectory).map(_.getAbsolutePath))

      hadoopConfDirOpt match {
        case None =>
          println(
            "Warning: core-site.xml not found in the classpath, and no hadoop conf found via HADOOP_CONF_DIR, " +
              "YARN_CONF_DIR, or at /etc/hadoop/conf"
          )
        case Some(dir) =>
          println(s"Adding Hadoop conf dir ${AmmoniteSparkSessionBuilder.prettyDir(dir)} to classpath")
          interpApi.load.cp(os.Path(dir))
      }
    }

    if (hiveSupport() && !hiveSiteFound) {

      val hiveConfDirOpt = sys.env.get("HIVE_CONF_DIR")

      hiveConfDirOpt match {
        case None =>
          println("Warning: hive-site.xml not found in the classpath, and no Hive conf found via HIVE_CONF_DIR")
        case Some(dir) =>
          println(s"Adding Hive conf dir ${AmmoniteSparkSessionBuilder.prettyDir(dir)} to classpath")
          interpApi.load.cp(os.Path(dir))
      }
    }

    println("Creating SparkSession")
    val session = super.getOrCreate()

    interpApi.beforeExitHooks += { v =>
      if (!session.sparkContext.isStopped)
        session.sparkContext.stop()
      v
    }

    session.sparkContext.addSparkListener(
      new SparkListener {
        override def onApplicationEnd(applicationEnd: SparkListenerApplicationEnd) =
          for (s <- classServerOpt) {
            s.stop()
            classServerOpt = None
          }
      }
    )

    if (forceProgressBars0)
      AmmoniteSparkSessionBuilder.forceProgressBars(session.sparkContext)

    session
  }
}
