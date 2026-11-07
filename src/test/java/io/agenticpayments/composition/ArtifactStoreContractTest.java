package io.agenticpayments.composition;

import io.agenticpayments.infrastructure.DatabaseMigrator;
import io.agenticpayments.infrastructure.JdbcSignedArtifactStore;
import org.postgresql.ds.PGSimpleDataSource;
import org.junit.jupiter.api.Test;
import javax.sql.DataSource;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.*;
import static io.agenticpayments.composition.Composition.*;
import static org.junit.jupiter.api.Assertions.*;

/** The same behavioral checks run through different implementations of one vendor-neutral port. */
class ArtifactStoreContractTest {
    static DataSource database() {
        String url=System.getenv("PAYMENTS_DB_URL");
        if(url==null) throw new IllegalStateException("PostgreSQL integration-test environment is required");
        var ds=new PGSimpleDataSource(); ds.setURL(url); ds.setUser(System.getenv("PAYMENTS_DB_USER")); ds.setPassword(System.getenv("PAYMENTS_DB_PASSWORD"));
        DatabaseMigrator.migrate(ds); return ds;
    }
    static Artifact artifact() { return new Artifact("SYNTHETIC_EXACT_ARTIFACT_CANARY".getBytes(StandardCharsets.UTF_8),"application/ap2+jwt",PROFILE); }
    static void commonContract(SignedArtifactStore store) throws Exception {
        store.describe().require("signed-artifact-store",PROFILE,"immutable","digest-verified","scoped");
        Scope scope=new Scope("artifact-contract",UUID.randomUUID());
        byte[] original=artifact().bytes(); Artifact value=new Artifact(original,artifact().mediaType(),PROFILE);
        original[0]=0; assertNotEquals(0,value.bytes()[0]);
        ArtifactReference reference=store.put(scope,value); assertEquals(reference,store.put(scope,value));
        assertArrayEquals(value.bytes(),store.get(scope,reference).bytes());
        byte[] returned=store.get(scope,reference).bytes(); returned[0]=0;
        assertArrayEquals(value.bytes(),store.get(scope,reference).bytes());
        assertThrows(CompositionException.class,()->store.get(new Scope("another-tenant",scope.runId()),reference));
        assertThrows(CompositionException.class,()->store.get(new Scope(scope.tenantId(),UUID.randomUUID()),reference));
        ArtifactReference forged=new ArtifactReference(reference.id(),"sha256:"+"0".repeat(64),reference.length(),reference.mediaType(),reference.profile());
        assertThrows(CompositionException.class,()->store.get(scope,forged));
        ArtifactReference wrongLength=new ArtifactReference(reference.id(),reference.digest(),reference.length()+1,reference.mediaType(),reference.profile());
        assertThrows(CompositionException.class,()->store.get(scope,wrongLength));
        Artifact differentProfile=new Artifact(value.bytes(),value.mediaType(),"another-profile");
        assertNotEquals(reference,store.put(scope,differentProfile));
        assertFalse(value.toString().contains("CANARY")); assertFalse(reference.toString().contains(reference.id()));
        try(var workers=Executors.newVirtualThreadPerTaskExecutor()) {
            var tasks=new ArrayList<Future<ArtifactReference>>();
            for(int n=0;n<12;n++) tasks.add(workers.submit(()->store.put(scope,value)));
            for(var task:tasks) assertEquals(reference,task.get(10,TimeUnit.SECONDS));
        }
    }
    @Test void interchangeableMemoryAndPostgresImplementSameImmutableScopedContract() throws Exception {
        commonContract(new InMemoryArtifactStore());
        commonContract(new JdbcSignedArtifactStore(database()));
    }
    @Test void decoratingStoreRetainsExactBytesAndCannotClaimFinancialAtomicityOrDurabilityFromMemory() throws Exception {
        SignedArtifactStore delegate=new InMemoryArtifactStore();
        SignedArtifactStore decorator=new SignedArtifactStore() {
            public Descriptor describe(){return delegate.describe();}
            public ArtifactReference put(Scope scope,Artifact value){return delegate.put(scope,value);}
            public Artifact get(Scope scope,ArtifactReference ref){return delegate.get(scope,ref);}
        };
        commonContract(decorator);
        assertThrows(CompositionException.class,()->NativeComposition.ports(decorator).requireSupported());
        assertFalse(decorator.describe().capabilities().contains("atomic-financial-admission"));
    }
    @Test void postgresRestartReadsExactArtifactsAndCorruptionFailsClosedWithoutFinancialState() throws Exception {
        DataSource ds=database(); Scope scope=new Scope("artifact-restart",UUID.randomUUID());
        SignedArtifactStore first=new JdbcSignedArtifactStore(ds); ArtifactReference ref=first.put(scope,artifact());
        SignedArtifactStore restarted=new JdbcSignedArtifactStore(ds);
        assertArrayEquals(artifact().bytes(),restarted.get(scope,ref).bytes());
        // Staging is intentionally possible before a simulation/financial aggregate exists.
        try(var c=ds.getConnection();var q=c.prepareStatement("SELECT count(*) FROM payment_attempts WHERE tenant_id=? AND run_id=?")) {
            q.setString(1,scope.tenantId());q.setObject(2,scope.runId());try(var row=q.executeQuery()){assertTrue(row.next());assertEquals(0,row.getLong(1));}
        }
        try(var c=ds.getConnection();var update=c.prepareStatement("UPDATE native_artifacts SET content=? WHERE tenant_id=? AND run_id=? AND artifact_id=?")) {
            update.setBytes(1,"CORRUPTED".getBytes(StandardCharsets.UTF_8));update.setString(2,scope.tenantId());update.setObject(3,scope.runId());update.setString(4,ref.id());assertEquals(1,update.executeUpdate());
        }
        assertThrows(CompositionException.class,()->restarted.get(scope,ref));
        assertThrows(CompositionException.class,()->restarted.put(scope,artifact()));
    }
}
