package core

import  chisel3._
import  chisel3.util._
import chisel3.experimental.BundleLiterals._
import chisel3.simulator.scalatest.ChiselSim
import org.scalatest.freespec.AnyFreeSpec
import org.scalatest.matchers.must.Matchers

class CSRSpec extends AnyFreeSpec with Matchers with ChiselSim {
    "csr could read out original value" in {
        simulate(new RegFile(dumplog = true)){dut =>
            println(s"read csr at addr:0x%03x".format(CSRMAP.printer))
        }
    }
}