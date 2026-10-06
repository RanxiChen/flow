package flow.backend
import flow.sim._
import org.scalatest.freespec.AnyFreeSpec
import org.scalatest.matchers.must.Matchers

class TraceProtocolSpec extends AnyFreeSpec with Matchers {
  private def event(rd: Int, fp: Boolean = false, pending: Boolean = true): RawCommitEvent =
    RawCommitEvent(true,0x400,0x33,0x404,false,!pending,rd,0,false,false,0,0,0,0,0,pending,fp)
  "S15_trace_keeps_commit_and_two_bank_late_events_independent" in {
    val t=new PendingTrace
    t.commit(event(1)); t.commit(event(0,fp=true))
    t.commit(event(2,pending=false))
    t.complete(LateRegisterEvent(7,false,1,42,false))
    t.complete(LateRegisterEvent(8,true,0,0x1234,false)); t.finish()
    RawCommitEventParser.toCommitUpdate(event(1)).regWrite mustBe None
  }
  "S15_trace_rejects_duplicate_missing_and_ordinary_overlap" in {
    val t=new PendingTrace; t.commit(event(1))
    intercept[IllegalArgumentException](t.commit(event(1)))
    intercept[IllegalArgumentException](t.commit(event(1,pending=false)))
    intercept[IllegalArgumentException](t.complete(LateRegisterEvent(4,false,2,0,false)))
    intercept[IllegalArgumentException](t.finish())
    t.complete(LateRegisterEvent(6,false,1,0,error=true)); t.finish()
    intercept[IllegalArgumentException](t.complete(LateRegisterEvent(7,false,1,0,false)))
  }
}
