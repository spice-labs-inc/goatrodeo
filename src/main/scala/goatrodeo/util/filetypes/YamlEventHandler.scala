package goatrodeo.util.filetypes

import com.typesafe.scalalogging.Logger
import org.yaml.snakeyaml.{LoaderOptions, Yaml}

import java.io.StringReader
import scala.jdk.CollectionConverters.*
/**
 * An event handler for processing Snake YAML Parser Events into a Map[String, String] for metadata handling
 */
class YamlEventHandler {
  val logger = Logger("YamlEventHandler")

}

object YamlEventHandler {
  def apply(rawYaml: String) = {

    val yamlOpts = new LoaderOptions()
    val yaml = new Yaml(yamlOpts)

    yaml.parse(new StringReader(rawYaml)).asScala
  }

}