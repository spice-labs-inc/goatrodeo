package goatrodeo.util.filetypes

import org.apache.tika.metadata.Metadata
import org.apache.tika.mime.MediaType
import org.apache.tika.parser.{ParseContext, Parser}
import org.xml.sax.ContentHandler
import scala.jdk.CollectionConverters._
import java.io.InputStream
import java.util

/**
 * A Tika Parser interface for Debian packages
 */
class DebianPackageParser extends Parser  {

  override def getSupportedTypes(context: ParseContext): util.Set[MediaType] =
    Set(MediaType.application("x-debian-package")).asJava


  /**
   * Parse the provided input stream as a .deb file, and extract its metadata
   *
   * Note that this uses a visitor pattern in the Java code with the way the Handler is managed
   * @param stream This should be the very head of the stream - the entire .deb package, not just the 'control' file
   * @param handler
   * @param metadata
   * @param context
   */
  override def parse(stream: InputStream, handler: ContentHandler, metadata: Metadata, context: ParseContext): Unit =
    ???
}

object DebianPackageParser {

}
