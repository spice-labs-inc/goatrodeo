package io.spicelabs.goatrodeo.util

import io.spicelabs.rodeocomponents.APIS.containers.ContainerItem
import io.spicelabs.rodeocomponents.APIS.containers.ContainerFactory
import java.io.InputStream
import java.io.FileInputStream
import io.spicelabs.rodeocomponents.APIS.containers.ContainerHandler
import io.spicelabs.rodeocomponents.APIS.containers.HandlerResult
import scala.jdk.StreamConverters._
import javax.naming.OperationNotSupportedException
import java.util.concurrent.atomic.AtomicReference
import java.nio.file.Path
import io.spicelabs.rodeocomponents.APIS.containers.EmptyContainerHandler
import scala.util.Try
import scala.util.Failure
import scala.util.Success
import java.io.File
import java.util.zip.ZipFile
import scala.jdk.CollectionConverters.IteratorHasAsScala
import com.palantir.isofilereader.isofilereader.IsoFileReader
import scala.jdk.CollectionConverters.ListHasAsScala
import java.io.BufferedInputStream
import org.apache.commons.compress.archivers.ArchiveInputStream
import org.apache.commons.compress.archivers.ArchiveStreamFactory
import org.apache.commons.compress.archivers.ArchiveEntry
import org.apache.commons.compress.compressors.CompressorStreamFactory
import org.apache.commons.compress.compressors.CompressorInputStream
import java.nio.file.Files
import java.io.FileOutputStream
import com.typesafe.scalalogging.Logger
import io.spicelabs.rodeocomponents.APIS.containers.StreamProvider
import scala.jdk.FunctionConverters._
import java.io.ByteArrayInputStream

// these are Scala equivalents to the Java APIs.
// the reason for using these rather that the Java interfaces directly
// is that we can take better advantage of using the scala collection
// functions and more easily treat everything uniformly
trait SContainerHandler {
  def getItems(): Vector[ContainerItem]
  def onItemProcessed(item: ContainerItem): Unit
}

trait SContainerFactory {
  def name: String
  def canHandle(mimeType: String): HandlerResult
  def buildHandler(
      mimeType: String,
      stm: StreamProvider,
      tempDir: Path
  ): SContainerHandler
  def buildHandler(
      mimeType: String,
      stm: FileInputStream,
      tempDir: Path
  ): SContainerHandler
  def buildHandler(
      mimeType: String,
      file: File,
      tempDir: Path
  ): SContainerHandler
  def onContainerProcessed(handler: SContainerHandler): Unit
}

class SCStreamProvider(artifact: ArtifactWrapper) extends StreamProvider {
  override def operateOnStream[T](
      operator: java.util.function.Function[InputStream, T]
  ): T = {
    val scoperator = operator.asScala
    artifact.withStream(scoperator)
  }
}

// class SCStreamProvider(artifact: ArtifactWrapper) extends StreamProvider {
//     override def operateOnStream[T](operator: (java.util.function.Function[InputStream, T])?): (T)? = {
//         artifact.withInputStream(operator)
//     }
//     // override def operateOnStream[T](operator: java.util.function.Function[InputStream, T]): T = {
//     //     artifact.withInputStream(operator)
//     // }
// }

// There are the main methods for working with containers.
// The general approach is to use a strategy pattern where there are
// a list of objects that can work with containers with the provision that
// the last one is "special" and acts as a fallback
object Containers {
  val logger: Logger = Logger(getClass())
  private lazy val standardContainerFactories: Vector[SContainerFactory] =
    Vector[SContainerFactory](
      ZipContainerFactory(),
      Iso9660ContainerFactory(),
      ArchiveContainerFactory()
    )
  private val containerFactories: AtomicReference[Vector[SContainerFactory]] =
    AtomicReference(standardContainerFactories)

  // add a container factory before the ArchiveContainerFactory
  def addContainerFactory(factory: SContainerFactory): Unit = {
    containerFactories.getAndUpdate(fv => {
      if (!fv.exists(sc => sc.name == factory.name)) {
        fv.patch(fv.length - 1, Seq(factory), 0)
      } else {
        fv
      }
    })
  }

  // remove a container factory as long as it isn't the ArchiveContainerFactory
  def removeContainerFactory(name: String): Unit = {
    containerFactories.getAndUpdate(fv => {
      if (name != ArchiveHelpers.archiveContainerName)
        fv.filterNot(sc => sc.name == name)
      else
        fv
    })
  }

  // get the names of all the containers
  def factoryNames(): Vector[String] = {
    containerFactories.get().map(_.name)
  }

  // set the containerFactories back to standard
  def reset(): Unit = {
    containerFactories.getAndSet(standardContainerFactories)
  }

