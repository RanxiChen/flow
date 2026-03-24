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

class ICache(val dumplog: Boolean = false) extends Module {
  val io = IO(new Bundle{
    val commer = new NativeMemIO
    val dst = new ICacheIO
  })
  /**
    * 整个电路实现流水线访问cache的过程，分为两个子阶段，TAG检查跟数据访问
    * 然后是状态机控制update过程
    * 当cache miss的时候，状态机从流水线访问的状态切换到update状态，
    * 这个状态该锁存数据访问阶段的资源，TAG的使能端一直保持不可读取状态
    * 在update状态，要先进行read，然后在refill阶段决定填充到那个way，在这之后一个空周期，确保写回
    * 基于这个空周期，不在需要ram实现同周期读写的时候进行冲突检测
    * 最后在一个周期进入流水线访问状态，继续访问下一个地址
    */
  // cache状态机，控制cache的访问和更新
  object cache_status extends ChiselEnum {
    val IDLE, RUN, UPDATE = Value
  }
  object update_status extends ChiselEnum {
    val REQ,RESP,REFILL,WAIT = Value 
  }
  import cache_status._
  import update_status._

  val cache_statusReg = RegInit(IDLE)
  val update_statusReg = RegInit(REQ)
  // ========== 地址划分相关常量 ==========
  // 修改原因：明确定义地址各部分的宽度，符合 CLAUDE.md 中的地址划分规范
  val ram_index_width = 8                           // Index 宽度：8 bits -> 256 sets
  val ram_entry_num = 1 << ram_index_width          // 每个 way 的 entry 数量：256
  val block_offset_width = 5                         // Offset 宽度：5 bits -> 32 bytes per line
  val cache_block_bytes = 1 << block_offset_width   // Cache line 大小：32 字节
  val cache_block_width = cache_block_bytes * 8     // Cache line 位宽：256 bits（用于存储到 RAM）

  val inst_address_width = 64                        // 物理地址宽度：64 bits
  val tag_width = inst_address_width - ram_index_width - block_offset_width  // Tag 宽度：51 bits

  // ========== 存储结构定义 ==========
  // 修改原因：按照 2-way 设计，每个 way 需要独立的 data RAM 和 tag RAM
  val data_ram0 = SyncReadMem(ram_entry_num, UInt(cache_block_width.W))  // Way 0 数据 RAM
  val data_ram1 = SyncReadMem(ram_entry_num, UInt(cache_block_width.W))  // Way 1 数据 RAM

  val tag_ram0 = SyncReadMem(ram_entry_num, UInt(tag_width.W))           // Way 0 标签 RAM
  val tag_ram1 = SyncReadMem(ram_entry_num, UInt(tag_width.W))           // Way 1 标签 RAM

  // 修改原因：valid bit 用寄存器实现，初始全为 false 表示 cache 为空（冷启动）
  val valid_bit0 = RegInit(VecInit.fill(ram_entry_num)(false.B))         // Way 0 有效位
  val valid_bit1 = RegInit(VecInit.fill(ram_entry_num)(false.B))         // Way 1 有效位

  // ========== 接口初始化 ==========
  // 修改原因：io.commer 的所有信号都是输出，可以初始化
  io.commer.initialize()
  // 修改原因：io.dst.req 是输入（Flipped），不能写入，只初始化输出信号
  io.dst.initialize()

  // ========== 以下是用于参考的示例代码，展示如何使用 NativeMemIO 接口 ==========
  // 这段代码演示了 speak/listen 的使用方式，已注释保留作为参考
  /*
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
  */
  // ========== 示例代码结束 ==========

  // ========== Cache 完整实现开始 ==========

  // ========== 流水线寄存器和锁存信号 ==========
  // 修改原因：SyncReadMem 有一个周期读延迟，需要寄存器缓存地址信息
  val addr_reg = RegInit(0.U(inst_address_width.W))     // 锁存的完整地址
  val index_reg = RegInit(0.U(ram_index_width.W))       // 锁存的 index
  val tag_reg = RegInit(0.U(tag_width.W))               // 锁存的 tag
  val offset_reg = RegInit(0.U(block_offset_width.W))   // 锁存的 offset（用于选择指令字）

