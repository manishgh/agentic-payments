package io.agenticpayments.core;

import io.agenticpayments.domain.OperationBinding;
import org.junit.jupiter.api.Test;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import static org.junit.jupiter.api.Assertions.*;

class OperationBindingTest {
    private OperationBinding fixture(String scenario) {
        return new OperationBinding("test-tenant",UUID.fromString("00000000-0000-0000-0000-000000000001"),"merchant-demo",
                UUID.fromString("00000000-0000-0000-0000-000000000002"),UUID.fromString("00000000-0000-0000-0000-000000000003"),
                UUID.fromString("00000000-0000-0000-0000-000000000004"),"sha256:"+"0".repeat(64),"EUR",1250,
                "fixture-grant-1",1,"customer-demo",scenario,"fixture-credential-1");
    }
    @Test void exactVersionedOperationEncodingGoldenDigest() {
        var binding=fixture("success");
        assertEquals(666,binding.canonical().getBytes(StandardCharsets.UTF_8).length);
        assertEquals("sha256:fd3bb56d454d8176c6feefacf3d332e77d6c197a501b039e4daf73de33cafca9",binding.digest());
    }
    @Test void scenarioChangesCannotShareTheOriginalRequestDigest() {
        assertNotEquals(fixture("success").digest(),fixture("definitive-no-effect").digest());
    }
}
