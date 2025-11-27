import chisel3._
import circt.stage.ChiselStage
import chisel3.util._
import fudian.{FCMA_ADD_s1, FCMA_ADD_s2, FMUL_s1, FMUL_s2, FMUL_s3, FMULToFADD, RawFloat}
import fudian.utils.Multiplier
import scala.io.Source

object TANHFP32Parameters {
  val ONE     = "h3F800000".U(32.W)
  val NEG_ONE = "hBF800000".U(32.W)
  val ZERO    = "h00000000".U(32.W)
  val NAN     = "h7FC00000".U(32.W)
  
  def loadLUT(filename: String): Seq[(Int, String, String, String)] = {
    Source.fromFile(filename).getLines()
      .filterNot(_.trim.isEmpty)
      .map { line =>
        val parts = line.trim.split("\\s+")
        (parts(0).toInt, parts(1), parts(2), parts(3))
      }.toSeq.sortBy(_._1)
  }
}

object TANHFP32Utils {
  implicit class DecoupledPipe[T <: Data](val decoupledBundle: DecoupledIO[T]) extends AnyVal {
    def handshakePipeIf(en: Boolean): DecoupledIO[T] = {
      if (en) {
        val out = Wire(Decoupled(chiselTypeOf(decoupledBundle.bits)))
        val rValid = RegInit(false.B)
        val rBits  = Reg(chiselTypeOf(decoupledBundle.bits))
        decoupledBundle.ready  := !rValid || out.ready
        out.valid              := rValid
        out.bits               := rBits
        when(decoupledBundle.fire) {
          rBits  := decoupledBundle.bits
          rValid := true.B
        } .elsewhen(out.fire) {
          rValid := false.B
        }
        out
      } else {
        decoupledBundle
      }
    }
  }
}

import TANHFP32Utils._

class ADDFP32[T <: Bundle](ctrlSignals: T) extends Module {
  val expWidth  = 8
  val precision = 24
  
  class InBundle extends Bundle {
    val a    = UInt(32.W)
    val b    = UInt(32.W)
    val rm   = UInt(3.W)
    val ctrl = ctrlSignals.cloneType.asInstanceOf[T]
  }
  
  class OutBundle extends Bundle {
    val result = UInt(32.W)
    val ctrl   = ctrlSignals.cloneType.asInstanceOf[T]
  }
  
  val io = IO(new Bundle {
    val in  = Flipped(Decoupled(new InBundle))
    val out = Decoupled(new OutBundle)
  })
  
  val addS1 = Module(new FCMA_ADD_s1(expWidth, precision, precision))
  val addS2 = Module(new FCMA_ADD_s2(expWidth, precision, precision))
  
  addS1.io.a             := io.in.bits.a
  addS1.io.b             := io.in.bits.b
  addS1.io.b_inter_valid := false.B
  addS1.io.b_inter_flags := DontCare
  addS1.io.rm            := io.in.bits.rm
  
  val s1 = Wire(Decoupled(new Bundle {
    val out  = addS1.io.out.cloneType
    val ctrl = ctrlSignals.cloneType.asInstanceOf[T]
  }))
  val s1Pipe = s1.handshakePipeIf(true)
  
  s1.valid         := io.in.valid
  s1.bits.out      := addS1.io.out
  s1.bits.ctrl     := io.in.bits.ctrl
  io.in.ready      := s1.ready
  
  addS2.io.in := s1Pipe.bits.out
  
  val s2     = Wire(Decoupled(new OutBundle))
  val s2Pipe = s2.handshakePipeIf(true)
  
  s2.valid       := s1Pipe.valid
  s2.bits.result := addS2.io.result
  s2.bits.ctrl   := s1Pipe.bits.ctrl
  s1Pipe.ready   := s2.ready
  
  io.out <> s2Pipe
}

class MULFP32[T <: Bundle](ctrlSignals: T) extends Module {
  val expWidth  = 8
  val precision = 24
  
  class InBundle extends Bundle {
    val a    = UInt(32.W)
    val b    = UInt(32.W)
    val rm   = UInt(3.W)
    val ctrl = ctrlSignals.cloneType.asInstanceOf[T]
  }
  
  class OutBundle extends Bundle {
    val result = UInt(32.W)
    val toAdd  = new FMULToFADD(expWidth, precision)
    val ctrl   = ctrlSignals.cloneType.asInstanceOf[T]
  }
  
