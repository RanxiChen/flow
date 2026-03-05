/** Cache-related modules and parameters live in this file.
  *
  * This file currently targets a simple, blocking I-Cache (instruction cache).
  * It is intentionally minimal to keep bring-up and verification easy, while
  * exposing structural parameters for future scaling.
  */
package cache

import chisel3._
import chisel3.util._

/** I-Cache design contract (blocking, read-only, random replacement).
  *
  * ==Scope==
  *   - Instruction cache only (no D-Cache logic here).
  *   - Physical address only (no virtual address / VIPT yet).
  *   - Read-only cache: no write, no writeback, no allocate.
  *   - Blocking miss handling: single outstanding miss.
  *
  * ==Default Parameters==
  *   - `addrWidth`: 64
  *   - `capacityBytes`: 8 * 1024
  *   - `nWays`: 2
  *   - `lineBytes`: 32
  *   - `busDataWidth`: 64
  *
  * ==Configurable Parameters==
  *   - `addrWidth`: physical address width in bits.
  *   - `capacityBytes`: total cache capacity in bytes.
  *   - `nWays`: associativity.
  *   - `lineBytes`: cache line size in bytes (power of two).
  *   - `busDataWidth`: memory bus read data width in bits.
  *
  * ==Fixed Policy==
  *   - Replacement strategy: random.
  *
  * ==Interfaces / Integration Requirements==
  *   - IFetch interface: simple request/response, 4B instruction fetch.
  *   - Memory bus: custom read-only bus (request/response defined by you).
  *   - Fill behavior: whole line fetched; beats = `lineBytes * 8 / busDataWidth`.
  *   - `flush/invalidate` hook: reserved for `fence.i` or debug usage.
  *
  * ==Implementation Notes==
  *   - Tag + data arrays, valid bits.
  *   - `require` checks for parameter consistency:
  *     - `lineBytes` is power of two
  *     - `capacityBytes` aligns with `nWays` and `lineBytes`
  *     - `busDataWidth` is a multiple of 8
  */

import _root_.interface.{ICacheIO, NativeMemIO}
import _root_.circt.stage.ChiselStage

class ICache extends Module {
  val io = IO(new Bundle{
    val commer = new NativeMemIO
    //val dst = new ICacheIO
  })
  /*
  object cache_status extends ChiselEnum {
    val IDLE, RUN, REFILL = Value
  }
  import cache_status._
  val status = RegInit(IDLE)
  val ram_index_width = 8
  val ram_entry_num = 1 << ram_index_width
  val block_offset_width = 5
  val cache_block_width = 1 << block_offset_width
  // data ram
  val data_ram0 = SyncReadMem(ram_entry_num,UInt(cache_block_width.W))
  val data_ram1 = SyncReadMem(ram_entry_num,UInt(cache_block_width.W))
  // tag ram
  val inst_address_width = 64
  val tag_index_width = inst_address_width -  ram_index_width - block_offset_width
  val tag_ram0 = SyncReadMem(ram_entry_num,UInt(tag_index_width.W))
  val tag_ram1 = SyncReadMem(ram_entry_num,UInt(tag_index_width.W))
  // valid bit
  val valid_bit0 = RegInit(VecInit.fill(ram_entry_num)(false.B))
  val valid_bit1 = RegInit(VecInit.fill(ram_entry_num)(false.B))
*/
  io.commer.initialize()
  val cnt = RegInit(0.U(3.W))
  val state = RegInit(false.B)
  when(state === false.B){
    //speak cnt to mem
    when(io.commer.speak((cnt))){
      state := true.B
      cnt := cnt + 1.U
    }.otherwise{
      state := false.B
    }
  }.elsewhen(state === true.B){
      //listen data from mem
      val (done, data) = io.commer.listen()
      when(done){
        printf(p"data: ${data}\n")
        state := false.B
      }
  }.otherwise{
      state := false.B
  }
}

object FireICache extends App {
  println("Generating the ICache hardware")
  ChiselStage.emitSystemVerilogFile(
    new ICache,
    Array(
      "--target-dir","../sim/ICache/rtl"
    ),
    firtoolOpts = Array(
      "-disable-all-randomization",
      "-strip-debug-info",
      "-default-layer-specialization=enable"
    )
  )
}
