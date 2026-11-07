package io.agenticpayments.infrastructure;

import io.agenticpayments.composition.ArtifactIntegrity;
import java.sql.*;
import java.util.*;
import javax.sql.DataSource;
import static io.agenticpayments.composition.Composition.*;

/** Independently committed immutable artifact staging. This adapter supplies no financial authority. */
public final class JdbcSignedArtifactStore implements SignedArtifactStore {
    private final DataSource dataSource;
    public JdbcSignedArtifactStore(DataSource dataSource) { this.dataSource=Objects.requireNonNull(dataSource); }
    @Override public Descriptor describe() {
        return new Descriptor("signed-artifact-store",VERSION,"postgresql-artifacts",Set.of(PROFILE),
            Set.of("immutable","digest-verified","scoped","durable-retention"),"artifact-store");
    }
    @Override public ArtifactReference put(Scope scope,Artifact artifact) {
        Objects.requireNonNull(scope); Objects.requireNonNull(artifact);
        ArtifactReference reference=ArtifactIntegrity.reference(artifact);
        try(var connection=dataSource.getConnection()) {
            connection.setAutoCommit(false);
            try(var statement=connection.prepareStatement("INSERT INTO native_artifacts(tenant_id,run_id,artifact_id,digest,media_type,profile,content,content_length) VALUES(?,?,?,?,?,?,?,?) ON CONFLICT DO NOTHING")) {
                statement.setString(1,scope.tenantId()); statement.setObject(2,scope.runId()); statement.setString(3,reference.id());
                statement.setString(4,reference.digest()); statement.setString(5,reference.mediaType()); statement.setString(6,reference.profile());
                statement.setBytes(7,artifact.bytes()); statement.setInt(8,reference.length()); statement.executeUpdate();
            }
            ArtifactIntegrity.verify(read(connection,scope,reference),reference);
            connection.commit(); return reference;
        } catch(SQLException e) { throw new CompositionException(Failure.ARTIFACT_UNAVAILABLE); }
    }
    @Override public Artifact get(Scope scope,ArtifactReference reference) {
        Objects.requireNonNull(scope); Objects.requireNonNull(reference);
        try(var connection=dataSource.getConnection()) { return ArtifactIntegrity.verify(read(connection,scope,reference),reference); }
        catch(SQLException e) { throw new CompositionException(Failure.ARTIFACT_UNAVAILABLE); }
    }
    private static Artifact read(Connection connection,Scope scope,ArtifactReference reference) throws SQLException {
        try(var statement=connection.prepareStatement("SELECT digest,media_type,profile,content,content_length FROM native_artifacts WHERE tenant_id=? AND run_id=? AND artifact_id=?")) {
            statement.setString(1,scope.tenantId()); statement.setObject(2,scope.runId()); statement.setString(3,reference.id());
            try(var row=statement.executeQuery()) {
                if(!row.next()) throw new CompositionException(Failure.ARTIFACT_UNAVAILABLE);
                byte[] bytes=row.getBytes("content");
                if(bytes==null||bytes.length!=row.getInt("content_length")||!reference.digest().equals(row.getString("digest"))
                        ||!reference.mediaType().equals(row.getString("media_type"))||!reference.profile().equals(row.getString("profile")))
                    throw new CompositionException(Failure.ARTIFACT_CONFLICT);
                return new Artifact(bytes,row.getString("media_type"),row.getString("profile"));
            }
        }
    }
}
