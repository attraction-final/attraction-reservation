package com.example.attractionreservation.domain.attractionSlot.entity;

import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Getter
@Entity
@Table(name = "attraction_slots")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class AttractionSlot {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
}
