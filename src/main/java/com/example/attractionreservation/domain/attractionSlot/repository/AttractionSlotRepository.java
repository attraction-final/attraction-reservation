package com.example.attractionreservation.domain.attractionSlot.repository;

import com.example.attractionreservation.domain.attractionSlot.entity.AttractionSlot;
import org.springframework.data.jpa.repository.JpaRepository;

public interface AttractionSlotRepository extends JpaRepository<AttractionSlot, Long> {
}
