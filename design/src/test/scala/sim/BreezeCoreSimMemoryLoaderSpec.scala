package flow.sim

import org.scalatest.freespec.AnyFreeSpec
import org.scalatest.matchers.must.Matchers

class BreezeCoreSimMemoryLoaderSpec extends AnyFreeSpec with Matchers {
    "BreezeCoreSimMemoryLoader should load top-level memoryMap from json" in {
        val json =
            """{
              |  "meta": {
              |    "owner": "test",
              |    "comment": "ignore me"
              |  },
              |  "memoryMap": {
              |    "0x0": "0x0000000000100093",
              |    "0x8": "0x0000000000200113",
              |    "16": "3146131"
              |  }
              |}""".stripMargin

        val tempDir = os.temp.dir(prefix = "breeze-core-sim-loader")
        val jsonPath = tempDir / "memory.json"
        os.write(jsonPath, json)

        val memory = BreezeCoreSimMemoryLoader.loadMemoryMap(jsonPath.toString)

        memory(BigInt(0)) mustBe BigInt("0000000000100093", 16)
        memory(BigInt(8)) mustBe BigInt("0000000000200113", 16)
        memory(BigInt(16)) mustBe BigInt(3146131)
    }

    "BreezeCoreSimMemoryLoader should load simulation bootaddr from json" in {
        val json =
            """{
              |  "simulation": {
              |    "bootaddr": "0x80"
              |  },
              |  "memoryMap": {
              |    "0x80": "0x000000137ff00073"
              |  }
              |}""".stripMargin

        val tempDir = os.temp.dir(prefix = "breeze-core-sim-loader")
        val jsonPath = tempDir / "memory.json"
        os.write(jsonPath, json)

        BreezeCoreSimMemoryLoader.loadBootAddr(jsonPath.toString) mustBe BigInt(0x80)
    }
}
