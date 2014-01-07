package com.typesafe.jse

import akka.actor._
import scala.concurrent.blocking
import java.io._
import akka.contrib.process.StreamEvents.Ack
import akka.contrib.process.{Sink, Source}
import scala.collection.immutable
import io.apigee.trireme.core._
import scala.collection.JavaConverters._
import com.typesafe.jse.Engine.ExecuteJs
import java.nio.charset.Charset

/**
 * Declares an in-JVM Rhino based JavaScript engine supporting the Node API.
 * The <a href="https://github.com/apigee/trireme#trireme">Trireme</a> project provides this capability.
 * The actor is expected to be associated with a blocking dispatcher as its use of Jdk streams are blocking.
 */
class Trireme(
               stdArgs: immutable.Seq[String],
               stdModulePaths: immutable.Seq[String],
               ioDispatcherId: String
               ) extends Engine {

  // The main objective of this actor implementation is to establish actors for both the execution of
  // Trireme code (Trireme's execution is blocking), and actors for the source of stdio (which is also blocking).
  // This actor is then a conduit of the IO as a result of execution.

  val stdoutOs = new PipedOutputStream()
  val stderrOs = new PipedOutputStream()

  val stdoutIs = new PipedInputStream(stdoutOs)
  val stderrIs = new PipedInputStream(stderrOs)

  def receive = {
    case ExecuteJs(source, args, timeout, timeoutExitValue, modulePaths) =>
      val requester = sender

      // Create an input stream and close it immediately as it isn't going to be used.
      val stdinOs = new PipedOutputStream()
      val stdinIs = new PipedInputStream(stdinOs)

      try {
        val stdinSink = context.actorOf(Sink.props(stdinOs, ioDispatcherId = ioDispatcherId), "stdin")
        val stdoutSource = context.actorOf(Source.props(stdoutIs, self, ioDispatcherId = ioDispatcherId), "stdout")
        val stderrSource = context.actorOf(Source.props(stderrIs, self, ioDispatcherId = ioDispatcherId), "stderr")

        context.become(engineIOHandler(
          stdinSink, stdoutSource, stderrSource,
          requester,
          Ack,
          timeout, timeoutExitValue
        ))

        context.actorOf(TriremeShell.props(
          source,
          stdArgs ++ args,
          stdModulePaths ++ modulePaths,
          stdinIs, stdoutOs, stderrOs
        ), "trireme-shell") ! TriremeShell.Execute

        // We don't need an input stream so close it out straight away.
        stdinSink ! PoisonPill

      } finally {
        blocking {
          closeSafely(stdinIs)
          closeSafely(stdinOs)
        }
      }

  }

  def closeSafely(closable: Closeable): Unit = {
    try {
      closable.close()
    } catch {
      case _: Exception =>
    }
  }

  override def postStop() = {
    // Be paranoid and ensure that all resources are cleared up.
    blocking {
      closeSafely(stderrIs)
      closeSafely(stdoutIs)
      closeSafely(stderrOs)
      closeSafely(stdoutOs)
    }
  }

}

object Trireme {
  /**
   * Give me a Trireme props.
   */
  def props(
             stdArgs: immutable.Seq[String] = Nil,
             stdModulePaths: immutable.Seq[String] = Nil,
             ioDispatcherId: String = "blocking-process-io-dispatcher"
             ): Props = {
    Props(classOf[Trireme], stdArgs, stdModulePaths, ioDispatcherId)
      .withDispatcher(ioDispatcherId)
  }

}


/**
 * Manage the execution of the Trireme shell setting up its environment, running the main entry point
 * and sending its parent the exit code when we're done.
 */
private[jse] class TriremeShell(
                                 source: File,
                                 args: immutable.Seq[String],
                                 modulePaths: immutable.Seq[String],
                                 stdinIs: InputStream,
                                 stdoutOs: OutputStream,
                                 stderrOs: OutputStream
                                 ) extends Actor with ActorLogging {

  import TriremeShell._

  val moduleBase = source.getParentFile.getCanonicalFile
  val sourcePath = source.getCanonicalPath
  val userModulePaths = immutable.Seq(moduleBase.getCanonicalPath) ++ modulePaths

  val env = NodeEngine.nodePathEnv(userModulePaths)
  val nodeEnv = new NodeEnvironment()
  val sandbox = new Sandbox()
  sandbox.setStdin(stdinIs)
  sandbox.setStdout(stdoutOs)
  sandbox.setStderr(stderrOs)

  def receive = {
    case Execute =>

      if (log.isDebugEnabled) {
        log.debug("Invoking Trireme with {}", args)
      }

      val script = nodeEnv.createScript(source.getName, source, args.toArray)
      script.setSandbox(sandbox)
      script.setEnvironment(env.asJava)

      val senderSel = sender.path
      val senderSys = context.system
      script.execute.setListener(new ScriptStatusListener {
        def onComplete(script: NodeScript, status: ScriptStatus): Unit = {
          stdoutOs.close()
          stderrOs.close()
          senderSys.actorSelection(senderSel) ! status.getExitCode
        }
      })
  }

}

private[jse] object TriremeShell {
  def props(
             moduleBase: File,
             args: immutable.Seq[String],
             modulePaths: immutable.Seq[String],
             stdinIs: InputStream,
             stdoutOs: OutputStream,
             stderrOs: OutputStream
             ): Props = {
    Props(classOf[TriremeShell], moduleBase, args, modulePaths, stdinIs, stdoutOs, stderrOs)
  }

  case object Execute

}