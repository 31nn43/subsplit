import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

// All the business rules live here: no HTTP, no JSON, no storage details.
public class balanceService {

    // after a renewal date, a member has this many days to pay before showing as OVERDUE
    static final int GRACE_DAYS = 7;

    public record MemberBalance(long userId, String userName, BigDecimal share, BigDecimal paid,
                                BigDecimal owed, String status) {}

    private final Store store;

    public balanceService(Store store) { this.store = store; }

    // ---------- subscriptions ----------

    public Group createGroup(User owner, String serviceName, BigDecimal totalCost,
                             LocalDate startDate, Group.Split split) {
        if (serviceName == null || serviceName.isBlank()) throw new IllegalArgumentException("Service name is required");
        if (totalCost.signum() <= 0) throw new IllegalArgumentException("Total cost must be positive");
        return store.save(new Group(store.nextId(), owner.id(), serviceName.trim(), totalCost, startDate, split, null));
    }

    // Only the owner can add people. They're added by email, and must have signed up already.
    public Membership addMember(User me, long groupId, String email, BigDecimal share) {
        Group group = findGroup(groupId);
        requireOwner(me, group);
        requireActive(group);
        User person = store.findUserByEmail(email)
                .orElseThrow(() -> new NotFound("No registered user with that email. Ask them to sign up first."));
        if (store.findMembership(groupId, person.id()).isPresent())
            throw new IllegalArgumentException("That person is already in this subscription");

        if (group.split() == Group.Split.CUSTOM) {
            if (share == null) throw new IllegalArgumentException("Enter their share (this subscription uses a custom split)");
            if (share.signum() < 0) throw new IllegalArgumentException("Share cannot be negative");
        } else {
            share = BigDecimal.ZERO;   // even split: shares are worked out automatically
        }
        return store.save(new Membership(store.nextId(), groupId, person.id(), share));
    }

    // Members record their own payments. The owner can also record one for someone else (e.g. cash).
    public Payment recordPayment(User me, long groupId, Long userId, BigDecimal amount) {
        Group group = findGroup(groupId);
        requireActive(group);
        if (userId != null && userId != me.id()) requireOwner(me, group);
        long payerId = userId != null ? userId : me.id();

        Membership m = store.findMembership(groupId, payerId)
                .orElseThrow(() -> new NotFound(payerId == me.id()
                        ? "You're not part of this subscription"
                        : "That person is not part of this subscription"));
        if (amount.signum() <= 0) throw new IllegalArgumentException("Payment must be positive");

        LocalDate today = LocalDate.now();
        return store.save(new Payment(store.nextId(), m.id(), group.cycleStart(today).toString(), amount, today));
    }

    // Stops it renewing. It stays active until the end of the current cycle.
    public Group cancel(User me, long groupId) {
        Group group = findGroup(groupId);
        requireOwner(me, group);
        if (group.endsOn() != null) throw new IllegalArgumentException("This subscription is already cancelled");
        Group updated = group.withEndsOn(group.nextRenewal(LocalDate.now()));
        store.replace(updated);
        return updated;
    }

    // ---------- what each person is allowed to see ----------

    public List<Group> groupsFor(User me) {
        return store.allGroups().stream()
                .filter(g -> g.ownerId() == me.id() || store.findMembership(g.id(), me.id()).isPresent())
                .toList();
    }

    // me, plus everyone I share a (still running) subscription with
    public List<User> peopleFor(User me) {
        LocalDate today = LocalDate.now();
        Set<Long> ids = new LinkedHashSet<>();
        ids.add(me.id());
        for (Group g : groupsFor(me)) {
            if (g.ended(today)) continue;
            ids.add(g.ownerId());
            for (Membership m : store.membershipsOfGroup(g.id())) ids.add(m.userId());
        }
        return ids.stream().map(store::findUser).flatMap(Optional::stream).toList();
    }

    // ---------- balances ----------

    // For each member: what's their share, how much have they paid this cycle, PAID / PENDING / OVERDUE?
    public List<MemberBalance> balances(User me, long groupId) {
        Group group = findGroup(groupId);
        requireVisible(me, group);

        LocalDate today = LocalDate.now();
        LocalDate cycleStart = group.cycleStart(today);
        String cycle = cycleStart.toString();
        LocalDate dueBy = cycleStart.plusDays(GRACE_DAYS);

        List<Membership> members = store.membershipsOfGroup(groupId);
        List<MemberBalance> result = new ArrayList<>();

        for (Membership m : members) {
            BigDecimal share = shareOf(group, members, m);
            BigDecimal paid = store.paymentsFor(m.id(), cycle).stream()
                    .map(Payment::amount)
                    .reduce(BigDecimal.ZERO, BigDecimal::add);
            BigDecimal owed = share.subtract(paid).max(BigDecimal.ZERO);

            String status;
            if (owed.signum() == 0) status = "PAID";
            else if (today.isAfter(dueBy)) status = "OVERDUE";
            else status = "PENDING";

            String name = store.findUser(m.userId()).map(User::name).orElse("?");
            result.add(new MemberBalance(m.userId(), name, share, paid, owed, status));
        }
        return result;
    }

    // Custom split: the share typed in. Even split: total / members, working in whole cents
    // so the shares always add up exactly (10.00 between 3 people = 3.34 + 3.33 + 3.33).
    private BigDecimal shareOf(Group group, List<Membership> members, Membership m) {
        if (group.split() == Group.Split.CUSTOM) return m.share();
        long cents = group.totalCost().movePointRight(2).setScale(0, RoundingMode.HALF_UP).longValueExact();
        long people = members.size();
        long each = cents / people;
        long leftover = cents % people;
        int position = members.indexOf(m);
        return BigDecimal.valueOf(each + (position < leftover ? 1 : 0), 2);
    }

    // ---------- small helpers ----------

    private Group findGroup(long id) {
        return store.findGroup(id).orElseThrow(() -> new NotFound("Subscription not found: " + id));
    }

    private void requireOwner(User me, Group g) {
        if (g.ownerId() != me.id())
            throw new accessError(403, "Only the person who created this subscription can do that");
    }

    private void requireVisible(User me, Group g) {
        if (g.ownerId() != me.id() && store.findMembership(g.id(), me.id()).isEmpty())
            throw new accessError(403, "You're not part of this subscription");
    }

    private void requireActive(Group g) {
        if (g.ended(LocalDate.now())) throw new IllegalArgumentException("This subscription has ended");
    }
}