  private def factoryThatCanHandle(
      mimeType: String
  ): Option[(SContainerFactory, HandlerResult)] = {
    firstHandlerOfMime(containerFactories.get(), mimeType)
  }

  private def firstHandlerOfMime(
      fv: Vector[SContainerFactory],
      mime: String
  ): Option[(SContainerFactory, HandlerResult)] = {
    fv.length match {
      case 0 => None
      case _ => {
        val factory = fv.head
        val result = factory.canHandle(mime)
        result match {
          case HandlerResult.NO => firstHandlerOfMime(fv.drop(1), mime)
          case _                => Some(factory -> result)
        }
      }
    }
  }

  // this is the main interface to the ContainerFactories from within goat rodeo.
  // it finds the first container factory that advertises that it can handle a
  // container mime type.
  def asContainer(
      artifact: ArtifactWrapper,
      tempDir: Path
  ): FileWalker.OptionalArchiveStream = {
    factoryThatCanHandle(artifact.mimeType) match {
      case Some(factory, result) =>
        getFactoryElements(artifact, factory, result, tempDir)
      case None => None
    }
  }

  // this gets the elements from the given factory and turns them into an OptionalArchiveStream
  private def getFactoryElements(
      artifact: ArtifactWrapper,
      factory: SContainerFactory,
      howToHandle: HandlerResult,
      tempDir: Path
  ): FileWalker.OptionalArchiveStream = {
    val handlerOpt = howToHandle match {
      case HandlerResult.WITH_INPUT_STREAM =>
        Some(
          artifact.withStream(is =>
            factory.buildHandler(
              artifact.mimeType,
              SCStreamProvider(artifact),
              tempDir
            )
          )
        )
      case HandlerResult.WITH_FILE_INPUT_STREAM => {
        val f = artifact.forceFile(tempDir)
        Try {
          val fis = FileInputStream(f)
          factory.buildHandler(artifact.mimeType, fis, tempDir)
        } match {
          case Failure(exception) => None
          case Success(value)     => Some(value)
        }
      }
      case HandlerResult.WITH_FILE => {
        val file = artifact.forceFile(tempDir)
        Some(factory.buildHandler(artifact.mimeType, file, tempDir))
      }
      case HandlerResult.NO => None
    }

    handlerOpt match {
      case Some(handler) => {
        val items = handler
          .getItems()
          .map(item => {
            val containerArtifact = ArtifactWrapper.newWrapper(
              item.itemPath(),
              item.itemSize(),
              item.stm(),
              artifact.tempDir,
              tempDir
            )
            handler.onItemProcessed(item)
            containerArtifact
          })
        val result = Some(items -> factory.name)
        factory.onContainerProcessed(handler)
        result
      }
      case None => None
    }
  }

  lazy val emptyHandler: SContainerHandler = ComponentContainerHandlerAdapter(
    EmptyContainerHandler.empty
  )
}

// This is an adapter for the handler from a component
class ComponentContainerHandlerAdapter(val handler: ContainerHandler)
    extends SContainerHandler {
  override def getItems(): Vector[ContainerItem] =
    handler.getItems().toScala(Vector)
  override def onItemProcessed(item: ContainerItem): Unit =
    handler.onItemProcessed(item)
}

// This is an adapter for the container factory from a component
// all the methods get delegated to the component's implementation
class ComponentContainerFactoryAdapter(compFactory: ContainerFactory)
    extends SContainerFactory {
  private var fileStreamOpt: Option[FileInputStream] = None
  override def name: String = compFactory.getName()
  override def canHandle(mimeType: String): HandlerResult =
    compFactory.canHandle(mimeType)
  override def buildHandler(
      mimeType: String,
      stm: StreamProvider,
      tempDir: Path
  ): SContainerHandler =
    ComponentContainerHandlerAdapter(
      compFactory.buildHandler(mimeType, stm, tempDir)
    )
  override def buildHandler(
      mimeType: String,
      stm: FileInputStream,
      tempDir: Path
  ): SContainerHandler = {
    fileStreamOpt = Some(stm)
    ComponentContainerHandlerAdapter(
      compFactory.buildHandler(mimeType, stm, tempDir)
    )
  }
  override def buildHandler(
      mimeType: String,
      file: File,
      tempDir: Path
  ): SContainerHandler =
    ComponentContainerHandlerAdapter(
      compFactory.buildHandler(mimeType, file, tempDir)
    )
  override def onContainerProcessed(handler: SContainerHandler): Unit = {
    handler match {
      case handler: ComponentContainerHandlerAdapter => {
        compFactory.onContainerProcessed(handler.handler)
        fileStreamOpt.foreach(fs => fs.close())
      }
      case _ => {
        val message =
          s"Incorrect type in ComponentContainerFactoryAdapter - asked to report processed to a handler of type ${handler.getClass().getName()}"
        Containers.logger.error(message)
        throw OperationNotSupportedException(message)
      }

    }
  }
}

