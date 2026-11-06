package io.agenticpayments.replay;

import io.agenticpayments.domain.PaymentRules;
import java.time.Instant;
import java.util.*;

/** Closed expanded schedule. Its seed documents generation; it never selects work during execution. */
public record ReplayPlan(String formatVersion,String extensionVersion,String profile,String runnerVersion,
                         String normalizationVersion,Artifacts artifacts,String logicalRunName,String seed,
                         String initialClock,Configuration configuration,List<Choice> choices,List<Step> steps) {
    public static final String FORMAT="simulator-plan-0.1",RUNNER="simulator-runner-0.1",NORMALIZATION="simulator-normalized-state-0.1";
    public record Artifacts(String coreDigest,String adapterDigest,String catalogDigest,String normalizerDigest) {
        public Artifacts { digest(coreDigest);digest(adapterDigest);digest(catalogDigest);digest(normalizerDigest); }
    }
    public record Configuration(String budgetMinorUnits,List<String> currencies,List<String> actorAliases,List<String> scenarioIds,String descriptorPolicyDigest) {
        public Configuration {
            if(budgetMinorUnits==null||!budgetMinorUnits.matches("[1-9][0-9]{0,11}")||Long.parseLong(budgetMinorUnits)<1250)bad();
            currencies=List.copyOf(currencies);actorAliases=List.copyOf(actorAliases);scenarioIds=List.copyOf(scenarioIds);digest(descriptorPolicyDigest);
            if(!new HashSet<>(currencies).equals(Set.of("EUR","USD"))||currencies.size()!=2||!new HashSet<>(actorAliases).equals(ReplayActors.ALIASES)||actorAliases.size()!=3||scenarioIds.isEmpty()||new HashSet<>(scenarioIds).size()!=scenarioIds.size()||!PaymentRules.SCENARIOS.containsAll(scenarioIds))bad();
        }
        public static Configuration local(long budget) { return new Configuration(Long.toString(budget),List.of("EUR","USD"),List.of("agent","customer","runner"),new ArrayList<>(new TreeSet<>(PaymentRules.SCENARIOS)),ReplayArtifacts.policyDigest()); }
    }
    public record VersionRef(Integer literal,String resultLabel) {
        public VersionRef { if((literal==null)==(resultLabel==null)||(literal!=null&&literal<1))bad();if(resultLabel!=null)label(resultLabel); }
        public static VersionRef value(int version){return new VersionRef(version,null);}
        public static VersionRef result(String label){return new VersionRef(null,label);}
    }
    public enum Action { CREATE,QUOTE,APPROVE,EXECUTE,COMPLETE_ACTION,REVOKE,ADVANCE_CLOCK,RUN_NEXT,RECONCILE,RECONSTRUCT,ASSERT,INTERLEAVING }
    public sealed interface Parameters permits Create,Quote,Approve,Execute,CompleteAction,Revoke,AdvanceClock,RunNext,Reconcile,Reconstruct,AssertState,AssertBudget,AssertRaw,Interleaving {}
    public record Create(String intent,String scenario,List<String> requiredCapabilities) implements Parameters { public Create {label(intent);if(!PaymentRules.SCENARIOS.contains(scenario))bad();requiredCapabilities=List.copyOf(requiredCapabilities);if(requiredCapabilities.size()>16||new HashSet<>(requiredCapabilities).size()!=requiredCapabilities.size()||!PaymentRules.CAPABILITIES.containsAll(requiredCapabilities))bad();} }
    public record Quote(String intent,VersionRef expectedVersion,String cartAlias) implements Parameters {public Quote{label(intent);Objects.requireNonNull(expectedVersion);if(!Set.of("cart-eur","cart-usd").contains(cartAlias))bad();}}
    public record Approve(String intent,VersionRef expectedVersion,String quoteResult,String grantAlias) implements Parameters {public Approve{label(intent);Objects.requireNonNull(expectedVersion);label(quoteResult);if(!Set.of("grant-eur","grant-usd").contains(grantAlias))bad();}}
    public record Execute(String intent,VersionRef expectedVersion) implements Parameters {public Execute{label(intent);Objects.requireNonNull(expectedVersion);}}
    public record CompleteAction(String intent,VersionRef expectedVersion,String decision) implements Parameters {public CompleteAction{label(intent);Objects.requireNonNull(expectedVersion);if(!Set.of("approve","deny").contains(decision))bad();}}
    public record Revoke(String grantAlias,String reason) implements Parameters {public Revoke{if(!Set.of("grant-eur","grant-usd").contains(grantAlias)||!Set.of("user-request","policy-revoked","test-revocation").contains(reason))bad();}}
    public record AdvanceClock(String instant) implements Parameters {public AdvanceClock{utc(instant);}}
    public record RunNext() implements Parameters {}
    public record Reconcile(String intent,boolean sealIfAbsent) implements Parameters {public Reconcile{label(intent);}}
    public record Reconstruct() implements Parameters {}
    public record AssertState(String invariant,String intent,String expectedState,String expectedExposure) implements Parameters {public AssertState{if(!"intent-state-0.1".equals(invariant))bad();label(intent);if(!Set.of("DRAFT","QUOTED","APPROVED","PREPARING","WAITING_ACTION","READY","ADMITTED","PENDING","UNKNOWN","SUCCEEDED","FAILED","CANCELLED","EXPIRED").contains(expectedState)||!Set.of("NONE","HELD","CONSUMED","RELEASED").contains(expectedExposure))bad();}}
    public record AssertBudget(String invariant,String currency,String heldMinorUnits,String consumedMinorUnits,boolean frozen) implements Parameters {public AssertBudget{if(!"budget-0.1".equals(invariant)||!Set.of("EUR","USD").contains(currency)||!integer(heldMinorUnits)||!integer(consumedMinorUnits))bad();}}
    public record AssertRaw(String invariant) implements Parameters {public AssertRaw{if(!"raw-invariants-0.1".equals(invariant))bad();}}
    public enum Recipe { ADMISSION_REVOKE_SEND_0_1,SEAL_SEND_0_1 }
    public record Interleaving(Recipe recipe,String intent) implements Parameters {public Interleaving{Objects.requireNonNull(recipe);label(intent);}}
    public record Step(int ordinal,String label,String actor,Action action,Parameters parameters,String expectedCode) {
        public Step {if(ordinal<1)bad();ReplayPlan.label(label);if(!ReplayActors.ALIASES.contains(actor))bad();Objects.requireNonNull(action);Objects.requireNonNull(parameters);if(expectedCode==null||!expectedCode.matches("OK|[A-Z][A-Z0-9_]{1,63}"))bad();if(!compatible(action,parameters))bad();}
    }
    public record Choice(int stepOrdinal,String name,List<String> candidates,String selected,String generatorVersion) {
        public Choice(int ordinal,String name,List<String> candidates,String selected){this(ordinal,name,candidates,selected,SeededChoice.VERSION);}
        public Choice{if(stepOrdinal<1||!"scenario".equals(name)||!SeededChoice.VERSION.equals(generatorVersion))bad();candidates=List.copyOf(candidates);if(candidates.isEmpty()||new HashSet<>(candidates).size()!=candidates.size()||!PaymentRules.SCENARIOS.containsAll(candidates)||!candidates.contains(selected))bad();}
    }
    public ReplayPlan {
        if(!FORMAT.equals(formatVersion)||!"0.1.0-draft".equals(extensionVersion)||!PaymentRules.PROFILE.equals(profile)||!RUNNER.equals(runnerVersion)||!NORMALIZATION.equals(normalizationVersion))bad();
        Objects.requireNonNull(artifacts);Objects.requireNonNull(configuration);label(logicalRunName);
        if(!Long.toString(Long.parseLong(seed)).equals(seed))bad();Instant clock=utc(initialClock);
        choices=List.copyOf(choices);steps=List.copyOf(steps);if(steps.isEmpty()||steps.size()>10000)bad();
        var labels=new LinkedHashMap<String,Step>();var intents=new HashSet<String>();
        for(int n=0;n<steps.size();n++){
            Step step=steps.get(n);if(step.ordinal()!=n+1||labels.containsKey(step.label()))bad();
            String intent=intent(step.parameters());
            if(step.parameters() instanceof Create c){if(!intents.add(c.intent())||!configuration.scenarioIds().contains(c.scenario()))bad();}
            else if(intent!=null&&!intents.contains(intent))bad();
            VersionRef version=version(step.parameters());if(version!=null&&version.resultLabel()!=null){Step prior=labels.get(version.resultLabel());if(prior==null||!Objects.equals(intent,intent(prior.parameters())))bad();}
            if(step.parameters() instanceof Approve a){Step prior=labels.get(a.quoteResult());if(prior==null||!(prior.parameters() instanceof Quote q)||!a.intent().equals(q.intent()))bad();}
            if(step.parameters() instanceof AdvanceClock advance){Instant next=utc(advance.instant());if(next.isBefore(clock))bad();clock=next;}
            labels.put(step.label(),step);
        }
        var choiceKeys=new HashSet<String>();
        for(Choice c:choices){if(c.stepOrdinal()>steps.size()||!choiceKeys.add(c.stepOrdinal()+":"+c.name())||!SeededChoice.choose(seed,c.stepOrdinal(),c.name(),c.candidates()).equals(c.selected()))bad();Step step=steps.get(c.stepOrdinal()-1);if(!(step.parameters() instanceof Create create)||!create.scenario().equals(c.selected()))bad();}
    }
    public static ReplayPlan local(String name,String seed,String clock,long budget,List<Step> steps) {return new ReplayPlan(FORMAT,"0.1.0-draft",PaymentRules.PROFILE,RUNNER,NORMALIZATION,ReplayArtifacts.current(),name,seed,clock,Configuration.local(budget),List.of(),steps);}
    public String canonicalBytes(){return ReplayJson.write(this);}
    public String digest(){return PaymentRules.sha256(canonicalBytes());}
    public String configurationDigest(){return ReplayJson.digest(configuration);}
    static String intent(Parameters p){return switch(p){case Create x->x.intent();case Quote x->x.intent();case Approve x->x.intent();case Execute x->x.intent();case CompleteAction x->x.intent();case Reconcile x->x.intent();case AssertState x->x.intent();case Interleaving x->x.intent();default->null;};}
    static VersionRef version(Parameters p){return switch(p){case Quote x->x.expectedVersion();case Approve x->x.expectedVersion();case Execute x->x.expectedVersion();case CompleteAction x->x.expectedVersion();default->null;};}
    private static boolean compatible(Action a,Parameters p){return switch(a){case CREATE->p instanceof Create;case QUOTE->p instanceof Quote;case APPROVE->p instanceof Approve;case EXECUTE->p instanceof Execute;case COMPLETE_ACTION->p instanceof CompleteAction;case REVOKE->p instanceof Revoke;case ADVANCE_CLOCK->p instanceof AdvanceClock;case RUN_NEXT->p instanceof RunNext;case RECONCILE->p instanceof Reconcile;case RECONSTRUCT->p instanceof Reconstruct;case ASSERT->p instanceof AssertState||p instanceof AssertBudget||p instanceof AssertRaw;case INTERLEAVING->p instanceof Interleaving;};}
    static Instant utc(String value){Instant instant=Instant.parse(value);if(!instant.toString().equals(value))bad();return instant;}
    static void label(String value){if(value==null||!value.matches("[a-z][a-z0-9-]{0,63}"))bad();}
    static void digest(String value){if(value==null||!value.matches("sha256:[0-9a-f]{64}"))bad();}
    private static boolean integer(String value){return value!=null&&value.matches("0|[1-9][0-9]{0,11}");}
    private static void bad(){throw new IllegalArgumentException("Invalid closed replay plan");}
}