  val io = IO(new Bundle {
    val in  = Flipped(Decoupled(new InBundle))
    val out = Decoupled(new OutBundle)
  })
  
  val mul   = Module(new Multiplier(precision + 1, pipeAt = Seq()))
  val mulS1 = Module(new FMUL_s1(expWidth, precision))
  val mulS2 = Module(new FMUL_s2(expWidth, precision))
  val mulS3 = Module(new FMUL_s3(expWidth, precision))
  
  mulS1.io.a  := io.in.bits.a
  mulS1.io.b  := io.in.bits.b
  mulS1.io.rm := io.in.bits.rm
  
  val rawA = RawFloat.fromUInt(io.in.bits.a, expWidth, precision)
  val rawB = RawFloat.fromUInt(io.in.bits.b, expWidth, precision)
  
  mul.io.a := rawA.sig
  mul.io.b := rawB.sig
  mul.io.regEnables.foreach(_ := true.B)
  
  val s1 = Wire(Decoupled(new Bundle {
    val mulS1Out = mulS1.io.out.cloneType
    val prod     = mul.io.result.cloneType
    val ctrl     = ctrlSignals.cloneType.asInstanceOf[T]
  }))
  val s1Pipe = s1.handshakePipeIf(true)
  
  s1.valid         := io.in.valid
  s1.bits.mulS1Out := mulS1.io.out
  s1.bits.prod     := mul.io.result
  s1.bits.ctrl     := io.in.bits.ctrl
  io.in.ready      := s1.ready
  
  mulS2.io.in   := s1Pipe.bits.mulS1Out
  mulS2.io.prod := s1Pipe.bits.prod
  
  val s2 = Wire(Decoupled(new Bundle {
    val mulS2Out = mulS2.io.out.cloneType
    val ctrl     = ctrlSignals.cloneType.asInstanceOf[T]
  }))
  val s2Pipe = s2.handshakePipeIf(true)
  
  s2.valid         := s1Pipe.valid
  s2.bits.mulS2Out := mulS2.io.out
  s2.bits.ctrl     := s1Pipe.bits.ctrl
  s1Pipe.ready     := s2.ready
  
  mulS3.io.in := s2Pipe.bits.mulS2Out
  
  val s3     = Wire(Decoupled(new OutBundle))
  val s3Pipe = s3.handshakePipeIf(true)
  
  s3.valid          := s2Pipe.valid
  s3.bits.result    := mulS3.io.result
  s3.bits.toAdd     := mulS3.io.to_fadd
  s3.bits.ctrl      := s2Pipe.bits.ctrl
  s2Pipe.ready      := s3.ready
  
  io.out <> s3Pipe
}

class CMAFP32[T <: Bundle](ctrlSignals: T) extends Module {
  val expWidth  = 8
  val precision = 24
  
  class InBundle extends Bundle {
    val a    = UInt(32.W)
    val b    = UInt(32.W)
    val c    = UInt(32.W)
    val rm   = UInt(3.W)
    val ctrl = ctrlSignals.cloneType.asInstanceOf[T]
  }
  
  class OutBundle extends Bundle {
    val result = UInt(32.W)
    val ctrl   = ctrlSignals.cloneType.asInstanceOf[T]
  }
  
  val io = IO(new Bundle {
    val in  = Flipped(Decoupled(new InBundle))
    val out = Decoupled(new OutBundle)
  })
  
  class MULToADD extends Bundle {
    val c       = UInt(32.W)
    val topCtrl = ctrlSignals.cloneType.asInstanceOf[T]
  }
  
  val mul   = Module(new MULFP32[MULToADD](new MULToADD))
  val addS1 = Module(new FCMA_ADD_s1(expWidth, precision * 2, precision))
  val addS2 = Module(new FCMA_ADD_s2(expWidth, precision * 2, precision))
  
  mul.io.in.valid             := io.in.valid
  mul.io.in.bits.a            := io.in.bits.a
  mul.io.in.bits.b            := io.in.bits.b
  mul.io.in.bits.rm           := io.in.bits.rm
  mul.io.in.bits.ctrl.c       := io.in.bits.c
  mul.io.in.bits.ctrl.topCtrl := io.in.bits.ctrl
  io.in.ready                 := mul.io.in.ready
  
