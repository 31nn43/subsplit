import java.math.BigDecimal;

// Links one user to one group and records how much of the cost they owe per cycle
public record Membership(long id, long groupId, long userId, BigDecimal share) {}
