import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

// In-memory "database": plain lists. Everything is lost when the program stops.
public class Store {
    private long nextId = 1;
    private final List<User> users = new ArrayList<>();
    private final List<Group> groups = new ArrayList<>();
    private final List<Membership> memberships = new ArrayList<>();
    private final List<Payment> payments = new ArrayList<>();
    private final Map<String, Long> sessions = new HashMap<>();   // login token -> user id

    public synchronized long nextId() { return nextId++; }

    public synchronized User save(User u) { users.add(u); return u; }
    public synchronized Group save(Group g) { groups.add(g); return g; }
    public synchronized Membership save(Membership m) { memberships.add(m); return m; }
    public synchronized Payment save(Payment p) { payments.add(p); return p; }

    // swaps a group for an updated copy with the same id (used when cancelling)
    public synchronized void replace(Group updated) {
        for (int i = 0; i < groups.size(); i++) {
            if (groups.get(i).id() == updated.id()) groups.set(i, updated);
        }
    }

    public synchronized List<Group> allGroups() { return List.copyOf(groups); }

    public synchronized Optional<User> findUser(long id) {
        return users.stream().filter(u -> u.id() == id).findFirst();
    }

    public synchronized Optional<User> findUserByEmail(String email) {
        return users.stream().filter(u -> u.email().equalsIgnoreCase(email.trim())).findFirst();
    }

    // everyone with this name (case-insensitive); more than one person can share a name
    public synchronized List<User> findUsersByName(String name) {
        return users.stream().filter(u -> u.name().equalsIgnoreCase(name.trim())).toList();
    }


    public synchronized Optional<Group> findGroup(long id) {
        return groups.stream().filter(g -> g.id() == id).findFirst();
    }

    public synchronized List<Membership> membershipsOfGroup(long groupId) {
        return memberships.stream().filter(m -> m.groupId() == groupId).toList();
    }

    public synchronized Optional<Membership> findMembership(long groupId, long userId) {
        return memberships.stream()
                .filter(m -> m.groupId() == groupId && m.userId() == userId)
                .findFirst();
    }

    public synchronized List<Payment> paymentsFor(long membershipId, String cycle) {
        return payments.stream()
                .filter(p -> p.membershipId() == membershipId && p.cycle().equals(cycle))
                .toList();
    }

    // ----- login sessions -----
    public synchronized void putSession(String token, long userId) { sessions.put(token, userId); }
    public synchronized void endSession(String token) { sessions.remove(token); }

    public synchronized Optional<User> userForSession(String token) {
        Long id = token == null ? null : sessions.get(token);
        return id == null ? Optional.empty() : findUser(id);
    }
}