  // ========== LFSR 随机替换逻辑 ==========
  // 修改原因：实现随机替换策略，使用 16-bit LFSR，多项式 x^16 + x^14 + x^13 + x^11 + 1
  val lfsr = RegInit(0x1234.U(16.W))  // 初始种子，不能为 0
  lfsr := Cat(lfsr(14,0), lfsr(15) ^ lfsr(13) ^ lfsr(12) ^ lfsr(10))

  // ========== Refill 相关寄存器 ==========
  // 修改原因：4-beat refill 需要计数器和缓冲区
  val beat_cnt = RegInit(0.U(2.W))                      // Beat 计数器：0-3
  val refill_buffer = Reg(Vec(4, UInt(64.W)))           // 4 个 64-bit beat 的缓冲区

  // ========== 地址解析（组合逻辑） ==========
  // 修改原因：从输入地址中提取 tag/index/offset
  val req_addr = io.dst.req.bits.addr
  val req_offset = req_addr(block_offset_width - 1, 0)
  val req_index = req_addr(block_offset_width + ram_index_width - 1, block_offset_width)
  val req_tag = req_addr(inst_address_width - 1, block_offset_width + ram_index_width)

  // ========== TAG 和 Data RAM 读取信号 ==========
  // 修改原因：控制 RAM 的读使能，在 RUN 状态或 WAIT 状态接受新请求时都需要读取
  val tag_read_enable = ((cache_statusReg === RUN) && io.dst.req.fire) ||
                         ((cache_statusReg === UPDATE) && (update_statusReg === WAIT) && io.dst.req.fire)
  val tag0_read = tag_ram0.read(req_index, tag_read_enable)
  val tag1_read = tag_ram1.read(req_index, tag_read_enable)
  val data0_read = data_ram0.read(req_index, tag_read_enable)
  val data1_read = data_ram1.read(req_index, tag_read_enable)

  // ========== TAG 比较和 Hit 检测（使用流水线寄存器） ==========
  // 修改原因：SyncReadMem 读取延迟一个周期，需要用寄存器中的 tag 比较
  val hit0 = valid_bit0(index_reg) && (tag0_read === tag_reg)
  val hit1 = valid_bit1(index_reg) && (tag1_read === tag_reg)
  val cache_hit = hit0 || hit1

  // ========== 主状态机逻辑 ==========

  // 修改原因：为输出信号设置默认值，避免组合逻辑不完整导致锁存器
  io.dst.resp.valid := false.B
  io.dst.resp.bits.data := 0.U

  // ========== 周期分隔和状态监控输出 ==========
  if(dumplog) {
    printf(p"==================== Cycle ====================\n")
    printf(p"[State] cache=${cache_statusReg}")
    when(cache_statusReg === UPDATE) {
      printf(p", update=${update_statusReg}")
    }
    printf(p"\n")
  }

