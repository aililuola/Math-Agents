package io.github.aililuola.mathproofmesh.persistence;

import java.util.Objects;
import org.springframework.transaction.support.TransactionTemplate;

public final class TransactionalEventStore {
  private final TransactionTemplate transactions;
  private final EventLogRepository eventLog;

  public TransactionalEventStore(
      TransactionTemplate transactions, EventLogRepository eventLog) {
    this.transactions = Objects.requireNonNull(transactions, "transactions");
    this.eventLog = Objects.requireNonNull(eventLog, "eventLog");
  }

  public long commit(DomainMutation mutation, DomainEvent event) {
    Objects.requireNonNull(mutation, "mutation");
    Objects.requireNonNull(event, "event");
    return Objects.requireNonNull(
        transactions.execute(
            ignored -> {
              mutation.execute();
              return eventLog.appendWithOutbox(event);
            }),
        "event transaction result");
  }

  @FunctionalInterface
  public interface DomainMutation {
    void execute();
  }
}
