import sbt._
import Keys._

object MyBuild extends Build {
  // If only it didn't take me an hour to figure out how to log something
  private def distsMessage() {
    scala.Console.err.println("No distributions found: set SCALA_DISTS to a path containing scala distributions.")
  }
  
  // Set this to path to dists
  val distPath = sys.env.getOrElse("SCALA_DISTS", ".")

  override def projects = root +: benchProjects

  lazy val root = (
    Project("root", file("."))
    settings(aggregate in run := true,
             parallelExecution := false, // I hate this, but run needs to be serial, so compile has to follow as well
             cancelable := true,
             sources in Compile := Nil,
             run in Compile <<= inputTask { _ map { ignore => () } })
    aggregate(benchProjects map Reference.projectToRef: _*)
  )

  // Auto-find scala distributions in the dists dir
  lazy val benchProjects: Seq[Project] = (
    file(distPath).listFiles.toList
      filter  (_ / "lib/scala-library.jar" exists)
      flatMap (f => normalAndOptimised(f.name)) match {
        case Nil    => distsMessage() ; Nil
        case xs     => xs
      }
  )

  def normalAndOptimised(dir: String) = Seq(
    benchProject(dir, noAssertions ++ scalaAt(dir)),
    benchProject(dir + "Opt", noAssertions ++ scalaAt(dir) ++ optimise)
  )

  def benchProject(name: String, extraSettings: Seq[Setting[_]] = Seq.empty) =
    Project(name, file(".")) settings(myDefaultSettings:_*) settings(extraSettings ++ targetDir: _*)

  def targetDir = Seq(target <<= (baseDirectory, thisProject, name) apply (_ / "target" / _.id / _))

  def scalaAt(dir: String) = Seq(scalaHome := Some(file(distPath) / dir))

  val optimise = scalacOptions += "-optimise"
  val noAssertions = scalacOptions += "-Xdisable-assertions"

  val myDefaultSettings: Seq[Setting[_]] = Seq(
    organization := "com.example",
    name := "scala-benchmarking-template",
    version := "1.0.0-SNAPSHOT",
    scalaVersion := "2.9.1",
    libraryDependencies ++= Seq(
        "com.google.code.java-allocation-instrumenter" % "java-allocation-instrumenter" % "2.0",
        "com.google.code.caliper" % "caliper" % "1.0-SNAPSHOT",
        "com.google.code.gson" % "gson" % "1.7.1"
    ),
    resolvers += "sonatypeSnapshots" at "http://oss.sonatype.org/content/repositories/snapshots",

    cancelable := true,

    parallelExecution := false, // I hate this, but run needs to be serial, so compile has to follow as well

    runner in Compile in run <<= (thisProject, taskTemporaryDirectory, scalaInstance, baseDirectory, javaOptions, outputStrategy, javaHome, connectInput) map {
      (tp, tmp, si, base, options, strategy, javaHomeDir, connectIn) =>
        new MyRunner(tp.id, ForkOptions(scalaJars = si.jars, javaHome = javaHomeDir, connectInput = connectIn, outputStrategy = strategy,
          runJVMOptions = options, workingDirectory = Some(base)) )
    }
  )
}

class MyRunner(subproject: String, config: ForkScalaRun) extends sbt.ScalaRun {
  def run(mainClass: String, classpath: Seq[File], options: Seq[String], log: Logger): Option[String] = {
    log.info("Running " + subproject + " " + mainClass + " " + options.mkString(" "))
                                                                                                  
    val javaOptions = classpathOption(classpath) ::: mainClass :: options.toList                 
    val strategy = config.outputStrategy getOrElse LoggedOutput(log)                              
    val process =  Fork.java.fork(config.javaHome, 
                                  config.runJVMOptions ++ javaOptions,
                                  config.workingDirectory,
                                  Map.empty,
                                  config.connectInput,
                                  strategy)
    def cancel() = {                                                                              
      log.warn("Run canceled.")                                                             
      process.destroy()                                                                     
      1                                                                                     
    }                                                                                             
    val exitCode = try process.exitValue() catch { case e: InterruptedException => cancel() }     
    processExitCode(exitCode, "runner")                                                           
  }                                                                                                     
  private def classpathOption(classpath: Seq[File]) = "-classpath" :: Path.makeString(classpath) :: Nil 
  private def processExitCode(exitCode: Int, label: String) = {                                                                                                     
    if(exitCode == 0) None                                                                                  
    else Some("Nonzero exit code returned from " + label + ": " + exitCode)                    
  }                                                                                                     
}   

