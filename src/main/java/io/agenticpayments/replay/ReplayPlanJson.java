package io.agenticpayments.replay;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.*;
import static io.agenticpayments.replay.ReplayPlan.*;

/** Strict closed JSON reader; polymorphic parameters are selected only by the declared action. */
public final class ReplayPlanJson {
    private ReplayPlanJson(){}
    public static ReplayPlan parse(String input){
        try{
            JsonNode root=ReplayJson.JSON.readTree(input);closed(root,Set.of("formatVersion","extensionVersion","profile","runnerVersion","normalizationVersion","artifacts","logicalRunName","seed","initialClock","configuration","choices","steps"));
            var steps=new ArrayList<Step>();if(!root.path("steps").isArray())throw new IllegalArgumentException();
            for(JsonNode node:root.path("steps")){
                closed(node,Set.of("ordinal","label","actor","action","parameters","expectedCode"));Action action=Action.valueOf(node.path("action").textValue());JsonNode p=node.path("parameters");
                Class<? extends Parameters> type=switch(action){case CREATE->Create.class;case QUOTE->Quote.class;case APPROVE->Approve.class;case EXECUTE->Execute.class;case COMPLETE_ACTION->CompleteAction.class;case REVOKE->Revoke.class;case ADVANCE_CLOCK->AdvanceClock.class;case RUN_NEXT->RunNext.class;case RECONCILE->Reconcile.class;case RECONSTRUCT->Reconstruct.class;case INTERLEAVING->Interleaving.class;case ASSERT->switch(p.path("invariant").asText()){case "intent-state-0.1"->AssertState.class;case "budget-0.1"->AssertBudget.class;case "raw-invariants-0.1"->AssertRaw.class;default->throw new IllegalArgumentException();};};
                if(!node.path("ordinal").isIntegralNumber()||!node.path("ordinal").canConvertToInt())throw new IllegalArgumentException();
                steps.add(new Step(node.path("ordinal").intValue(),node.path("label").textValue(),node.path("actor").textValue(),action,ReplayJson.JSON.treeToValue(p,type),node.path("expectedCode").textValue()));
            }
            var choices=new ArrayList<Choice>();if(!root.path("choices").isArray())throw new IllegalArgumentException();for(JsonNode choice:root.path("choices"))choices.add(ReplayJson.JSON.treeToValue(choice,Choice.class));
            return new ReplayPlan(root.path("formatVersion").textValue(),root.path("extensionVersion").textValue(),root.path("profile").textValue(),root.path("runnerVersion").textValue(),root.path("normalizationVersion").textValue(),ReplayJson.JSON.treeToValue(root.path("artifacts"),Artifacts.class),root.path("logicalRunName").textValue(),root.path("seed").textValue(),root.path("initialClock").textValue(),ReplayJson.JSON.treeToValue(root.path("configuration"),Configuration.class),choices,steps);
        }catch(Exception e){throw new IllegalArgumentException("Invalid closed replay plan JSON");}
    }
    private static void closed(JsonNode node,Set<String> fields){if(!node.isObject()||node.size()!=fields.size())throw new IllegalArgumentException();node.fieldNames().forEachRemaining(field->{if(!fields.contains(field))throw new IllegalArgumentException();});for(String field:fields)if(node.get(field)==null||node.get(field).isNull())throw new IllegalArgumentException();}
}
