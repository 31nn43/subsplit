import java.math.BigDecimal;
import java.time.LocalDate;

// cycle is a label like "2026-10" saying which billing month the payment covers
public record Payment(long id, long membershipId, String cycle, BigDecimal amount, LocalDate paidOn) {}
