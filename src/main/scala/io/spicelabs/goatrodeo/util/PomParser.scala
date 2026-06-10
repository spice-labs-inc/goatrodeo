/* Copyright 2024-2026 Spice Labs, Inc. & Contributors. Apache 2.0. */
package io.spicelabs.goatrodeo.util

import com.typesafe.scalalogging.Logger
import org.w3c.dom.Document
import org.w3c.dom.Element

import javax.xml.parsers.DocumentBuilderFactory
import scala.util.Try

object PomParser {

  private val logger = Logger[PomParser.type]

  case class ParsedLicense(name: Option[String], url: Option[String])

  case class ParsedDependency(
      groupId: Option[String],
      artifactId: Option[String],
      version: Option[String],
      scope: Option[String],
      classifier: Option[String],
      optional: Boolean,
      `type`: Option[String]
  )

  case class ParsedPom(
      groupId: Option[String],
      artifactId: Option[String],
      version: Option[String],
      name: Option[String],
      description: Option[String],
      url: Option[String],
      organization: Option[String],
      scmUrl: Option[String],
      properties: Map[String, String],
      licenses: Vector[ParsedLicense],
      dependencies: Vector[ParsedDependency],
      dependencyManagement: Vector[ParsedDependency],
      parentGroupId: Option[String],
      parentArtifactId: Option[String],
      parentVersion: Option[String]
  )

  private val MaxDepth = 10
  private val PropRegex = """\$\{([^}]+)\}""".r

