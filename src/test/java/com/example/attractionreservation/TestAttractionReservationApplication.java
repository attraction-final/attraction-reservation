package com.example.attractionreservation;

import org.springframework.boot.SpringApplication;

public class TestAttractionReservationApplication {

    public static void main(String[] args) {
        SpringApplication.from(AttractionReservationApplication::main).with(TestcontainersConfiguration.class).run(args);
    }

}
