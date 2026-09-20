package dev.ledger.transfer;

import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface TransferRepository extends JpaRepository<Transfer, UUID> {
    @Query("""
            select t from Transfer t
            where (:accountId is null or t.fromAccountId = :accountId or t.toAccountId = :accountId)
              and (:kind is null or t.kind = :kind)
            """)
    Page<Transfer> search(@Param("accountId") UUID accountId, @Param("kind") TransferKind kind, Pageable pageable);
}
