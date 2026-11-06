package io.agenticpayments.replay;

import java.util.*;
import static io.agenticpayments.replay.ReplayPlan.*;

public final class ReplayExamples {
    private ReplayExamples(){}
    public static ReplayPlan twoIntents(){
        var steps=new ArrayList<Step>();String seed="42";List<String> candidates=List.of("success","delayed-success");String selected=SeededChoice.choose(seed,2,"scenario",candidates);
        add(steps,"create-alpha","agent",Action.CREATE,new Create("alpha","challenge-then-success",List.of("single-tender","customer-action")));
        add(steps,"create-beta","agent",Action.CREATE,new Create("beta",selected,List.of("single-tender","reconciliation")));
        add(steps,"quote-alpha","agent",Action.QUOTE,new Quote("alpha",VersionRef.result("create-alpha"),"cart-eur"));
        add(steps,"quote-beta","agent",Action.QUOTE,new Quote("beta",VersionRef.result("create-beta"),"cart-eur"));
        add(steps,"approve-alpha","customer",Action.APPROVE,new Approve("alpha",VersionRef.result("quote-alpha"),"quote-alpha","grant-eur"));
        add(steps,"approve-beta","customer",Action.APPROVE,new Approve("beta",VersionRef.result("quote-beta"),"quote-beta","grant-eur"));
        add(steps,"execute-alpha","agent",Action.EXECUTE,new Execute("alpha",VersionRef.result("approve-alpha")));
        add(steps,"execute-beta","agent",Action.EXECUTE,new Execute("beta",VersionRef.result("approve-beta")));
        add(steps,"prepare-alpha","runner",Action.RUN_NEXT,new RunNext());
        add(steps,"reconstruct-services","runner",Action.RECONSTRUCT,new Reconstruct());
        add(steps,"prepare-beta","runner",Action.RUN_NEXT,new RunNext());
        add(steps,"complete-alpha","customer",Action.COMPLETE_ACTION,new CompleteAction("alpha",VersionRef.value(1),"approve"));
        add(steps,"dispatch-beta","runner",Action.RUN_NEXT,new RunNext());
        add(steps,"dispatch-alpha","runner",Action.RUN_NEXT,new RunNext());
        add(steps,"reconcile-beta","runner",Action.RUN_NEXT,new RunNext());
        add(steps,"record-idle","runner",Action.RUN_NEXT,new RunNext());
        add(steps,"assert-alpha","runner",Action.ASSERT,new AssertState("intent-state-0.1","alpha","SUCCEEDED","CONSUMED"));
        add(steps,"assert-beta","runner",Action.ASSERT,new AssertState("intent-state-0.1","beta","SUCCEEDED","CONSUMED"));
        add(steps,"assert-budget","runner",Action.ASSERT,new AssertBudget("budget-0.1","EUR","0","2500",false));
        add(steps,"assert-raw","runner",Action.ASSERT,new AssertRaw("raw-invariants-0.1"));
        return new ReplayPlan(FORMAT,"0.1.0-draft",io.agenticpayments.domain.PaymentRules.PROFILE,RUNNER,NORMALIZATION,ReplayArtifacts.current(),"two-intent-example",seed,"2026-11-06T10:00:00Z",Configuration.local(5000),List.of(new Choice(2,"scenario",candidates,selected)),steps);
    }
    public static void add(List<Step> steps,String label,String actor,Action action,Parameters parameters){steps.add(new Step(steps.size()+1,label,actor,action,parameters,"OK"));}
}