  addS1.io.a             := Cat(mul.io.out.bits.ctrl.c, 0.U(precision.W))
  addS1.io.b             := mul.io.out.bits.toAdd.fp_prod.asUInt
  addS1.io.b_inter_valid := true.B
  addS1.io.b_inter_flags := mul.io.out.bits.toAdd.inter_flags
  addS1.io.rm            := mul.io.out.bits.toAdd.rm
  
  val s4 = Wire(Decoupled(new Bundle {
    val out  = addS1.io.out.cloneType
    val ctrl = ctrlSignals.cloneType.asInstanceOf[T]
  }))
  val s4Pipe = s4.handshakePipeIf(true)
  
  s4.valid         := mul.io.out.valid
  s4.bits.out      := addS1.io.out
  s4.bits.ctrl     := mul.io.out.bits.ctrl.topCtrl
  mul.io.out.ready := s4.ready
  
  addS2.io.in := s4Pipe.bits.out
  
  val s5     = Wire(Decoupled(new OutBundle))
  val s5Pipe = s5.handshakePipeIf(true)
  
  s5.valid       := s4Pipe.valid
  s5.bits.result := addS2.io.result
  s5.bits.ctrl   := s4Pipe.bits.ctrl
  s4Pipe.ready   := s5.ready
  
  io.out <> s5Pipe
}

class LUTTanh[T <: Bundle](ctrlSignals: T) extends Module {
  class InBundle extends Bundle {
    val index = UInt(6.W)
    val ctrl  = ctrlSignals.cloneType.asInstanceOf[T]
  }
  
  class OutBundle extends Bundle {
    val c0   = UInt(32.W)
    val c1   = UInt(32.W)
    val c2   = UInt(32.W)
    val ctrl = ctrlSignals.cloneType.asInstanceOf[T]
  }
  
  val io = IO(new Bundle {
    val in  = Flipped(Decoupled(new InBundle))
    val out = Decoupled(new OutBundle)
  })
  
  val lut = TANHFP32Parameters.loadLUT("lut.txt")
  
  val c0Table = VecInit(lut.map { case (_, c0, _, _) => c0.U(32.W) })
  val c1Table = VecInit(lut.map { case (_, _, c1, _) => c1.U(32.W) })
  val c2Table = VecInit(lut.map { case (_, _, _, c2) => c2.U(32.W) })
  
  val s1     = Wire(Decoupled(new OutBundle))
  val s1Pipe = s1.handshakePipeIf(true)
  
  s1.valid     := io.in.valid
  s1.bits.c0   := c0Table(io.in.bits.index)
  s1.bits.c1   := c1Table(io.in.bits.index)
  s1.bits.c2   := c2Table(io.in.bits.index)
  s1.bits.ctrl := io.in.bits.ctrl
  
  io.in.ready := s1.ready
  io.out <> s1Pipe
}

class FilterTanhFP32[T <: Bundle](ctrlSignals: T) extends Module {
  class InBundle extends Bundle {
    val in   = UInt(32.W)
    val ctrl = ctrlSignals.cloneType.asInstanceOf[T]
  }
  
  class OutBundle extends Bundle {
    val out       = UInt(32.W)
    val bypass    = Bool()
    val bypassVal = UInt(32.W)
    val sign      = Bool()
    val expField  = UInt(8.W)
    val frac      = UInt(23.W)
    val xAbs      = UInt(32.W)
    val ctrl      = ctrlSignals.cloneType.asInstanceOf[T]
  }
  
  val io = IO(new Bundle {
    val in  = Flipped(Decoupled(new InBundle))
    val out = Decoupled(new OutBundle)
  })
  
  val sign     = io.in.bits.in(31)
  val expField = io.in.bits.in(30, 23)
  val frac     = io.in.bits.in(22, 0)
  val xAbs     = Cat(0.U(1.W), io.in.bits.in(30, 0))
  
  val isNaN     = (expField === "hFF".U) && (frac =/= 0.U)
  val isInf     = (expField === "hFF".U) && (frac === 0.U)
  val isZero    = (expField === 0.U) && (frac === 0.U)
  val isSubnorm = (expField === 0.U) && (frac =/= 0.U)
  
  val e_unbias = expField.zext - 127.S
  
  val smallBypass = e_unbias < -5.S
  val largeBypass = e_unbias >= 3.S
  
  val specialBypass = isNaN || isInf || isZero || isSubnorm
  val rangeBypass   = smallBypass || largeBypass
  
