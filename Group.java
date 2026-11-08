import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;

// One shared subscription. It renews every month on the start date's day until the owner cancels it.
public record Group(long id, long ownerId, String serviceName, BigDecimal totalCost,
                    LocalDate startDate, Split split, LocalDate endsOn) {

    public enum Split { EVEN, CUSTOM }

    public Group withEndsOn(LocalDate date) {
        return new Group(id, ownerId, serviceName, totalCost, startDate, split, date);
    }

    // true once a cancelled subscription has reached its last day
    public boolean ended(LocalDate today) {
        return endsOn != null && !today.isBefore(endsOn);
    }

    // which monthly cycle are we in? 0 = the first month after the start date
    private long cycleIndex(LocalDate today) {
        if (ended(today)) today = endsOn.minusDays(1);          // frozen on the last cycle once ended
        long k = ChronoUnit.MONTHS.between(startDate, today);
        if (k < 0) return 0;                                    // hasn't started yet
        if (!startDate.plusMonths(k + 1).isAfter(today)) k++;   // fixes start dates like the 31st
        return k;
    }

    // the most recent renewal date (the day the current cycle began)
    public LocalDate cycleStart(LocalDate today) {
        return startDate.plusMonths(cycleIndex(today));
    }

    // the next date it renews, or null if it has been cancelled
    public LocalDate nextRenewal(LocalDate today) {
        if (endsOn != null) return null;
        if (today.isBefore(startDate)) return startDate;
        return startDate.plusMonths(cycleIndex(today) + 1);
    }
}
