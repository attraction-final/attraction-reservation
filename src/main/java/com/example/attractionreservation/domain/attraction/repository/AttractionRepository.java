package com.example.attractionreservation.domain.attraction.repository;

import com.example.attractionreservation.domain.attraction.entity.Attraction;
import org.springframework.data.jpa.repository.JpaRepository;

public interface AttractionRepository extends JpaRepository<Attraction, Long> {
}
