import com.termux.app.iqcode.core.PlanApprovalGate;
import com.termux.app.iqcode.model.PlanWorkflowState;

import java.util.concurrent.atomic.AtomicReference;

public final class PlanApprovalGateTest {
    private static void require(boolean value,String message){if(!value)throw new AssertionError(message);}
    public static void main(String[]args)throws Exception{
        PlanApprovalGate gate=new PlanApprovalGate();
        PlanWorkflowState plan=PlanWorkflowState.planning("workflow-test","default","/tmp/plan.md").withPlan("/tmp/plan.md","# Plan").awaitingApproval();
        AtomicReference<PlanApprovalGate.ApprovalRequest> request=new AtomicReference<>();
        AtomicReference<PlanApprovalGate.ApprovalResponse> response=new AtomicReference<>();
        Thread waiter=new Thread(()->{try{response.set(gate.request(plan,request::set));}catch(Exception e){throw new RuntimeException(e);}});
        waiter.start();
        for(int i=0;i<100&&request.get()==null;i++)Thread.sleep(5);
        require(request.get()!=null,"approval request must be published");
        require(waiter.isAlive(),"approval must block until the user responds");
        require(gate.respond(request.get().requestId,PlanApprovalGate.Decision.KEEP_PLANNING,"add tests"),"first response must succeed");
        require(!gate.respond(request.get().requestId,PlanApprovalGate.Decision.APPROVE,""),"duplicate response must be ignored");
        waiter.join(1000);
        require(!waiter.isAlive(),"response must release the waiting agent");
        require(response.get().decision==PlanApprovalGate.Decision.KEEP_PLANNING,"decision must be preserved");
        require("add tests".equals(response.get().feedback),"feedback must be preserved");
        System.out.println("PlanApprovalGateTest PASS");
    }
}
