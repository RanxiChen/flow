package flow.platform

import com.fasterxml.jackson.databind.{JsonNode, ObjectMapper}

import java.io.File
import scala.jdk.CollectionConverters._

/** A single active physical-memory region from the frozen platform map.
  *
  * The access flags describe operations supported by the physical region. They
  * are PMA capabilities, not per-privilege permissions such as PMP entries.
  */
final case class PMARegionConst(
    name: String,
    origin: BigInt,
    size: BigInt,
    supportsRead: Boolean,
    supportsWrite: Boolean,
    supportsExecute: Boolean,
    cacheable: Boolean,
    device: Boolean
) {
  require(size > 0, s"PMA region $name must have a non-zero size")
  require((size & (size - 1)) == 0, s"PMA region $name size must be a power of two")
  require((origin & (size - 1)) == 0, s"PMA region $name must be naturally aligned")
  require(!(cacheable && device), s"PMA region $name cannot be both cacheable and device memory")

  val endExclusive: BigInt = origin + size
}

final case class BreezeMcuPlatformConfig(
    schemaVersion: Int,
    platform: String,
    addressWidth: Int,
    resetVector: BigInt,
    pmaRegions: Seq[PMARegionConst]
)

/** Elaboration-time view of config/breeze_mcu_platform.json.
  *
  * The JSON file remains the shared source of truth for the core and the future
  * LiteX integration. This object converts its fixed platform conventions into
  * Scala constants used to elaborate hardware.
  */
object BreezeMcuPlatform {
  private val ConfigFileName = "breeze_mcu_platform.json"
  private val mapper = new ObjectMapper()

  private def locateConfig(): File = {
    val candidates = Seq(
      new File("config", ConfigFileName),
      new File("../config", ConfigFileName)
    )

    candidates.find(_.isFile).getOrElse {
      val attempted = candidates.map(_.getAbsolutePath).mkString(", ")
      throw new IllegalArgumentException(
        s"Cannot locate $ConfigFileName; tried: $attempted"
      )
    }
  }

  private def required(parent: JsonNode, field: String): JsonNode =
    Option(parent.get(field)).getOrElse {
      throw new IllegalArgumentException(s"Missing required platform field: $field")
    }

  private def requiredText(parent: JsonNode, field: String): String = {
    val node = required(parent, field)
    require(node.isTextual, s"Platform field $field must be a string")
    node.textValue()
  }

  private def requiredBoolean(parent: JsonNode, field: String): Boolean = {
    val node = required(parent, field)
    require(node.isBoolean, s"Platform field $field must be a boolean")
    node.booleanValue()
  }

  private def requiredInt(parent: JsonNode, field: String): Int = {
    val node = required(parent, field)
    require(node.isIntegralNumber, s"Platform field $field must be an integer")
    node.intValue()
  }

  private def requiredNumber(parent: JsonNode, field: String): BigInt = {
    val node = required(parent, field)
    if (node.isIntegralNumber) {
      BigInt(node.bigIntegerValue())
    } else if (node.isTextual) {
      val text = node.textValue().trim
      if (text.startsWith("0x") || text.startsWith("0X")) BigInt(text.drop(2), 16)
      else BigInt(text, 10)
    } else {
      throw new IllegalArgumentException(
        s"Platform field $field must be an integer or an integer string"
      )
    }
  }

  private def load(): BreezeMcuPlatformConfig = {
    val path = locateConfig()
    val root = mapper.readTree(path)
    val schemaVersion = requiredInt(root, "schemaVersion")
    val platform = requiredText(root, "platform")
    val addressWidth = requiredInt(root, "addressWidth")
    val resetVector = requiredNumber(root, "resetVector")
    val regionNodes = required(root, "regions")

    require(schemaVersion == 1, s"Unsupported platform schema version: $schemaVersion")
    require(addressWidth >= 1 && addressWidth <= 64, s"Invalid physical address width: $addressWidth")
    require(regionNodes.isArray, "Platform field regions must be an array")

    val regions = regionNodes.elements().asScala.map { node =>
      PMARegionConst(
        name = requiredText(node, "name"),
        origin = requiredNumber(node, "origin"),
        size = requiredNumber(node, "size"),
        supportsRead = requiredBoolean(node, "readable"),
        supportsWrite = requiredBoolean(node, "writable"),
        supportsExecute = requiredBoolean(node, "executable"),
        cacheable = requiredBoolean(node, "cacheable"),
        device = requiredBoolean(node, "device")
      )
    }.toSeq

    require(regions.nonEmpty, "The platform must define at least one active PMA region")
    require(regions.map(_.name).distinct.size == regions.size, "PMA region names must be unique")

    val physicalLimit = BigInt(1) << addressWidth
    require(resetVector >= 0 && resetVector < physicalLimit, "Reset vector is outside physical address space")
    regions.foreach { region =>
      require(region.origin >= 0, s"PMA region ${region.name} has a negative origin")
      require(
        region.endExclusive <= physicalLimit,
        s"PMA region ${region.name} exceeds the configured physical address width"
      )
    }

    regions.sortBy(_.origin).sliding(2).foreach {
      case Seq(left, right) =>
        require(
          left.endExclusive <= right.origin,
          s"PMA regions ${left.name} and ${right.name} overlap"
        )
      case _ =>
    }

    require(
      regions.exists(region =>
        region.supportsExecute &&
          resetVector >= region.origin &&
          resetVector < region.endExclusive
      ),
      "Reset vector must be inside an executable PMA region"
    )

    BreezeMcuPlatformConfig(
      schemaVersion = schemaVersion,
      platform = platform,
      addressWidth = addressWidth,
      resetVector = resetVector,
      pmaRegions = regions
    )
  }

  val Config: BreezeMcuPlatformConfig = load()
  val AddressWidth: Int = Config.addressWidth
  val ResetVector: BigInt = Config.resetVector
  val PMARegions: Seq[PMARegionConst] = Config.pmaRegions
}
