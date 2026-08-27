package io.github.aililuola.mathproofmesh.persistence;

import java.util.Objects;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.support.TransactionTemplate;

public final class JdbcArtifactMetadataSink implements ArtifactMetadataSink {
  private static final String INSERT_ARTIFACT_SQL =
      """
      INSERT INTO artifact (
        content_hash, size_bytes, media_type, storage_path,
        provenance_source, retention_policy
      ) VALUES (
        :contentHash, :sizeBytes, :mediaType, :storagePath,
        :provenanceSource, :retentionPolicy
      )
      ON CONFLICT (content_hash) DO NOTHING
      """;

  private static final String LINK_RUN_SQL =
      """
      INSERT INTO run_artifact (run_id, content_hash, purpose)
      VALUES (:runId, :contentHash, :purpose)
      ON CONFLICT (run_id, content_hash, purpose) DO NOTHING
      """;

  private static final String VERIFY_ARTIFACT_SQL =
      """
      SELECT COUNT(*)
      FROM artifact
      WHERE content_hash = :contentHash
        AND size_bytes = :sizeBytes
        AND storage_path = :storagePath
      """;

  private final JdbcClient jdbc;
  private final TransactionTemplate transactions;

  public JdbcArtifactMetadataSink(
      JdbcClient jdbc, TransactionTemplate transactions) {
    this.jdbc = Objects.requireNonNull(jdbc, "jdbc");
    this.transactions = Objects.requireNonNull(transactions, "transactions");
  }

  @Override
  public void register(ArtifactMetadata metadata) {
    Objects.requireNonNull(metadata, "metadata");
    transactions.executeWithoutResult(
        ignored -> {
          jdbc.sql(INSERT_ARTIFACT_SQL)
              .param("contentHash", metadata.contentHash())
              .param("sizeBytes", metadata.sizeBytes())
              .param("mediaType", metadata.mediaType())
              .param("storagePath", metadata.storagePath())
              .param("provenanceSource", metadata.provenanceSource())
              .param("retentionPolicy", metadata.retentionPolicy())
              .update();
          int compatible =
              jdbc.sql(VERIFY_ARTIFACT_SQL)
                  .param("contentHash", metadata.contentHash())
                  .param("sizeBytes", metadata.sizeBytes())
                  .param("storagePath", metadata.storagePath())
                  .query(Integer.class)
                  .single();
          if (compatible != 1) {
            throw new PersistenceException(
                "artifact metadata conflicts with existing content hash");
          }
          jdbc.sql(LINK_RUN_SQL)
              .param("runId", metadata.runId())
              .param("contentHash", metadata.contentHash())
              .param("purpose", metadata.purpose())
              .update();
        });
  }
}
