package flow.rvv.zve

import chisel3._
import chisel3.util._

/** The ISA's seven-bit estimates, not a rounded FPnew divide/sqrt substitute. */
class ZveFpEstimate extends Module {
  val io=IO(new Bundle {
    val value=Input(UInt(32.W)); val reciprocalSqrt=Input(Bool()); val rounding=Input(UInt(3.W))
    val result=Output(UInt(32.W)); val flags=Output(UInt(5.W))
  })
  val sign=io.value(31); val exponent=io.value(30,23); val fraction=io.value(22,0)
  val zero=exponent===0.U && fraction===0.U
  val infinity=exponent===255.U && fraction===0.U
  val nan=exponent===255.U && fraction=/=0.U
  val signaling=nan && !fraction(22)
  val zeros=PriorityEncoder(Reverse(fraction))
  val normalizedExponent=Mux(exponent===0.U,-zeros.zext,exponent.zext).pad(11)
  val normalizedFraction=Mux(exponent===0.U,(fraction<<(zeros+1.U))(22,0),fraction)
  val reciprocalExponent=253.S(11.W)-normalizedExponent
  val sqrtExponent=(380.S(11.W)-normalizedExponent)>>1
  val rec=VecInit(ZveEstimateTables.reciprocal.map(_.U(7.W)))(normalizedFraction(22,16))
  val sqrt=VecInit(ZveEstimateTables.reciprocalSqrt.map(_.U(7.W)))(Cat(normalizedExponent.asUInt(0),normalizedFraction(22,17)))
  val significand=Cat(Mux(io.reciprocalSqrt,sqrt,rec),0.U(16.W))
  val outputExponent=Mux(io.reciprocalSqrt,sqrtExponent,reciprocalExponent)
  val denormal=Cat(1.U(1.W),significand)>>(1.S-outputExponent).asUInt
  io.result := Mux(outputExponent<=0.S,Cat(sign,0.U(8.W),denormal(22,0)),
    Cat(sign,outputExponent.asUInt(7,0),significand))
  io.flags := 0.U
  when(!io.reciprocalSqrt && reciprocalExponent>254.S) {
    val finite=io.rounding===1.U || (io.rounding===2.U && !sign) || (io.rounding===3.U && sign)
    io.result := Cat(sign,Mux(finite,"h7f7fffff".U(31.W),"h7f800000".U(31.W)))
    io.flags := 5.U // OF | NX
  }
  when(infinity) { io.result := Cat(sign,0.U(31.W)); io.flags := 0.U }
  when(io.reciprocalSqrt && sign && !zero && !nan) {
    io.result := "h7fc00000".U; io.flags := 16.U
  }
  when(zero) { io.result := Cat(sign,"h7f800000".U(31.W)); io.flags := 8.U }
  when(nan) { io.result := "h7fc00000".U; io.flags := Mux(signaling,16.U,0.U) }
}
