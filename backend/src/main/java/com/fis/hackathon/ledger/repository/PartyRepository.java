package com.fis.hackathon.ledger.repository;

import com.fis.hackathon.ledger.domain.PartyEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;

public interface PartyRepository extends JpaRepository<PartyEntity, String> {
    List<PartyEntity> findByRoleOrderByIdAsc(String role);
}
