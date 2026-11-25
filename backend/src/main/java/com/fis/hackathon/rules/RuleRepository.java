package com.fis.hackathon.rules;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface RuleRepository extends JpaRepository<RuleEntity, String> {
    List<RuleEntity> findAllByOrderByPriorityAsc();
}
