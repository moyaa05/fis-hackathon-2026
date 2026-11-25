package com.fis.hackathon.ledger.repository;

import com.fis.hackathon.ledger.domain.WalletEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;
import java.util.Optional;

public interface WalletRepository extends JpaRepository<WalletEntity, String> {
    Optional<WalletEntity> findByPartyIdAndStatus(String partyId, String status);
    List<WalletEntity> findAllByOrderByPartyIdAscAddressAsc();
}