  switch(cache_statusReg) {
    // ========== IDLE 状态：初始状态 ==========
    is(IDLE) {
      // 修改原因：等待第一个请求，然后转入 RUN 状态
      io.dst.req.ready := true.B
      if(dumplog) {
        printf(p"[IDLE] Waiting for first req (req.valid=${io.dst.req.valid})\n")
      }
      when(io.dst.req.fire) {
        cache_statusReg := RUN
        // 锁存地址信息
        addr_reg := req_addr
        index_reg := req_index
        tag_reg := req_tag
        offset_reg := req_offset
        if(dumplog) {
          printf(p"[IDLE] First req received: addr=0x${Hexadecimal(req_addr)}, enter RUN state\n")
        }
      }
    }

    // ========== RUN 状态：正常流水线访问 ==========
    is(RUN) {
      // 修改原因：实现 TAG 检查和数据访问流水线
      io.dst.req.ready := true.B

      // ========== Stage 1 监控：当前周期接收的请求 ==========
      when(io.dst.req.fire) {
        if(dumplog) {
          printf(p"[RUN-S1] Accept new req: addr=0x${Hexadecimal(req_addr)}, tag=0x${Hexadecimal(req_tag)}, index=${req_index}, offset=${req_offset}\n")
        }
      }.otherwise {
        if(dumplog) {
          printf(p"[RUN-S1] No new req (req.valid=${io.dst.req.valid}, req.ready=${io.dst.req.ready})\n")
        }
      }

      // ========== Stage 2 监控：上一周期请求的 TAG 检查和数据访问 ==========
      if(dumplog) {
        printf(p"[RUN-S2] Process prev req: addr=0x${Hexadecimal(addr_reg)}, tag=0x${Hexadecimal(tag_reg)}, index=${index_reg}, offset=${offset_reg}\n")
        printf(p"[RUN-S2] Hit check: valid0=${valid_bit0(index_reg)}, tag0=0x${Hexadecimal(tag0_read)}, hit0=${hit0}, ")
        printf(p"valid1=${valid_bit1(index_reg)}, tag1=0x${Hexadecimal(tag1_read)}, hit1=${hit1}, cache_hit=${cache_hit}\n")
      }

      // Stage 1: 当前周期接收新地址，发起 RAM 读取
      when(io.dst.req.fire) {
        addr_reg := req_addr
        index_reg := req_index
        tag_reg := req_tag
        offset_reg := req_offset
      }

      // Stage 2: 下一周期 TAG 比较和数据返回
      when(cache_hit) {
        // Hit: 从对应 way 读取数据并返回指令
        val hit_data = Mux(hit0, data0_read, data1_read)
        // 修改原因：根据 offset 选择 32-bit 指令（每个 cache line 256-bit 包含 8 条指令）
        val word_offset = offset_reg(block_offset_width - 1, 2)  // 除以 4，得到指令索引（0-7）
        val inst = WireDefault(0.U(32.W))
        switch(word_offset) {
          is(0.U) { inst := hit_data(31, 0) }
          is(1.U) { inst := hit_data(63, 32) }
          is(2.U) { inst := hit_data(95, 64) }
          is(3.U) { inst := hit_data(127, 96) }
          is(4.U) { inst := hit_data(159, 128) }
          is(5.U) { inst := hit_data(191, 160) }
          is(6.U) { inst := hit_data(223, 192) }
          is(7.U) { inst := hit_data(255, 224) }
        }
        io.dst.resp.bits.data := inst
        io.dst.resp.valid := true.B
        if(dumplog) {
          printf(p"[RUN-S2] HIT! way=${Mux(hit0, 0.U, 1.U)}, word_offset=${word_offset}, inst=0x${Hexadecimal(inst)}\n")
        }
      }.otherwise {
        // Miss: 进入 UPDATE 状态
        if(dumplog) {
          printf(p"[RUN-S2] MISS! Enter UPDATE state\n")
        }
        cache_statusReg := UPDATE
        update_statusReg := REQ
        io.dst.req.ready := false.B  // 阻塞新请求
        beat_cnt := 0.U
      }
    }

    // ========== UPDATE 状态：Cache miss 处理 ==========
    is(UPDATE) {
      // 修改原因：锁存流水线资源，TAG RAM 不可读
      io.dst.req.ready := false.B  // 阻塞模式，不接受新请求

      switch(update_statusReg) {
        // ========== REQ 子状态：发送内存请求 ==========
        is(REQ) {
          // 修改原因：使用 speak 方法发送 cache line 起始地址
          val line_addr = Cat(tag_reg, index_reg, 0.U(block_offset_width.W))
          if(dumplog) {
            printf(p"[UPDATE-REQ] Sending mem request: line_addr=0x${Hexadecimal(line_addr)}, commer.req.ready=${io.commer.req.ready}\n")
          }
          when(io.commer.speak(line_addr)) {
            update_statusReg := RESP
            beat_cnt := 0.U
            if(dumplog) {
              printf(p"[UPDATE-REQ] Request accepted, enter RESP state\n")
            }
          }
        }

        // ========== RESP 子状态：接收数据 ==========
        is(RESP) {
          // 修改原因：使用 listen 方法接收 4 个 beat 的数据
          val (done, data) = io.commer.listen()
          if(dumplog) {
            printf(p"[UPDATE-RESP] Waiting beat ${beat_cnt}: done=${done}, data=0x${Hexadecimal(data)}\n")
          }
          when(done) {
            // 将数据存入 buffer
            refill_buffer(beat_cnt) := data
            if(dumplog) {
              printf(p"[UPDATE-RESP] Received beat ${beat_cnt}: 0x${Hexadecimal(data)}\n")
            }

            when(beat_cnt === 3.U) {
              // 收到最后一个 beat，进入 REFILL
              update_statusReg := REFILL
              if(dumplog) {
                printf(p"[UPDATE-RESP] All 4 beats received, enter REFILL state\n")
              }
            }.otherwise {
              // 继续接收下一个 beat
              beat_cnt := beat_cnt + 1.U
            }
          }
        }

        // ========== REFILL 子状态：决定填充到哪个 way ==========
        is(REFILL) {
          // 修改原因：使用 LFSR 随机选择 victim way，写入 tag 和 data
          val victim_way = lfsr(0)  // 使用 LFSR 最低位选择 way
          val refill_data = Cat(refill_buffer(3), refill_buffer(2), refill_buffer(1), refill_buffer(0))

          // 日志：记录 refill 操作的详细信息
          if(dumplog) {
            printf(p"[UPDATE-REFILL] *** Refill to Way ${victim_way} *** index=${index_reg}, tag=0x${Hexadecimal(tag_reg)}\n")
            printf(p"[UPDATE-REFILL] refill_data=0x${Hexadecimal(refill_data)}\n")
          }

          when(victim_way === 0.U) {
            // 替换 way 0
            tag_ram0.write(index_reg, tag_reg)
            data_ram0.write(index_reg, refill_data)
            valid_bit0(index_reg) := true.B
          }.otherwise {
            // 替换 way 1
            tag_ram1.write(index_reg, tag_reg)
            data_ram1.write(index_reg, refill_data)
            valid_bit1(index_reg) := true.B
          }

          update_statusReg := WAIT
          if(dumplog) {
            printf(p"[UPDATE-REFILL] Refill completed, enter WAIT state\n")
          }
        }

        // ========== WAIT 子状态：返回 refill 的数据并完成 ==========
        is(WAIT) {
          // 修改原因：允许握手，让 CPU 知道请求已完成，避免重复访问
          io.dst.req.ready := true.B

          // 修改原因：当接受新请求时，锁存地址信息，确保下一个周期能正确处理
          when(io.dst.req.fire) {
            addr_reg := req_addr
            index_reg := req_index
            tag_reg := req_tag
            offset_reg := req_offset
            if(dumplog) {
              printf(p"[UPDATE-WAIT] Accept new req: addr=0x${Hexadecimal(req_addr)}, tag=0x${Hexadecimal(req_tag)}, index=${req_index}, offset=${req_offset}\n")
            }
          }

          // 修改原因：从 refill_buffer 直接提取指令返回给 CPU，无需等待下次请求
          val refill_data = Cat(refill_buffer(3), refill_buffer(2), refill_buffer(1), refill_buffer(0))
          val word_offset = offset_reg(block_offset_width - 1, 2)  // 除以 4，得到指令索引（0-7）
          val inst = WireDefault(0.U(32.W))
          switch(word_offset) {
            is(0.U) { inst := refill_data(31, 0) }
            is(1.U) { inst := refill_data(63, 32) }
            is(2.U) { inst := refill_data(95, 64) }
            is(3.U) { inst := refill_data(127, 96) }
            is(4.U) { inst := refill_data(159, 128) }
            is(5.U) { inst := refill_data(191, 160) }
            is(6.U) { inst := refill_data(223, 192) }
            is(7.U) { inst := refill_data(255, 224) }
          }
          io.dst.resp.bits.data := inst
          io.dst.resp.valid := true.B

          if(dumplog) {
            printf(p"[UPDATE-WAIT] word_offset=${word_offset}, inst=0x${Hexadecimal(inst)}, resp.valid=true\n")
            printf(p"[UPDATE-WAIT] req.valid=${io.dst.req.valid}, req.ready=true, fire=${io.dst.req.fire}\n")
          }

          // 状态转换：回到 RUN 状态
          cache_statusReg := RUN
          update_statusReg := REQ
          if(dumplog) {
            printf(p"[UPDATE-WAIT] Return to RUN state\n")
          }
        }
      }
    }
  }

  // ========== Cache 完整实现结束 ==========
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