  private def secureDbf: javax.xml.parsers.DocumentBuilderFactory = {
    val f = javax.xml.parsers.DocumentBuilderFactory.newInstance()
    f.setNamespaceAware(false)
    f.setValidating(false)
    f.setXIncludeAware(false)
    f.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true)
    f.setFeature("http://xml.org/sax/features/external-general-entities", false)
    f.setFeature(
      "http://apache.org/xml/features/nonvalidating/load-external-dtd",
      false
    )
    f
  }

  private val DoctypeStart = "<!DOCTYPE"

  private def containsDoctype(xml: String): Boolean =
    xml.toUpperCase.contains(DoctypeStart)

  /** Strip DOCTYPE declaration from XML string. Finds "<!DOCTYPE"
    * (case-insensitive) and removes through the matching closing ">". Correctly
    * handles:
    *   - internal subsets ([...])
    *   - quoted strings (both single and double) inside the declaration so that
    *     a ">" inside a SYSTEM identifier does not truncate early.
    */
  private def stripDoctype(xml: String): String = {
    val start = xml.toUpperCase.indexOf(DoctypeStart)
    if (start < 0) return xml
    var pos = start + DoctypeStart.length
    var bracketDepth = 0
    var inQuote: Option[Char] = None
    while (pos < xml.length) {
      val ch = xml.charAt(pos)
      inQuote match {
        case Some(q) =>
          if (ch == q) inQuote = None
        case None =>
          if (ch == '[') bracketDepth += 1
          else if (ch == ']' && bracketDepth > 0) bracketDepth -= 1
          else if (ch == '"' || ch == '\'') inQuote = Some(ch)
          else if (ch == '>' && bracketDepth == 0) {
            val end = pos + 1
            return xml.substring(0, start) + xml.substring(end)
          }
      }
      pos += 1
    }
    xml
  }

  def parse(pomString: String): Option[ParsedPom] = {
    parse0(pomString).orElse {
      if (containsDoctype(pomString)) {
        val stripped = stripDoctype(pomString)
        parse0(stripped)
      } else None
    }
  }

  /** Silent SAX error handler that prevents the default parser from
    * printing `[Fatal Error]` / `[Error]` / `[Warning]` lines to stderr.
    */
  private object SilentSaxHandler extends org.xml.sax.ErrorHandler {
    def warning(e: org.xml.sax.SAXParseException): Unit = ()
    def error(e: org.xml.sax.SAXParseException): Unit = ()
    def fatalError(e: org.xml.sax.SAXParseException): Unit = ()
  }

  private def parse0(pomString: String): Option[ParsedPom] =
    Try {
      val db = secureDbf.newDocumentBuilder()
      db.setErrorHandler(SilentSaxHandler)
      val doc =
        try {
          db.parse(
            new java.io.ByteArrayInputStream(pomString.getBytes("UTF-8"))
          )
        } catch {
          case e: Exception => throw e
        }
      val props = parseProperties(doc)
      val base = baseProperties(doc) ++ props
      ParsedPom(
        groupId = interpolate(
          tagText(doc, "groupId")
            .orElse(parentTagText(doc, "groupId"))
            .getOrElse(""),
          base
        ).filter(_.nonEmpty),
        artifactId = tagText(doc, "artifactId"),
        version = interpolate(
          tagText(doc, "version")
            .orElse(parentTagText(doc, "version"))
            .getOrElse(""),
          base
        ).filter(_.nonEmpty),
        name = tagText(doc, "name"),
        description = tagText(doc, "description"),
        url = tagText(doc, "url"),
        organization = orgName(doc),
        scmUrl = scmUrl(doc),
        properties = props,
        licenses = parseLicenses(doc),
        dependencies = parseDependencies(doc, base ++ props),
        dependencyManagement = parseDependencyManagement(doc),
        parentGroupId = parentTagText(doc, "groupId"),
        parentArtifactId = parentTagText(doc, "artifactId"),
        parentVersion = parentTagText(doc, "version")
      )
    }.toOption

  def interpolate(value: String, props: Map[String, String]): Option[String] =
    interpolate0(value, props, 0, Set.empty)

  def resolveProperty(key: String, props: Map[String, String]): Option[String] =
    props.get(key).flatMap(interpolate(_, props))

  private def interpolate0(
      value: String,
      props: Map[String, String],
      depth: Int,
      seen: Set[String]
  ): Option[String] = {
    if (depth > MaxDepth) return None
    var result = value
    var failed = false
    for (m <- PropRegex.findAllMatchIn(value) if !failed) {
      val key = m.group(1).nn
      if (seen.contains(key)) {
        failed = true
      } else {
        val resolved = props.get(key) match {
          case Some(v) => interpolate0(v, props, depth + 1, seen + key)
          case None    => None
        }
        resolved match {
          case Some(r) => result = result.replace(m.matched, r)
          case None    => failed = true
        }
      }
    }
    if (failed) None else Some(result)
  }

  private def tagText(doc: Document, tagName: String): Option[String] = {
    val nodes = doc.getElementsByTagName(tagName)
    if (nodes.getLength > 0) {
      val text = nodes.item(0).getTextContent.trim
      if (text.nonEmpty) Some(text) else None
    } else None
  }

  private def parentTagText(doc: Document, tagName: String): Option[String] = {
    val parents = doc.getElementsByTagName("parent")
    if (parents.getLength > 0) {
      val parentElem = parents.item(0).asInstanceOf[Element]
      val children = parentElem.getElementsByTagName(tagName)
      if (children.getLength > 0) {
        val text = children.item(0).getTextContent.trim
        if (text.nonEmpty) Some(text) else None
      } else None
    } else None
  }

  private def orgName(doc: Document): Option[String] = {
    val orgs = doc.getElementsByTagName("organization")
    if (orgs.getLength > 0) {
      val names =
        orgs.item(0).asInstanceOf[Element].getElementsByTagName("name")
      if (names.getLength > 0) {
        val text = names.item(0).getTextContent.trim
        if (text.nonEmpty) Some(text) else None
      } else None
    } else None
  }

  private def scmUrl(doc: Document): Option[String] = {
    val scms = doc.getElementsByTagName("scm")
    if (scms.getLength > 0) {
      val urls = scms.item(0).asInstanceOf[Element].getElementsByTagName("url")
      if (urls.getLength > 0) {
        val text = urls.item(0).getTextContent.trim
        if (text.nonEmpty) Some(text) else None
      } else None
    } else None
  }

  private def parseProperties(doc: Document): Map[String, String] = {
    val propsNodes = doc.getElementsByTagName("properties")
    if (propsNodes.getLength > 0) {
      val propsElem = propsNodes.item(0).asInstanceOf[Element]
      val result = scala.collection.mutable.Map.empty[String, String]
      val children = propsElem.getChildNodes
      for (i <- 0 until children.getLength) {
        children.item(i) match {
          case e: Element =>
            val text = e.getTextContent.trim
            if (text.nonEmpty) result(e.getTagName) = text
          case _ =>
        }
      }
      result.toMap
    } else Map.empty
  }

  private def baseProperties(doc: Document): Map[String, String] = {
    val g = tagText(doc, "groupId").getOrElse("")
    val a = tagText(doc, "artifactId").getOrElse("")
    val v = tagText(doc, "version")
      .orElse(parentTagText(doc, "version"))
      .getOrElse("")
    Map(
      "project.groupId" -> g,
      "pom.groupId" -> g,
      "project.version" -> v,
      "pom.version" -> v,
      "project.artifactId" -> a,
      "pom.artifactId" -> a
    )
  }

  private def parseLicenses(doc: Document): Vector[ParsedLicense] = {
    val result = Vector.newBuilder[ParsedLicense]
    val licenseNodes = doc.getElementsByTagName("license")
    var i = 0
    while (i < licenseNodes.getLength) {
      val e = licenseNodes.item(i).asInstanceOf[Element]
      val name = Option(e.getElementsByTagName("name").item(0))
        .map(_.getTextContent.trim)
        .filter(_.nonEmpty)
      val url = Option(e.getElementsByTagName("url").item(0))
        .map(_.getTextContent.trim)
        .filter(_.nonEmpty)
      result += ParsedLicense(name, url)
      i += 1
    }
    result.result()
  }

  private def parseDependencies(
      doc: Document,
      props: Map[String, String]
  ): Vector[ParsedDependency] = {
    val result = Vector.newBuilder[ParsedDependency]
    val depNodes = doc.getElementsByTagName("dependency")
    var i = 0
    while (i < depNodes.getLength) {
      val e = depNodes.item(i).asInstanceOf[Element]
      // Skip <dependency> elements nested under <dependencyManagement>;
      // only parse direct <dependencies> blocks.
      var parent = e.getParentNode
      var inDepMgmt = false
      while (parent != null && !inDepMgmt) {
        if (parent.getNodeName == "dependencyManagement") inDepMgmt = true
        parent = parent.getParentNode
      }
      if (!inDepMgmt) {
        val g = interpolate(elementText(e, "groupId").getOrElse(""), props)
          .filter(_.nonEmpty)
        val a = interpolate(elementText(e, "artifactId").getOrElse(""), props)
          .filter(_.nonEmpty)
        val v = interpolate(elementText(e, "version").getOrElse(""), props)
          .filter(_.nonEmpty)
        result += ParsedDependency(
          groupId = g,
          artifactId = a,
          version = v,
          scope = elementText(e, "scope"),
          classifier = elementText(e, "classifier"),
          optional = elementText(e, "optional").contains("true"),
          `type` = elementText(e, "type")
        )
      }
      i += 1
    }
    result.result()
  }

  private def parseDependencyManagement(
      doc: Document
  ): Vector[ParsedDependency] = {
    val dmNodes = doc.getElementsByTagName("dependencyManagement")
    if (dmNodes.getLength == 0) return Vector.empty
    val dm = dmNodes.item(0).asInstanceOf[Element]
    val result = Vector.newBuilder[ParsedDependency]
    val deps = dm.getElementsByTagName("dependency")
    var i = 0
    while (i < deps.getLength) {
      val e = deps.item(i).asInstanceOf[Element]
      result += ParsedDependency(
        groupId = elementText(e, "groupId"),
        artifactId = elementText(e, "artifactId"),
        version = elementText(e, "version"),
        scope = elementText(e, "scope"),
        classifier = elementText(e, "classifier"),
        optional = elementText(e, "optional").contains("true"),
        `type` = elementText(e, "type")
      )
      i += 1
    }
    result.result()
  }

  private def elementText(e: Element, tag: String): Option[String] = {
    val nl = e.getElementsByTagName(tag)
    if (nl.getLength > 0) {
      val text = nl.item(0).getTextContent.trim
      if (text.nonEmpty) Some(text) else None
    } else None
  }

}
