package io.agenticpayments.infrastructure;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.agenticpayments.application.*;
import io.agenticpayments.application.CheckoutConfigurationResolver.ConfigurationIdentity;
import io.agenticpayments.application.CheckoutLifecycle.StageCommand;
import io.agenticpayments.application.CheckoutLifecycleTransactions.Purchase;
import io.agenticpayments.checkout.CheckoutCommandTransport.Prepared;
import io.agenticpayments.checkout.CheckoutSessions;
import io.agenticpayments.composition.Composition.*;
import io.agenticpayments.domain.PaymentRules;
import java.sql.*;
import java.util.*;
import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import static io.agenticpayments.infrastructure.Sql.*;

/** Same-connection source validation; the caller already owns the simulation run lock. */
final class JdbcObservedCheckoutSource {
    private final ObjectMapper json=new ObjectMapper(); // Exact existing lifecycle encoding, deliberately unsorted.
    private record Payload(StageCommand input,ConfigurationIdentity configuration,Prepared prepared,int stagedRevision) {}

    ObservedCheckout load(Connection c,Scope scope,String principal,UUID purchaseId,int revision,UUID reference) throws SQLException {
        try { return loadVerified(c,scope,principal,purchaseId,revision,reference); }
        catch(ApplicationException error) { throw error; }
        catch(RuntimeException error) { throw new PaymentException(ApplicationFailure.AUTHORITY_BINDING_INVALID); }
    }
    private ObservedCheckout loadVerified(Connection c,Scope scope,String principal,UUID purchaseId,int revision,UUID reference) throws SQLException {
        var row=one(c,"SELECT * FROM checkout_purchases WHERE tenant_id=? AND run_id=? AND principal_id=? AND purchase_id=?",
                scope.tenantId(),scope.runId(),principal,purchaseId);
        require(row!=null,ApplicationFailure.RESOURCE_NOT_FOUND);
        Purchase purchase=decode(text(row,"purchase_json"),Purchase.class);
        require(purchase.id().equals(purchaseId)&&purchase.configuration().binding().tenantId().equals(scope.tenantId())
                &&purchase.configuration().binding().principalId().equals(principal)
                &&purchase.configuration().binding().merchantId().equals(text(row,"merchant_id"))
                &&purchase.configuration().binding().configurationId().equals(text(row,"configuration_id"))
                &&purchase.configuration().configurationVersion().equals(text(row,"configuration_version")),ApplicationFailure.AUTHORITY_BINDING_INVALID);
        require(!purchase.mutationBlocked(),ApplicationFailure.CHECKOUT_MUTATION_BLOCKED);
        var command=one(c,"SELECT * FROM checkout_lifecycle_commands WHERE tenant_id=? AND run_id=? AND principal_id=? AND purchase_id=? AND command_id=?",
                scope.tenantId(),scope.runId(),principal,purchaseId,purchase.currentCommandId());
        require(command!=null,ApplicationFailure.AUTHORITY_BINDING_INVALID);
        require(!Set.of("STAGED","CLAIMED").contains(text(command,"state")),ApplicationFailure.CHECKOUT_COMMAND_IN_FLIGHT);
        require(purchase.revision()==revision&&Objects.equals(command.get("response_reference"),reference),ApplicationFailure.CHECKOUT_CHANGED);
        require("RECORDED".equals(text(command,"state"))&&"OK".equals(text(command,"outcome_code"))
                &&"OBSERVED".equals(text(command,"observation"))&&number(command,"http_status")>=200&&number(command,"http_status")<300,
                ApplicationFailure.AUTHORITY_BINDING_INVALID);
        Payload payload=decode(text(command,"immutable_json"),Payload.class);
        var input=payload.input(); var prepared=payload.prepared(); var request=prepared.request();
        require(payload.stagedRevision()<Integer.MAX_VALUE&&purchase.revision()==payload.stagedRevision()+1
                &&input.expectedRevision()<Integer.MAX_VALUE&&input.expectedRevision()+1==payload.stagedRevision()
                &&fingerprint(input).equals(text(command,"fingerprint"))
                &&payload.configuration().equals(purchase.configuration())
                &&input.merchantId().equals(purchase.configuration().binding().merchantId())
                &&input.configurationId().equals(purchase.configuration().binding().configurationId())
                &&input.currency().equals(purchase.currency())&&input.operation()==request.operation()
                &&payload.configuration().binding().equals(request.binding())&&input.currency().equals(request.currency())
                &&input.itemIds().equals(request.items().stream().map(CheckoutSessions.ItemReference::id).toList())
                &&payload.configuration().transportProfile().equals(prepared.profile())
                &&payload.configuration().encodingVersion().equals(prepared.encodingVersion())
                &&(request.sessionId()==null||Objects.equals(request.sessionId(),purchase.sessionId())),ApplicationFailure.AUTHORITY_BINDING_INVALID);
        var current=one(c,"SELECT e.metadata_json FROM checkout_current_configurations p JOIN checkout_configurations e USING(tenant_id,run_id,principal_id,merchant_id,configuration_id,configuration_version) WHERE p.tenant_id=? AND p.run_id=? AND p.principal_id=? AND p.merchant_id=? AND p.configuration_id=?",
                scope.tenantId(),scope.runId(),principal,input.merchantId(),input.configurationId());
        require(current!=null&&purchase.configuration().equals(decode(text(current,"metadata_json"),ConfigurationIdentity.class)),ApplicationFailure.CONFIGURATION_CHANGED);
        var outcome=one(c,"SELECT * FROM checkout_lifecycle_outcomes WHERE tenant_id=? AND run_id=? AND principal_id=? AND purchase_id=? AND response_reference=?",
                scope.tenantId(),scope.runId(),principal,purchaseId,reference);
        require(outcome!=null&&!Boolean.TRUE.equals(outcome.get("stale"))&&Objects.equals(outcome.get("command_id"),command.get("command_id"))
                &&outcome.get("claim_token")!=null
                &&PaymentRules.sha256(encode(prepared)).substring(7).equals(outcome.get("request_digest")),ApplicationFailure.AUTHORITY_BINDING_INVALID);
        require(outcome.get("observed_at")!=null,ApplicationFailure.UNSUPPORTED_CAPABILITY);
        var result=decode(text(outcome,"result_json"),CheckoutSessions.Result.class);
        var document=result.document();
        require(result.code()==CheckoutSessions.Code.OK&&result.observation()==CheckoutSessions.Observation.OBSERVED
                &&result.httpStatus()==number(command,"http_status")&&document!=null
                &&document.session().binding().equals(purchase.configuration().binding())
                &&Objects.equals(document.session().id(),purchase.sessionId())&&document.session().currency().equals(purchase.currency())
                &&Objects.equals(document.status(),purchase.remoteStatus())&&"ready_for_payment".equals(purchase.remoteStatus())
                &&PaymentRules.sha256(Base64.getEncoder().encodeToString(document.bytes())).equals(purchase.documentDigest()),ApplicationFailure.AUTHORITY_BINDING_INVALID);
        return new ObservedCheckout(scope,purchaseId,uuid(command,"command_id"),reference,revision,purchase.configuration(),
                instant(outcome,"observed_at"),document,document.bytes());
    }
    private String encode(Object value) {
        try { return json.writeValueAsString(value); } catch(Exception error) { throw new PaymentException(ApplicationFailure.AUTHORITY_BINDING_INVALID); }
    }
    private String fingerprint(StageCommand input) {
        try {
            var bytes=new ByteArrayOutputStream(); var output=new DataOutputStream(bytes);
            for(String value:new String[]{CheckoutLifecycle.CONTRACT_VERSION,input.operation().name(),Integer.toString(input.expectedRevision()),
                    input.merchantId(),input.configurationId(),input.currency()}) write(output,value);
            output.writeInt(input.itemIds().size()); for(String item:input.itemIds()) write(output,item);
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes.toByteArray()));
        } catch(Exception error) { throw new PaymentException(ApplicationFailure.AUTHORITY_BINDING_INVALID); }
    }
    private static void write(DataOutputStream output,String text) throws java.io.IOException {
        byte[] bytes=text.getBytes(StandardCharsets.UTF_8); output.writeInt(bytes.length); output.write(bytes);
    }
    private <T> T decode(String value,Class<T> type) {
        try { return json.readValue(value,type); } catch(Exception error) { throw new PaymentException(ApplicationFailure.AUTHORITY_BINDING_INVALID); }
    }
    private static void require(boolean valid,ApplicationFailure failure) { if(!valid) throw new PaymentException(failure); }
}