// get zip files
class ZipContainerHandler(val zip: ZipFile) extends SContainerHandler {
  override def getItems(): Vector[ContainerItem] = {
    zip
      .stream()
      .iterator()
      .asScala
      .filter(v => { !v.isDirectory() })
      .map(v => {
        val name = v.getName()
        val size = v.getSize()
        new ContainerItem(zip.getInputStream(v), false, name, size)
      })
      .toVector
  }

  override def onItemProcessed(item: ContainerItem): Unit = {}
}

// zip container factory implementation
class ZipContainerFactory extends SContainerFactory {
  private lazy val zipMimeTypes: Set[String] =
    Set(
      "application/zip",
      "application/java-archive",
      "application/vnd.android.package-archive"
    )

  override def name: String = "Zip Container"

  override def canHandle(mimeType: String): HandlerResult = {
    if zipMimeTypes.contains(mimeType) then HandlerResult.WITH_FILE
    else HandlerResult.NO
  }

  override def buildHandler(
      mimeType: String,
      stm: FileInputStream,
      tempDir: Path
  ): SContainerHandler = Containers.emptyHandler
  override def buildHandler(
      mimeType: String,
      stm: StreamProvider,
      tempDir: Path
  ): SContainerHandler = Containers.emptyHandler
  override def buildHandler(
      mimeType: String,
      file: File,
      tempDir: Path
  ): SContainerHandler = {
    Try {
      ZipContainerHandler(ZipFile(file))
    } match {
      case Failure(exception) => {
        Containers.logger.error(
          s"Error building ZipFile stream: ${exception.getMessage()}"
        )
        Containers.emptyHandler
      }
      case Success(value) => value
    }
  }

  override def onContainerProcessed(handler: SContainerHandler): Unit = {
    handler match {
      case handler: ZipContainerHandler => handler.zip.close()
      case _                            => {}
    }
  }
}

// get iso9660 files
class Iso9660ContainerHandler(val isoReader: IsoFileReader)
    extends SContainerHandler {
  override def getItems(): Vector[ContainerItem] = {
    val files = isoReader.getAllFiles()

    val flatList = isoReader.convertTreeFilesToFlatList(files).asScala.toVector
    for (cycleFile <- flatList)
      yield {

        val nameWithThing = cycleFile.getFullFileName('/')
        val name =
          (if (nameWithThing.endsWith(";1")) {
             nameWithThing.substring(0, nameWithThing.length() - 2)

           } else nameWithThing).toLowerCase()

        val size = cycleFile.getSize()
        new ContainerItem(isoReader.getFileStream(cycleFile), false, name, size)
      }
  }

  override def onItemProcessed(item: ContainerItem): Unit = {}
}

// iso9660 container factory
class Iso9660ContainerFactory extends SContainerFactory {
  private lazy val isoMimeTypes: Set[String] = Set(
    "application/x-iso9660-image"
  )

  override def name: String = "ISO Reader"

  override def canHandle(mimeType: String): HandlerResult = {
    if isoMimeTypes.contains(mimeType) then HandlerResult.WITH_FILE
    else HandlerResult.NO
  }

  override def buildHandler(
      mimeType: String,
      stm: FileInputStream,
      tempDir: Path
  ): SContainerHandler = Containers.emptyHandler
  override def buildHandler(
      mimeType: String,
      stm: StreamProvider,
      tempDir: Path
  ): SContainerHandler = Containers.emptyHandler
  override def buildHandler(
      mimeType: String,
      file: File,
      tempDir: Path
  ): SContainerHandler = {
    Try {
      Iso9660ContainerHandler(IsoFileReader(file))
    } match {
      case Failure(exception) => {
        Containers.logger.error(
          s"Error building Iso9660 stream: ${exception.getMessage()}"
        )
        Containers.emptyHandler
      }
      case Success(value) => value
    }
  }

  override def onContainerProcessed(handler: SContainerHandler): Unit = {
    handler match {
      case handler: Iso9660ContainerHandler => handler.isoReader.close()
      case _                                => {}
    }
  }
}

