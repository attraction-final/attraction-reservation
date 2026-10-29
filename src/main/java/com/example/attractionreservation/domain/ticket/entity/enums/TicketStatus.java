package com.example.attractionreservation.domain.ticket.entity.enums;

public enum TicketStatus {
    UNUSED {
        @Override
        public boolean canTransitTo(TicketStatus nextStatus) {
            return nextStatus != USED;
        }
    },
    IN_USE {
        @Override
        public boolean canTransitTo(TicketStatus nextStatus) {
            return nextStatus == USED;
        }
    },
    USED {
        @Override
        public boolean canTransitTo(TicketStatus nextStatus) {
            return false;
        }
    },
    EXPIRED {
        @Override
        public boolean canTransitTo(TicketStatus nextStatus) {
            return false;
        }
    },
    REFUNDED {
        @Override
        public boolean canTransitTo(TicketStatus nextStatus) {
            return false;
        }
    };

    public abstract boolean canTransitTo(TicketStatus nextStatus);
}
