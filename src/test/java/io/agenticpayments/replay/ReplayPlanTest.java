package io.agenticpayments.replay;

import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class ReplayPlanTest {
    @Test void namedChoiceGoldenVectorUsesUnsignedBigEndianSha256(){assertEquals(1,SeededChoice.index("42",2,"scenario",2));assertEquals("delayed-success",SeededChoice.choose("42",2,"scenario",List.of("success","delayed-success")));assertEquals(0,SeededChoice.index("42",2,"scenario",1));assertEquals(1,SeededChoice.index("42",17,"scenario",7));assertEquals(1,SeededChoice.index("-9223372036854775808",17,"scenario",7));assertEquals(3,SeededChoice.index("9223372036854775807",17,"scenario",7));}
    @Test void canonicalExpandedPlanRoundTripsStrictly(){ReplayPlan plan=ReplayExamples.twoIntents();assertEquals(plan,ReplayPlanJson.parse(plan.canonicalBytes()));assertEquals(plan.digest(),ReplayPlanJson.parse(plan.canonicalBytes()).digest());}
    @Test void unknownDuplicateMissingCoercedOrTrailingFieldsRejectBeforeRunCreation(){String json=ReplayExamples.twoIntents().canonicalBytes();assertThrows(IllegalArgumentException.class,()->ReplayPlanJson.parse(json.substring(0,json.length()-1)+",\"unknown\":true}"));assertThrows(IllegalArgumentException.class,()->ReplayPlanJson.parse(json.replace("\"seed\":\"42\"","\"seed\":\"42\",\"seed\":\"42\"")));assertThrows(IllegalArgumentException.class,()->ReplayPlanJson.parse(json.replace("\"ordinal\":1,","\"ordinal\":\"1\",")));assertThrows(IllegalArgumentException.class,()->ReplayPlanJson.parse(json+" {}"));assertThrows(IllegalArgumentException.class,()->ReplayPlanJson.parse(json.replace("\"seed\":\"42\",","")));}
    @Test void invalidSeedAndUnrecordedChoiceDoNotSilentlyPickDifferentFaults(){assertThrows(IllegalArgumentException.class,()->SeededChoice.index("042",2,"scenario",2));ReplayPlan p=ReplayExamples.twoIntents();var choices=List.of(new ReplayPlan.Choice(2,"scenario",List.of("success","delayed-success"),"success"));assertThrows(IllegalArgumentException.class,()->new ReplayPlan(p.formatVersion(),p.extensionVersion(),p.profile(),p.runnerVersion(),p.normalizationVersion(),p.artifacts(),p.logicalRunName(),p.seed(),p.initialClock(),p.configuration(),choices,p.steps()));}
    @Test void nullBooleanCannotSilentlyBecomeAnExpandedFalseChoice(){String json=ReplayExamples.twoIntents().canonicalBytes();assertThrows(IllegalArgumentException.class,()->ReplayPlanJson.parse(json.replace("\"frozen\":false","\"frozen\":null")));}
    @Test void fixedCanonicalPlanGoldenIsIndependentOfPhysicalIdsAndRuntimeHashes(){
        String zero="sha256:"+"0".repeat(64);var artifact=new ReplayPlan.Artifacts(zero,zero,zero,zero);var config=new ReplayPlan.Configuration("1250",List.of("EUR","USD"),List.of("agent","customer","runner"),List.of("success"),zero);
        var step=new ReplayPlan.Step(1,"create-one","agent",ReplayPlan.Action.CREATE,new ReplayPlan.Create("one","success",List.of("single-tender")),"OK");
        var plan=new ReplayPlan(ReplayPlan.FORMAT,"0.1.0-draft",io.agenticpayments.domain.PaymentRules.PROFILE,ReplayPlan.RUNNER,ReplayPlan.NORMALIZATION,artifact,"golden-plan","0","2026-11-06T10:00:00Z",config,List.of(),List.of(step));
        assertEquals(1106,plan.canonicalBytes().getBytes(java.nio.charset.StandardCharsets.UTF_8).length);assertEquals("sha256:9a1295cff12ba869f7d5af4c4c237eeb9cae31deab8cc3dddfb587504a1bed79",plan.digest());
    }
    @Test void actualGeneratedClassFilesAreCoveredByPinnedNestFamilies()throws Exception{
        for(Class<?> root:List.of(io.agenticpayments.infrastructure.JdbcPaymentStore.class,ReplayRunner.class,ReplayPlanJson.class)){
            var resource=root.getResource(root.getSimpleName()+".class");assertNotNull(resource);var parent=java.nio.file.Path.of(resource.toURI()).getParent();
            try(var files=java.nio.file.Files.list(parent)){var compiled=files.map(path->path.getFileName().toString()).filter(name->name.equals(root.getSimpleName()+".class")||(name.startsWith(root.getSimpleName()+"$")&&name.endsWith(".class"))).map(name->root.getPackageName()+"."+name.substring(0,name.length()-6)).collect(java.util.stream.Collectors.toSet());assertTrue(compiled.stream().anyMatch(name->name.endsWith("$1")));assertEquals(compiled,ReplayArtifacts.classFamilyNames(root));}
        }
    }
}
