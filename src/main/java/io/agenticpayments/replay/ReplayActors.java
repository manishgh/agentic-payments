package io.agenticpayments.replay;

import io.agenticpayments.application.PaymentService.Session;
import java.util.*;

final class ReplayActors {
    private ReplayActors() {}
    static final Set<String> ALIASES=Set.of("agent","customer","runner");
    static Session resolve(String tenant,UUID run,String actor) {
        Set<String> roles=switch(actor) {
            case "agent"->Set.of("intent:write","intent:read","execution:write");
            case "customer"->Set.of("approval:write","intent:read","action:complete","authority:revoke");
            case "runner"->Set.of("runner:write","intent:read");
            default->throw new IllegalArgumentException("Unknown fixture actor");
        };
        return new Session(tenant,run,actor.equals("agent")?"agent-demo":"customer-demo",roles,Set.of("merchant-demo"),actor.equals("agent")?Set.of():Set.of("fixture-grant-1","fixture-grant-usd"));
    }
    static String subject(String physical) {
        return switch(physical){case "customer-demo"->"customer";case "agent-demo"->"agent";default->throw new IllegalStateException("Unknown raw actor binding");};
    }
}