  val specialVal = Wire(UInt(32.W))
  when (isNaN) {
    specialVal := TANHFP32Parameters.NAN
  }.elsewhen (isInf) {
    specialVal := Mux(sign, TANHFP32Parameters.NEG_ONE, TANHFP32Parameters.ONE)
  }.otherwise {
    specialVal := io.in.bits.in
  }
  
  val rangeVal = Wire(UInt(32.W))
  when (smallBypass) {
    rangeVal := io.in.bits.in
  }.otherwise {
    rangeVal := Mux(sign, TANHFP32Parameters.NEG_ONE, TANHFP32Parameters.ONE)
  }
  
  val bypass    = specialBypass || rangeBypass
  val bypassVal = Mux(specialBypass, specialVal, rangeVal)
  
  val s1     = Wire(Decoupled(new OutBundle))
  val s1Pipe = s1.handshakePipeIf(true)
  
  s1.valid          := io.in.valid
  s1.bits.out       := io.in.bits.in
  s1.bits.bypass    := bypass
  s1.bits.bypassVal := bypassVal
  s1.bits.sign      := sign
  s1.bits.expField  := expField
  s1.bits.frac      := frac
  s1.bits.xAbs      := xAbs
  s1.bits.ctrl      := io.in.bits.ctrl
  
  io.in.ready := s1.ready
  io.out <> s1Pipe
}

class SegmentIndexFP32[T <: Bundle](ctrlSignals: T) extends Module {
  class InBundle extends Bundle {
    val expField = UInt(8.W)
    val frac     = UInt(23.W)
    val xAbs     = UInt(32.W)
    val ctrl     = ctrlSignals.cloneType.asInstanceOf[T]
  }
  
  class OutBundle extends Bundle {
    val region = UInt(6.W)
    val xAbs   = UInt(32.W)
    val ctrl   = ctrlSignals.cloneType.asInstanceOf[T]
  }
  
  val io = IO(new Bundle {
    val in  = Flipped(Decoupled(new InBundle))
    val out = Decoupled(new OutBundle)
  })
  
  val e_unbias = io.in.bits.expField.zext - 127.S
  val e_off    = (e_unbias + 5.S).asUInt
  val m_hi3    = io.in.bits.frac(22, 20)
  val region   = Cat(e_off(2, 0), m_hi3)
  
  val s1     = Wire(Decoupled(new OutBundle))
  val s1Pipe = s1.handshakePipeIf(true)
  
  s1.valid       := io.in.valid
  s1.bits.region := region
  s1.bits.xAbs   := io.in.bits.xAbs
  s1.bits.ctrl   := io.in.bits.ctrl
  
  io.in.ready := s1.ready
  io.out <> s1Pipe
}

class TANHFP32 extends Module {
  class InBundle extends Bundle {
    val in = UInt(32.W)
    val rm = UInt(3.W)
  }
  
  class OutBundle extends Bundle {
    val out = UInt(32.W)
  }
  
  val io = IO(new Bundle {
    val in  = Flipped(Decoupled(new InBundle))
    val out = Decoupled(new OutBundle)
  })
  
  class FilterToSegment extends Bundle {
    val rm = UInt(3.W)
  }
  
  val filter = Module(new FilterTanhFP32[FilterToSegment](new FilterToSegment))
  
  io.in.ready               := filter.io.in.ready
  filter.io.in.valid        := io.in.valid
  filter.io.in.bits.in      := io.in.bits.in
  filter.io.in.bits.ctrl.rm := io.in.bits.rm
  
  class SegmentToLUT extends Bundle {
    val rm        = UInt(3.W)
    val bypass    = Bool()
    val bypassVal = UInt(32.W)
    val sign      = Bool()
  }
  
  val segment = Module(new SegmentIndexFP32[SegmentToLUT](new SegmentToLUT))
  
  filter.io.out.ready                 := segment.io.in.ready
  segment.io.in.valid                 := filter.io.out.valid
  segment.io.in.bits.expField         := filter.io.out.bits.expField
  segment.io.in.bits.frac             := filter.io.out.bits.frac
  segment.io.in.bits.xAbs             := filter.io.out.bits.xAbs
  segment.io.in.bits.ctrl.rm          := filter.io.out.bits.ctrl.rm
  segment.io.in.bits.ctrl.bypass      := filter.io.out.bits.bypass
  segment.io.in.bits.ctrl.bypassVal   := filter.io.out.bits.bypassVal
  segment.io.in.bits.ctrl.sign        := filter.io.out.bits.sign
  
