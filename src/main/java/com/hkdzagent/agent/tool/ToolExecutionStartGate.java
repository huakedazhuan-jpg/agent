package com.hkdzagent.agent.tool;

public interface ToolExecutionStartGate {

    Decision reserve(
            ToolExecutionJournalEntry candidate,
            ToolExecutionFence fence
    );

    record Decision(
            boolean allowed,
            ToolExecutionJournalRepository.Reservation reservation
    ) {
        public Decision {
            if (allowed && reservation == null) {
                throw new IllegalArgumentException("allowed decision requires a reservation");
            }
            if (!allowed && reservation != null) {
                throw new IllegalArgumentException("denied decision must not contain a reservation");
            }
        }

        public static Decision allowed(ToolExecutionJournalRepository.Reservation reservation) {
            return new Decision(true, reservation);
        }

        public static Decision denied() {
            return new Decision(false, null);
        }
    }
}
