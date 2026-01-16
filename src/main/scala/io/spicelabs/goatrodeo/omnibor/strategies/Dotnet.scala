package io.spicelabs.goatrodeo.omnibor.strategies

import com.github.packageurl.PackageURL
import com.typesafe.scalalogging.Logger
import io.spicelabs.cilantro.AssemblyDefinition
import io.spicelabs.cilantro.AssemblyNameReference
import io.spicelabs.cilantro.CustomAttribute
import io.spicelabs.goatrodeo.omnibor.Item
import io.spicelabs.goatrodeo.omnibor.MetadataKeyConstants
import io.spicelabs.goatrodeo.omnibor.ParentScope
import io.spicelabs.goatrodeo.omnibor.ProcessingState
import io.spicelabs.goatrodeo.omnibor.SingleMarker
import io.spicelabs.goatrodeo.omnibor.Storage
import io.spicelabs.goatrodeo.omnibor.StringOrPair
import io.spicelabs.goatrodeo.omnibor.ToProcess
import io.spicelabs.goatrodeo.omnibor.ToProcess.ByName
import io.spicelabs.goatrodeo.omnibor.ToProcess.ByUUID
import io.spicelabs.goatrodeo.util.ArtifactWrapper
import io.spicelabs.goatrodeo.util.DotnetDetector
import io.spicelabs.goatrodeo.util.FileWrapper
import io.spicelabs.goatrodeo.util.GitOID
import io.spicelabs.goatrodeo.util.Helpers
import io.spicelabs.goatrodeo.util.Helpers.toHex
import io.spicelabs.goatrodeo.util.TreeMapExtensions.+?
import org.json4s.*
import org.json4s.JsonDSL.*
import org.json4s.native.JsonMethods.*

import java.io.FileInputStream
import scala.collection.immutable.TreeMap
import scala.collection.immutable.TreeSet
import scala.collection.mutable.ArrayBuffer
import scala.util.Using

class DotnetState(
    assemblyOpt: Option[AssemblyDefinition] = None
) extends ProcessingState[SingleMarker, DotnetState] {
  private val log = Logger(classOf[DotnetState])
  def beginProcessing(
      artifact: ArtifactWrapper,
      item: Item,
      marker: SingleMarker
  ): DotnetState = {

    artifact match {
      case fa: FileWrapper =>
        Using.resource(FileInputStream(fa.wrappedFile)) { fis =>
          DotnetState(Some(AssemblyDefinition.readAssembly(fis)))
        }
      case _ => throw new Exception("Expecting file wrapper for Dotnetstate")
    }
  }

  override def getPurls(
      artifact: ArtifactWrapper,
      item: Item,
      marker: SingleMarker
  ): (Vector[PackageURL], DotnetState) = {

    assemblyOpt
      .map(assembly =>
        PackageURL(
          "nuget",
          "",
          assembly.name.name,
          assembly.name.version.toString(),
          null,
          ""
        )
      )
      .toVector -> this
  }

  override def getMetadata(
      artifact: ArtifactWrapper,
      item: Item,
      marker: SingleMarker
  ): (TreeMap[String, TreeSet[StringOrPair]], DotnetState) = {
    // some of these metadata elements may not exist in the assembly
    // under consideration. Therefore, we use the custom +? operator
    // which will add the element if it's Some(foo) and ignore it if
    // it's None.
    val tm = TreeMap[String, TreeSet[StringOrPair]]()
      +? assemblyFullName
      +? assemblyName
      +? assemblyVersion
      +? assemblyLocale
      +? assemblyPublicKey
      +? assemblyCopyright
      +? assemblyTrademark
      +? assemblyProducer
      +? assemblyDescription
      +? assemblyDependencies
    (tm, this)
  }

  def maybeSOP(
      k: String,
      v: Option[String]
  ): Option[(String, TreeSet[StringOrPair])] =
    v.map(str => k -> TreeSet[StringOrPair](str))

  // each of these assembly attributes should
  // 1. exist
  // 2. have a zeroth constructor argument
  // 3. that value should be type String
  // in the event that we get none of those, we return None
  def customAttributeArgumentZero(attrName: String): Option[String] = {
    for {
      assembly <- assemblyOpt if assembly.hasCustomAttributes
      customAttrs <- Option(assembly.customAttributes)
      attr <- customAttrs
        .find(at => at.attributeType.fullName == attrName)
        .headOption
      res <- argZeroValueAsString(attr)
    } yield res
  }

  // custom attributes in .NET may have constructor arguments that are
  // embedded in the assembly. In that case, when the constructor argument is read,
  // there is a certain amount of effort expended to turn it into a rarefied instance
  // this includes the standard value types (ints, bool) as well as strings.
  def argZeroValueAsString(ca: CustomAttribute): Option[String] = {
    def asString(in: Any): Option[String] = {
in match {
  case s: String if !s.isEmpty() => Some(s)
  case _ => None
}
    }
    for {
      first <- ca.constructorArguments.headOption
      case str: String <-asString(first.value)
    } yield str
  }

  def assemblyFullName: Option[(String, TreeSet[StringOrPair])] = {
    assemblyOpt.flatMap(assembly =>
      maybeSOP(MetadataKeyConstants.NAME, Option(assembly.fullName))
    )

  }

  def assemblyName: Option[(String, TreeSet[StringOrPair])] = {
    assemblyOpt.flatMap(assembly =>
      maybeSOP(
        MetadataKeyConstants.SIMPLE_NAME,
        Option(assembly.name.name)
      )
    )
  }

  def assemblyVersion: Option[(String, TreeSet[StringOrPair])] = {
    assemblyOpt.flatMap { assembly =>
      maybeSOP(
        MetadataKeyConstants.VERSION,
        Option(assembly.name.version.toString())
      )
    }
  }

  def assemblyLocale: Option[(String, TreeSet[StringOrPair])] = {
    assemblyOpt.flatMap { assembly =>
      maybeSOP(
        MetadataKeyConstants.LOCALE,
        Option(assembly.name.culture)
      )
    }
  }

  def assemblyPublicKey: Option[(String, TreeSet[StringOrPair])] = {
    assemblyOpt.flatMap { assembly =>
      val pkStr =
        Option(assembly.name.publicKey).map(pk =>
          toHex(assembly.name.publicKey)
        )
      maybeSOP(MetadataKeyConstants.PUBLIC_KEY, pkStr)
    }
  }

  def assemblyCopyright: Option[(String, TreeSet[StringOrPair])] = {
    var cp = customAttributeArgumentZero("System.Reflection.CopyrightAttribute")
    maybeSOP(MetadataKeyConstants.COPYRIGHT, cp)
  }

  def assemblyTrademark: Option[(String, TreeSet[StringOrPair])] = {
    var tm = customAttributeArgumentZero("System.Reflection.TrademarkAttribute")
    maybeSOP(MetadataKeyConstants.TRADEMARK, tm)
  }

  def assemblyProducer: Option[(String, TreeSet[StringOrPair])] = {
    var pr = customAttributeArgumentZero(
      "System.Reflection.AssemblyTrademarkAttribute"
    )
    maybeSOP(MetadataKeyConstants.PUBLISHER, pr)
  }

  def assemblyDescription: Option[(String, TreeSet[StringOrPair])] = {
    var desc = customAttributeArgumentZero(
      "System.Reflection.AssemblyDescriptionAttribute"
    )
    maybeSOP(MetadataKeyConstants.DESCRIPTION, desc)
  }

  def assemblyDependencies: Option[(String, TreeSet[StringOrPair])] = {
    import DotnetState.formatDeps
    val assembly = assemblyOpt.get
    val refs = assembly.mainModule.assemblyReferences;
    if (refs.length == 0) { None }
    else {
      val deps = formatDeps(refs)
      maybeSOP(MetadataKeyConstants.DEPENDENCIES, deps)
    }
  }

  override def finalAugmentation(
      artifact: ArtifactWrapper,
      item: Item,
      marker: SingleMarker,
      parentScope: ParentScope,
      store: Storage
  ): (Item, DotnetState) = item -> this

  override def postChildProcessing(
      kids: Option[Vector[GitOID]],
      store: Storage,
      marker: SingleMarker
  ): DotnetState = {
    this
  }
}

