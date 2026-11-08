package io.agenticpayments.replay;

import io.agenticpayments.domain.*;
import io.agenticpayments.infrastructure.*;
import io.agenticpayments.application.*;
import io.agenticpayments.sdk.*;
import java.util.*;

/** Digests of actual loaded runtime class bytes, not caller-supplied compatibility labels. */
public final class ReplayArtifacts {
    private ReplayArtifacts() {}
    public static ReplayPlan.Artifacts current() {
        return new ReplayPlan.Artifacts(coreDigest(),
                classes(JdbcSimulatorAdapter.class,PaymentAdapter.class,OperationIdentity.class,ChargeOperation.class,Fact.class,Money.class,ExecutionScope.class,ActionType.class,Outcome.class,Preparation.class,LookupResult.class,CapabilityOffer.class,CapabilityCombination.class,AdapterUnavailableException.class,type("io.agenticpayments.sdk.SdkValidation")),catalogDigest(),
                classes(ReplayNormalizer.class,ReplayPlan.class,ReplayRunner.class,ReplayActors.class,SeededChoice.class,ReplayRepository.class,ReplayJson.class,ReplayPlanJson.class,ReplaySql.class,ReplayReport.class,ReplayArtifacts.class,ReplayExamples.class,ReplayMain.class));
    }
    public static String catalogDigest() { return ReplayJson.digest(Map.of("catalog","fixtures-0.1","merchant","merchant-demo","carts",List.of("cart-1250-eur","cart-1250-usd"),"credentialBinding","synthetic-reference-fixture-1","scenarios",new TreeSet<>(PaymentRules.SCENARIOS))); }
    public static String policyDigest() { return ReplayJson.digest(CapabilityOffer.localFake()); }
    private static String coreDigest(){
        var migrations=new TreeMap<String,String>();for(String resource:List.of("V001__simulator.sql","V002__conflict_evidence.sql","V003__accepted_evidence.sql","V004__recorded_replay.sql","V005__admission_evidence.sql","V006__recorded_run_constraints.sql")){
            try(var input=DatabaseMigrator.class.getResourceAsStream("/db/"+resource)){if(input==null)throw new IllegalStateException("Migration artifact missing");migrations.put(resource,"sha256:"+HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(input.readAllBytes())));}catch(Exception e){throw new IllegalStateException("Migration artifact cannot be identified",e);}
        }
        return ReplayJson.digest(Map.of("classes",classes(PaymentRules.class,OperationBinding.class,PaymentService.class,PaymentStore.class,PaymentException.class,ApplicationException.class,ApplicationFailure.class,io.agenticpayments.http.HttpFailureMapper.class,WorkerReceipt.class,JdbcPaymentStore.class,DatabaseMigrator.class,PaymentServices.class,type("io.agenticpayments.infrastructure.Sql")),"migrations",migrations));
    }
    private static Class<?> type(String name){try{return Class.forName(name);}catch(ClassNotFoundException e){throw new IllegalStateException("Runtime class missing",e);}}
    private static String classes(Class<?>... types) {
        var entries=new TreeMap<String,String>();
        var all=new LinkedHashSet<Class<?>>();for(Class<?> type:types)include(all,type);
        for(Class<?> type:all) {
            try(var input=type.getResourceAsStream("/"+type.getName().replace('.','/')+".class")) {
                if(input==null)throw new IllegalStateException("Runtime artifact is missing");
                entries.put(type.getName(),"sha256:"+HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(input.readAllBytes())));
            } catch(Exception e){throw new IllegalStateException("Runtime artifact cannot be identified",e);}
        }
        return ReplayJson.digest(entries);
    }
    static Set<String> classFamilyNames(Class<?> root){var family=new LinkedHashSet<Class<?>>();include(family,root);var names=new TreeSet<String>();for(Class<?> type:family)names.add(type.getName());return Set.copyOf(names);}
    private static void include(Set<Class<?>> types,Class<?> type){if(!types.add(type))return;for(Class<?> nested:type.getNestMembers())include(types,nested);}
}
