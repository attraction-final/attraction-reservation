package com.example.attractionreservation.domain.attractionSlot.repository;

import com.example.attractionreservation.domain.attractionSlot.entity.AttractionSlot;
import org.springframework.data.jpa.repository.JpaRepository;

// 기본 CRUD는 JpaRepository, 복잡한 조회는 AttractionSlotRepositoryCustom(QueryDSL)
public interface AttractionSlotRepository extends JpaRepository<AttractionSlot, Long>, AttractionSlotRepositoryCustom {
}