object DotnetState {
  def formatDeps(deps: ArrayBuffer[AssemblyNameReference]) = {
    val sortedDeps = deps.sortBy(((elem) => elem.name))

    val json =
      ("dependencies" ->
        sortedDeps.map { dep =>
          var nameVersionToken = ("name" -> dep.name) ~
            ("version" -> dep.version.toString()) ~
            ("public_key_token" -> Helpers.toHex(dep.publicKeyToken))
          if (dep.hasPublicKey) {
            nameVersionToken =
              nameVersionToken ~ ("public_key" -> Helpers.toHex(dep.publicKey))
          }
          nameVersionToken
        })
    Some(compact(render(json)))
  }
}

final case class DotnetFile(file: ArtifactWrapper) extends ToProcess {

  /** Call at the end of successful completing the operation
    */
  def markSuccessfulCompletion(): Unit = {
    file.finished()
  }
  type MarkerType = SingleMarker
  type StateType = DotnetState
  override def main: String = file.path()

  override def itemCnt: Int = 1

  /** The mime type of the main artifact
    */
  def mimeType: String = DotnetDetector.DOTNET_MIME.toString()

  override def getElementsToProcess()
      : (Seq[(ArtifactWrapper, MarkerType)], StateType) =
    Vector(file -> SingleMarker()) -> DotnetState()
}

object DotnetFile {
  def computeDotnetFiles(
      byUUID: ToProcess.ByUUID,
      byName: ToProcess.ByName
  ): (Vector[ToProcess], ByUUID, ByName, String) = {
    var ret: Vector[ToProcess] = Vector()
    var retByUUID = byUUID
    val dotnetMime = DotnetDetector.DOTNET_MIME.toString()

    val retByName = byName.map { case (k, v) =>
      val isDotnet = v.filter(_.mimeType == dotnetMime)

      // no isDotnet files, just continue
      if (isDotnet.isEmpty) {
        k -> v
      } else {
        // all the non-dotnet files
        val newV = v.filter(_.mimeType != dotnetMime)

        // for each of the dotnet files, add to ret, substract from uuid
        for { dotnet <- isDotnet } {
          retByUUID -= dotnet.uuid
          ret = ret :+ DotnetFile(dotnet)
        }

        k -> newV
      }
    }

    (ret, retByUUID, retByName, "Dotnet")
  }
}