object ArchiveHelpers {
  // get files from an archive stream
  // NOTE - the Archive code does some stuff where it hands back slices of the
  // original InputStream. The slices needs to be processed in order (AFAICT)
  // and (maybe) to EOF. The problem is that after getItems() is called, the original
  // stream gets closed and that invalidates all the slices.
  // To get around this, I read each in its entirety here. This may be problematic
  // for very large tar files.
  // If we need to address this in the future, these should get put into temp files,
  // which is a straight-forward piece of work considering that (1) we have a temp
  // directory in the ArchiveContainerFactory and (2) we have an onItemProcessed
  // which we can use to clean up the file.
  def getContainerItems(
      inputStream: BufferedInputStream
  ): Vector[ContainerItem] = {
    val factory = (new ArchiveStreamFactory())
    val input: ArchiveInputStream[ArchiveEntry] = factory
      .createArchiveInputStream(
        inputStream
      )
    val theIterator = Helpers
      .iteratorFor(input)
      .filter(!_.isDirectory())
      .map(ae => {
        val artifactName = ae.getName()
        val size = ae.getSize()
        val stm = ByteArrayInputStream(Helpers.slurpInputNoClose(input))
        new ContainerItem(stm, false, artifactName, size)
      })
      .toVector
    input.close()
    theIterator
  }

  // make a temp file and decompress into it (maybe)
  def maybeFile(stream: InputStream, tempDir: Path): Option[File] = {
    Try {
      val factory = new CompressorStreamFactory()
      val fis = new BufferedInputStream(stream)
      val input: CompressorInputStream =
        factory.createCompressorInputStream(fis)
      val theFile =
        Files
          .createTempFile(tempDir, "goats", "uncompressed")
          .toFile()
      val fos = new FileOutputStream(theFile)
      Helpers.copy(input, fos)
      fos.close()
      theFile
    }.toOption
  }
  def archiveContainerName = "Apache Common Wrapper"
}

// handle a non-compressed container
class ArchiveContainerHandler(provider: StreamProvider)
    extends SContainerHandler {
  override def getItems(): Vector[ContainerItem] = {
    Try {
      val operator = (stream: InputStream) =>
        ArchiveHelpers.getContainerItems(BufferedInputStream(stream))
      provider.operateOnStream(operator.asJava)
    } match {
      case Failure(exception) => {
        Containers.logger.error(
          s"Error getting uncompressed archive items: ${exception.getMessage()}"
        )
        Vector[ContainerItem]()
      }
      case Success(value) => value
    }
  }
  override def onItemProcessed(item: ContainerItem): Unit = {}
}

// handle a compressed container
class ArchiveContainerHandlerCompressed(provider: StreamProvider, tempDir: Path)
    extends SContainerHandler {
  val operator = (stream: InputStream) =>
    ArchiveHelpers.maybeFile(stream, tempDir)
  val maybeFile: Option[File] = provider.operateOnStream(operator.asJava)

  override def getItems(): Vector[ContainerItem] = {
    Try {
      maybeFile match {
        case Some(uncompressedFile) => {
          ArchiveHelpers.getContainerItems(
            BufferedInputStream(FileInputStream(uncompressedFile))
          )
        }
        case None => {
          val operator = (stream: InputStream) =>
            ArchiveHelpers.getContainerItems(BufferedInputStream(stream))
          provider.operateOnStream(operator.asJava)
        }
      }
    } match {
      case Failure(exception) => {
        // uncomment this code if you want a spew of error message for things that aren't archives
//                Containers.logger.error(s"Error getting compressed archive items: ${exception.getMessage()}")
        Vector[ContainerItem]()
      }
      case Success(value) => value
    }
  }
  override def onItemProcessed(item: ContainerItem): Unit = {}
  def cleanUp() = {
    Try {
      maybeFile match {
        case Some(value) => value.delete()
        case None        => ()
      }
    } match {
      case Failure(exception) => {
        Containers.logger.error(
          s"Error cleaning up compressed archive: ${exception.getMessage()}"
        )
      }
      case Success(value) => ()
    }
  }
}

// factory for archive containers
class ArchiveContainerFactory extends SContainerFactory {
  override def name: String = ArchiveHelpers.archiveContainerName
  override def canHandle(mimeType: String): HandlerResult =
    HandlerResult.WITH_INPUT_STREAM

  override def buildHandler(
      mimeType: String,
      stm: StreamProvider,
      tempDir: Path
  ): SContainerHandler = {
    val compressed =
      !FileWalker.notCompressed(
        "",
        mimeType
      ) // as of late, the first argument of notCompressed is ignored
    compressed match {
      case false => ArchiveContainerHandler(stm)
      case true  => ArchiveContainerHandlerCompressed(stm, tempDir)
    }
  }
  override def buildHandler(
      mimeType: String,
      stm: FileInputStream,
      tempDir: Path
  ): SContainerHandler = Containers.emptyHandler
  override def buildHandler(
      mimeType: String,
      file: File,
      tempDir: Path
  ): SContainerHandler = Containers.emptyHandler

  override def onContainerProcessed(handler: SContainerHandler): Unit = {
    handler match {
      case handler: ArchiveContainerHandlerCompressed => handler.cleanUp()
      case _                                          => {}
    }
  }
}