  class LUTToCma0 extends Bundle {
    val rm        = UInt(3.W)
    val bypass    = Bool()
    val bypassVal = UInt(32.W)
    val sign      = Bool()
    val xAbs      = UInt(32.W)
  }
  
  val lut = Module(new LUTTanh[LUTToCma0](new LUTToCma0))
  
  segment.io.out.ready          := lut.io.in.ready
  lut.io.in.valid               := segment.io.out.valid
  lut.io.in.bits.index          := segment.io.out.bits.region
  lut.io.in.bits.ctrl.rm        := segment.io.out.bits.ctrl.rm
  lut.io.in.bits.ctrl.bypass    := segment.io.out.bits.ctrl.bypass
  lut.io.in.bits.ctrl.bypassVal := segment.io.out.bits.ctrl.bypassVal
  lut.io.in.bits.ctrl.sign      := segment.io.out.bits.ctrl.sign
  lut.io.in.bits.ctrl.xAbs      := segment.io.out.bits.xAbs
  
  class Cma0ToCma1 extends Bundle {
    val rm        = UInt(3.W)
    val bypass    = Bool()
    val bypassVal = UInt(32.W)
    val sign      = Bool()
    val c0        = UInt(32.W)
    val xAbs      = UInt(32.W)
  }
  
  val cma0 = Module(new CMAFP32[Cma0ToCma1](new Cma0ToCma1))
  
  lut.io.out.ready               := cma0.io.in.ready
  cma0.io.in.valid               := lut.io.out.valid
  cma0.io.in.bits.a              := lut.io.out.bits.ctrl.xAbs
  cma0.io.in.bits.b              := lut.io.out.bits.c2
  cma0.io.in.bits.c              := lut.io.out.bits.c1
  cma0.io.in.bits.rm             := lut.io.out.bits.ctrl.rm
  cma0.io.in.bits.ctrl.rm        := lut.io.out.bits.ctrl.rm
  cma0.io.in.bits.ctrl.bypass    := lut.io.out.bits.ctrl.bypass
  cma0.io.in.bits.ctrl.bypassVal := lut.io.out.bits.ctrl.bypassVal
  cma0.io.in.bits.ctrl.sign      := lut.io.out.bits.ctrl.sign
  cma0.io.in.bits.ctrl.c0        := lut.io.out.bits.c0
  cma0.io.in.bits.ctrl.xAbs      := lut.io.out.bits.ctrl.xAbs
  
  class Cma1ToMux extends Bundle {
    val bypass    = Bool()
    val bypassVal = UInt(32.W)
    val sign      = Bool()
  }
  
  val cma1 = Module(new CMAFP32[Cma1ToMux](new Cma1ToMux))
  
  cma0.io.out.ready               := cma1.io.in.ready
  cma1.io.in.valid                := cma0.io.out.valid
  cma1.io.in.bits.a               := cma0.io.out.bits.ctrl.xAbs
  cma1.io.in.bits.b               := cma0.io.out.bits.result
  cma1.io.in.bits.c               := cma0.io.out.bits.ctrl.c0
  cma1.io.in.bits.rm              := cma0.io.out.bits.ctrl.rm
  cma1.io.in.bits.ctrl.bypass     := cma0.io.out.bits.ctrl.bypass
  cma1.io.in.bits.ctrl.bypassVal  := cma0.io.out.bits.ctrl.bypassVal
  cma1.io.in.bits.ctrl.sign       := cma0.io.out.bits.ctrl.sign
  
  val ySigned = Mux(cma1.io.out.bits.ctrl.sign, 
                    Cat(1.U(1.W), cma1.io.out.bits.result(30, 0)), 
                    cma1.io.out.bits.result)
  
  val finalResult = Mux(cma1.io.out.bits.ctrl.bypass, 
                        cma1.io.out.bits.ctrl.bypassVal, 
                        ySigned)
  
  val sOut     = Wire(Decoupled(new OutBundle))
  val sOutPipe = sOut.handshakePipeIf(true)
  
  sOut.valid       := cma1.io.out.valid
  sOut.bits.out    := finalResult
  cma1.io.out.ready := sOut.ready
  
  io.out <> sOutPipe
}

object TANHFP32Gen extends App {
  ChiselStage.emitSystemVerilogFile(
    new TANHFP32,
    Array("--target-dir", "rtl"),
    Array("-lowering-options=disallowLocalVariables")
  )
}
